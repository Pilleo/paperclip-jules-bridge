package com.pilleo.bridge

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    @Test
    fun `test getSession parses outputs correctly`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id": "session_123",
                        "state": "COMPLETED",
                        "outputs": [
                            {
                                "pullRequest": {
                                    "url": "https://github.com/org/repo/pull/1"
                                }
                            }
                        ]
                    }
                """.trimIndent())
        )

        val session = client.getSession("session_123")
        assertNotNull(session)
        assertEquals("session_123", session.id)
        assertEquals("COMPLETED", session.state)
        assertEquals(1, session.outputs.size)
        assertEquals("https://github.com/org/repo/pull/1", session.outputs[0].pullRequest?.url)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals("/sessions/session_123", recordedRequest.path)
        assertEquals("test-api-key", recordedRequest.getHeader("x-goog-api-key"))
    }

    @Test
    fun `test client retries on failure`() = runBlocking {
        // Enqueue 2 failures then a success
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"session_123", "state":"QUEUED"}""")
        )

        val session = client.getSession("session_123")
        assertEquals("session_123", session.id)
        assertEquals(3, mockWebServer.requestCount)
    }
}
