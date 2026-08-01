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

class ApplicationTest {

    private fun setupTempDb(): String {
        val dbFile = File.createTempFile("test-app-db-", ".sqlite")
        dbFile.deleteOnExit()
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
            configureRouting(RunRepository(setupTempDb()))
        }
        val response = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `test health ready endpoint`() = testApplication {
        application {
            configureRouting(RunRepository(setupTempDb()))
        }
        val response = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Ready", response.bodyAsText())
    }

    @Test
    fun `test webhook endpoint with missing auth`() = testApplication {
        application {
            configureRouting(RunRepository(setupTempDb()))
            configureSecurity("secret-token")
        }
        val response = client.post("/v1/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"runId":"123", "taskId":"456"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test webhook endpoint with invalid auth`() = testApplication {
        application {
            configureRouting(RunRepository(setupTempDb()))
            configureSecurity("secret-token")
        }
        val response = client.post("/v1/invocations") {
            header("Authorization", "Bearer wrong-token")
            contentType(ContentType.Application.Json)
            setBody("""{"runId":"123", "taskId":"456"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test webhook endpoint creates new run successfully`() = testApplication {
        val dbUrl = setupTempDb()
        val repo = RunRepository(dbUrl)

        application {
            configureSerialization()
            configureSecurity("secret-token")
            configureRouting(repo)
        }

        val requestBody = """
            {
                "runId": "run_abc123",
                "agentId": "agent_xyz789",
                "companyId": "company_456",
                "taskId": "task_123",
                "issueId": "issue_123",
                "wakeReason": "task_assigned",
                "context": {
                    "taskId": "task_123",
                    "wakeReason": "task_assigned",
                    "paperclipWorkspace": {
                        "cwd": "/workspace",
                        "source": "manual"
                    }
                },
                "unknownField": "should_be_ignored"
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

        // Ensure idempotency
        val response2 = client.post("/v1/invocations") {
            header("Authorization", "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.Accepted, response2.status)
        val json2 = Json.parseToJsonElement(response2.bodyAsText()).jsonObject
        assertEquals(executionId, json2["executionId"]?.jsonPrimitive?.content)
    }
}
