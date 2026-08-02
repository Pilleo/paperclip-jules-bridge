package com.pilleo.bridge

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JulesClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: JulesClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")

        client = JulesClient(baseUrl, "test-api-key")
    }

    @AfterEach
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `test createSession successfully parses response`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id": "session_123",
                        "state": "QUEUED"
                    }
                """.trimIndent())
        )

        val request = JulesSessionRequest(
            prompt = "Do some work",
            title = "A task",
            sourceContext = SourceContext(
                source = "sources/github/org/repo",
                githubRepoContext = GithubRepoContext(startingBranch = "main")
            )
        )

        val session = client.createSession(request)
        assertNotNull(session)
        assertEquals("session_123", session.id)
        assertEquals("QUEUED", session.state)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("/sessions", recordedRequest.path)
        assertEquals("test-api-key", recordedRequest.getHeader("x-goog-api-key"))
    }
}
