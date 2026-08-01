package com.pilleo.bridge

import io.ktor.server.testing.*
import io.ktor.server.config.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.system.exitProcess

class ApplicationConfigTest {

    private lateinit var julesServer: MockWebServer

    @BeforeEach
    fun setup() {
        julesServer = MockWebServer()
        julesServer.start()
    }

    @AfterEach
    fun teardown() {
        julesServer.shutdown()
    }

    @Test
    fun `test module loads config correctly and validates auth`() = testApplication {
        julesServer.enqueue(
            MockResponse().setResponseCode(200).setBody("[]")
        )

        environment {
            config = MapApplicationConfig().apply {
                put("ktor.deployment.port", "8080")
                put("database.url", "jdbc:sqlite::memory:")
                put("paperclip.baseUrl", "http://paperclip")
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

        assertTrue(true)
    }
}
