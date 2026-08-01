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
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PollingWorkerTest {

    private lateinit var dbFile: File
    private lateinit var repository: RunRepository
    private lateinit var julesServer: MockWebServer
    private lateinit var paperclipServer: MockWebServer
    private lateinit var julesClient: JulesClient
    private lateinit var paperclipClient: PaperclipClient
    private lateinit var worker: PollingWorker

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-poll-", ".sqlite")
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        Flyway.configure()
            .dataSource(jdbcUrl, "", "")
            .load()
            .migrate()

        repository = RunRepository(jdbcUrl)

        julesServer = MockWebServer()
        julesServer.start()
        julesClient = JulesClient(julesServer.url("/").toString().removeSuffix("/"), "key")

        paperclipServer = MockWebServer()
        paperclipServer.start()
        paperclipClient = PaperclipClient(paperclipServer.url("/").toString().removeSuffix("/"), "token")

        worker = PollingWorker(
            repository = repository,
            julesClient = julesClient,
            paperclipClient = paperclipClient,
            pollIntervalSeconds = 1,
            leaseDurationSeconds = 60,
            maxSessionAgeHours = 12
        )
    }

    @AfterEach
    fun teardown() {
        worker.stop()
        julesServer.shutdown()
        paperclipServer.shutdown()
        dbFile.delete()
    }

    @Test
    fun `test polling non-terminal PAUSED state`() = runBlocking {
        val runId = "test-run-1"
        repository.insertIfAbsent(runId, "pc-run-1", "task-1", "org/repo", "main", "hash", "RECEIVED")
        repository.updateSession(runId, "jules-1", "url", "IN_PROGRESS", "SESSION_RUNNING", null)

        julesServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"jules-1","state":"PAUSED"}""")
        )

        worker.poll()

        val run = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals("PAUSED", run.julesState)
        assertEquals("SESSION_RUNNING", run.state) // Bridge state continues running
        assertNull(run.callbackDeliveredAt)
    }

    @Test
    fun `test polling terminal COMPLETED state transitions to in_review and sends callback precisely once`() = runBlocking {
        val runId = "test-run-1"
        repository.insertIfAbsent(runId, "pc-run-1", "task-1", "org/repo", "main", "hash", "RECEIVED")
        repository.updateSession(runId, "jules-1", "url", "IN_PROGRESS", "SESSION_RUNNING", null)

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

        // Mock Paperclip PATCH issue status
        paperclipServer.enqueue(MockResponse().setResponseCode(200))
        // Mock Paperclip POST callback
        paperclipServer.enqueue(MockResponse().setResponseCode(200))

        worker.poll()

        val run = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals("COMPLETED", run.julesState)
        assertEquals("SESSION_COMPLETED", run.state)
        assertEquals("https://github.com/pr/1", run.prUrl)
        assertNotNull(run.callbackDeliveredAt)

        val patchReq = paperclipServer.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("/api/issues/task-1", patchReq.path)
        val patchBody = patchReq.body.readUtf8()
        assertEquals("in_review", Json.parseToJsonElement(patchBody).jsonObject["status"]?.jsonPrimitive?.content)

        val callbackReq = paperclipServer.takeRequest()
        assertEquals("POST", callbackReq.method)
        assertEquals("/api/heartbeat-runs/pc-run-1/callback", callbackReq.path)
        val callbackBody = callbackReq.body.readUtf8()
        assertEquals("succeeded", Json.parseToJsonElement(callbackBody).jsonObject["status"]?.jsonPrimitive?.content)

        // Poll again - should NOT send duplicate callback
        worker.poll()
        assertEquals(2, paperclipServer.requestCount) // Still 2, no more requests sent
    }

    @Test
    fun `test timeouts trigger callback`() = runBlocking {
        val runId = "test-run-1"
        repository.insertIfAbsent(runId, "pc-run-1", "task-1", "org/repo", "main", "hash", "RECEIVED")
        repository.updateSession(runId, "jules-1", "url", "IN_PROGRESS", "SESSION_RUNNING", null)

        // Force createdAt back to simulate timeout
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        java.sql.DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.prepareStatement("UPDATE runs SET created_at = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, Instant.now().minus(13, java.time.temporal.ChronoUnit.HOURS).toString())
                stmt.setString(2, runId)
                stmt.executeUpdate()
            }
        }

        paperclipServer.enqueue(MockResponse().setResponseCode(200))

        worker.poll()

        val run = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals("TIMED_OUT", run.state)
        assertNotNull(run.callbackDeliveredAt)

        val callbackReq = paperclipServer.takeRequest()
        val callbackBody = callbackReq.body.readUtf8()
        assertEquals("failed", Json.parseToJsonElement(callbackBody).jsonObject["status"]?.jsonPrimitive?.content)
    }
}
