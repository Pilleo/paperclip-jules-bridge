package com.pilleo.bridge

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RestartRecoveryTest {

    private lateinit var dbFile: File
    private lateinit var julesServer: MockWebServer
    private lateinit var paperclipServer: MockWebServer

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-recovery-", ".sqlite")

        julesServer = MockWebServer()
        julesServer.start()

        paperclipServer = MockWebServer()
        paperclipServer.start()
    }

    @AfterEach
    fun teardown() {
        julesServer.shutdown()
        paperclipServer.shutdown()
        dbFile.delete()
    }

    @Test
    fun `test restart recovery process precisely single callback delivery`() = runBlocking {
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        Flyway.configure().dataSource(jdbcUrl, "", "").load().migrate()

        val repository = RunRepository(jdbcUrl)
        val julesClient = JulesClient(julesServer.url("/").toString().removeSuffix("/"), "key")
        val paperclipClient = PaperclipClient(paperclipServer.url("/").toString().removeSuffix("/"), "token")

        // Instance 1
        val worker1 = PollingWorker(
            repository = repository,
            julesClient = julesClient,
            paperclipClient = paperclipClient,
            pollIntervalSeconds = 1,
            leaseDurationSeconds = 60
        )

        // 1. Initial invocation accepted
        val runId = "test-run-1"
        repository.insertIfAbsent(runId, "pc-run-1", "task-1", "org/repo", "main", "hash", "RECEIVED")
        repository.updateSession(runId, "jules-1", "url", "IN_PROGRESS", "SESSION_RUNNING", null)

        // Let's pretend Instance 1 claims lease and updates lease expiration in the past (to simulate a crash)
        val past = Instant.now().minusSeconds(120)
        repository.claimLease(runId, "worker-1", past, Instant.now())

        // 2. Kill ungracefully (stop without finalizing). The record is in SESSION_RUNNING but lease is expired.
        worker1.stop()

        // 3. Start a new Instance against same DB
        val worker2 = PollingWorker(
            repository = repository,
            julesClient = julesClient,
            paperclipClient = paperclipClient,
            pollIntervalSeconds = 1,
            leaseDurationSeconds = 60
        )

        // Enqueue Jules terminal state for the new polling attempt
        julesServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id":"jules-1",
                        "state":"COMPLETED",
                        "outputs":[{"pullRequest":{"url":"https://github.com/pr/1"}}]
                    }
                """.trimIndent())
        )

        // Enqueue Paperclip status patch & callback
        paperclipServer.enqueue(MockResponse().setResponseCode(200))
        paperclipServer.enqueue(MockResponse().setResponseCode(200))

        // 4. Force poll round
        worker2.poll()

        // Verify Run status
        val run = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals("COMPLETED", run.julesState)
        assertEquals("SESSION_COMPLETED", run.state)
        assertEquals("https://github.com/pr/1", run.prUrl)
        assertNotNull(run.callbackDeliveredAt)

        // Verify callbacks were sent exactly once.
        assertEquals(2, paperclipServer.requestCount) // 1 patch, 1 post callback

        val patchReq = paperclipServer.takeRequest()
        assertEquals("PATCH", patchReq.method)

        val callbackReq = paperclipServer.takeRequest()
        assertEquals("POST", callbackReq.method)
        assertEquals("/api/heartbeat-runs/pc-run-1/callback", callbackReq.path)
        val callbackBody = callbackReq.body.readUtf8()
        assertEquals("succeeded", Json.parseToJsonElement(callbackBody).jsonObject["status"]?.jsonPrimitive?.content)

        // Test idempotency explicitly: try to poll again
        worker2.poll()
        // Ensure no new requests sent
        assertEquals(2, paperclipServer.requestCount)

        worker2.stop()
    }
}
