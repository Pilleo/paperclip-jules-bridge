package com.pilleo.bridge

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import org.springframework.test.context.TestPropertySource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.springframework.web.client.RestClient
import org.springframework.test.context.ContextConfiguration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [BridgeApplication::class])
@ContextConfiguration(initializers = [BridgeApplicationContextInitializer::class])
@TestPropertySource(properties = [
    "jules.apiBaseUrl=http://localhost:9092",
    "paperclip.baseUrl=http://localhost:9093",
    "bridge.authToken=auth",
    "jules.apiKey=key",
    "bridge.invariantsFile=src/test/resources/invariants.md",
    "database.url=jdbc:sqlite::memory:"
])
class HealthEndpointTest {

    @LocalServerPort
    var port: Int = 0

    companion object {
        lateinit var julesServer: MockWebServer

        @JvmStatic
        @BeforeAll
        fun setupAll() {
            julesServer = MockWebServer()
            julesServer.start(9092)
            julesServer.enqueue(
                MockResponse().setResponseCode(200).setBody("[]")
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownAll() {
            julesServer.shutdown()
        }
    }

    @Test
    fun `test live endpoint`() {
        val client = RestClient.create("http://localhost:" + port)
        val entity = client.get().uri("/health/live").retrieve().toEntity(String::class.java)

        assertEquals(HttpStatus.OK, entity.statusCode)
        assertEquals("OK", entity.body)
    }

    @Test
    fun `test ready endpoint`() {
        val client = RestClient.create("http://localhost:" + port)
        val entity = client.get().uri("/health/ready").retrieve().toEntity(String::class.java)

        assertEquals(HttpStatus.OK, entity.statusCode)
        assertEquals("OK", entity.body)
    }
}
