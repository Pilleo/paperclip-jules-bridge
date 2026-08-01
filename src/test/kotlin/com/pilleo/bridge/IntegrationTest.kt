package com.pilleo.bridge

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import io.ktor.server.config.MapApplicationConfig
import java.time.Instant

class IntegrationTest {

    private lateinit var dbFile: File
    private lateinit var paperclipServer: MockWebServer
    private lateinit var julesServer: MockWebServer

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-int-db-", ".sqlite")
        dbFile.deleteOnExit()

        paperclipServer = MockWebServer()
        paperclipServer.start()

        julesServer = MockWebServer()
        julesServer.start()
    }

    @AfterEach
    fun teardown() {
        paperclipServer.shutdown()
        julesServer.shutdown()
        dbFile.delete()
    }

    @Test
    fun `test full pipeline failure handling`() = testApplication {
        val dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        environment {
            config = MapApplicationConfig().apply {
                put("database.url", dbUrl)
                put("paperclip.baseUrl", paperclipServer.url("/").toString().removeSuffix("/"))
                put("paperclip.apiToken", "token")
                put("jules.apiBaseUrl", julesServer.url("/").toString().removeSuffix("/"))
                put("jules.apiKey", "key")
                put("bridge.allowedRepositories", listOf("org/repo"))
                put("bridge.invariantsFile", "src/test/resources/invariants.md")
                put("bridge.authToken", "auth")
            }
        }

        application {
            module()
        }

        // Simulate 404 from Paperclip (issue not found)
        paperclipServer.enqueue(MockResponse().setResponseCode(404))

        val requestBody = """
            {
                "runId": "run_fail123",
                "taskId": "task_123"
            }
        """.trimIndent()

        val response = client.post("/v1/invocations") {
            header("Authorization", "Bearer auth")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)

        val repo = RunRepository(dbUrl)
        val run = repo.findByPaperclipRunId("run_fail123")!!
        assertEquals("FAILED", run.state) // Should mark as failed because issue not found
    }
}
