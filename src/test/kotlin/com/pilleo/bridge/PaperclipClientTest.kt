package com.pilleo.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperclipClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: PaperclipClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")

        client = PaperclipClient(baseUrl, "test-token")
    }

    @AfterEach
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `test getIssue successfully parses response`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id": "issue_123",
                        "title": "Test Issue",
                        "description": "A description",
                        "status": "todo",
                        "priority": "high",
                        "parentId": null,
                        "project": { "id": "proj_1" },
                        "unknown": "field"
                    }
                """.trimIndent())
        )

        val issue = client.getIssue("issue_123")
        assertNotNull(issue)
        assertEquals("issue_123", issue.id)
        assertEquals("Test Issue", issue.title)
        assertEquals("todo", issue.status)
        assertEquals("high", issue.priority)
        assertNull(issue.parentId)

        val request = mockWebServer.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/issues/issue_123", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `test getIssue returns null on 404`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val issue = client.getIssue("unknown")
        assertNull(issue)
    }

    @Test
    fun `test updateIssueStatus handles 422 validation error gracefully`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(422))

        val result = client.updateIssueStatus("issue_123", "in_review", "Testing")
        assertFalse(result) // Indicates transition failed

        val request = mockWebServer.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/issues/issue_123", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `test updateIssueStatus returns true on success`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = client.updateIssueStatus("issue_123", "in_review", "Testing")
        assertTrue(result)
    }

    @Test
    fun `test sendCallback successfully`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = client.sendCallback("run_123", CallbackRequest(
            status = "succeeded",
            result = "All good",
            errorMessage = null
        ))
        assertTrue(result)

        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/heartbeat-runs/run_123/callback", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))

        val bodyStr = request.body.readUtf8()
        assertTrue(bodyStr.contains("\"status\":\"succeeded\""))
    }
}
