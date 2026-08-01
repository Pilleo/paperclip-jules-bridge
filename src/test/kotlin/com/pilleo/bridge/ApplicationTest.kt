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

class ApplicationTest {

    private lateinit var dbFile: File
    private lateinit var paperclipServer: MockWebServer
    private lateinit var julesServer: MockWebServer

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-app-db-", ".sqlite")
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

    private fun setupTempDb(): String {
        val dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        Flyway.configure()
            .dataSource(dbUrl, "", "")
            .load()
            .migrate()
        return dbUrl
    }

    @Test
    fun `test health live endpoint`() = testApplication {
        application {
            val repo = RunRepository(setupTempDb())
            val pc = PaperclipClient(paperclipServer.url("/").toString(), "token")
            val jc = JulesClient(julesServer.url("/").toString(), "key")
            val pb = PromptBuilder(listOf("org/repo"), "src/test/resources/invariants.md")
            configureRouting(repo, pc, jc, pb, listOf("org/repo"), false, "AUTO_CREATE_PR")
        }
        val response = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `test health ready endpoint`() = testApplication {
        application {
            val repo = RunRepository(setupTempDb())
            val pc = PaperclipClient(paperclipServer.url("/").toString(), "token")
            val jc = JulesClient(julesServer.url("/").toString(), "key")
            val pb = PromptBuilder(listOf("org/repo"), "src/test/resources/invariants.md")
            configureRouting(repo, pc, jc, pb, listOf("org/repo"), false, "AUTO_CREATE_PR")
        }
        val response = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Ready", response.bodyAsText())
    }

    @Test
    fun `test webhook endpoint with missing auth`() = testApplication {
        application {
            val repo = RunRepository(setupTempDb())
            val pc = PaperclipClient(paperclipServer.url("/").toString(), "token")
            val jc = JulesClient(julesServer.url("/").toString(), "key")
            val pb = PromptBuilder(listOf("org/repo"), "src/test/resources/invariants.md")
            configureRouting(repo, pc, jc, pb, listOf("org/repo"), false, "AUTO_CREATE_PR")
            configureSecurity("secret-token")
        }
        val response = client.post("/v1/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"runId":"123", "taskId":"456"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test webhook endpoint creates new run successfully`() = testApplication {
        val dbUrl = setupTempDb()
        val repo = RunRepository(dbUrl)
        val pc = PaperclipClient(paperclipServer.url("/").toString().removeSuffix("/"), "token")
        val jc = JulesClient(julesServer.url("/").toString().removeSuffix("/"), "key")
        val pb = PromptBuilder(listOf("org/repo"), "src/test/resources/invariants.md")

        File("src/test/resources/invariants.md").writeText("Test invariants")

        application {
            configureSerialization()
            configureSecurity("secret-token")
            configureRouting(repo, pc, jc, pb, listOf("org/repo"), false, "AUTO_CREATE_PR")
        }

        paperclipServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id": "task_123",
                        "title": "Test Task",
                        "description": "desc",
                        "status": "todo",
                        "priority": "high",
                        "parentId": null,
                        "project": null
                    }
                """.trimIndent())
        )

        julesServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"session_xyz","state":"QUEUED"}""")
        )

        val requestBody = """
            {
                "runId": "run_abc123",
                "agentId": "agent_xyz789",
                "companyId": "company_456",
                "taskId": "task_123",
                "issueId": "task_123",
                "wakeReason": "task_assigned",
                "context": {
                    "taskId": "task_123",
                    "wakeReason": "task_assigned"
                }
            }
        """.trimIndent()

        val response = client.post("/v1/invocations") {
            header("Authorization", "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)

        val responseBody = response.bodyAsText()
        val json = Json.parseToJsonElement(responseBody).jsonObject
        assertEquals("accepted", json["status"]?.jsonPrimitive?.content)
        val executionId = json["executionId"]?.jsonPrimitive?.content
        assertTrue(executionId != null && executionId.isNotEmpty())

        // Assert DB state
        val run = repo.findByPaperclipRunId("run_abc123")!!
        assertEquals("session_xyz", run.julesSessionId)
        assertEquals("QUEUED", run.julesState)
        assertEquals("SESSION_RUNNING", run.state)

        // Ensure idempotency (should just return 202 without calling APIs again)
        val response2 = client.post("/v1/invocations") {
            header("Authorization", "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.Accepted, response2.status)
        val json2 = Json.parseToJsonElement(response2.bodyAsText()).jsonObject
        assertEquals(executionId, json2["executionId"]?.jsonPrimitive?.content)

        // Servers should only have received 1 request each
        assertEquals(1, paperclipServer.requestCount)
        assertEquals(1, julesServer.requestCount)
    }
}
