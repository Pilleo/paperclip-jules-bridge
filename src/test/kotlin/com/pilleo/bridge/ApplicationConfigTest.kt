package com.pilleo.bridge

import io.ktor.server.testing.*
import io.ktor.server.config.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ApplicationConfigTest {

    @Test
    fun `test module loads config correctly`() = testApplication {
        environment {
            config = MapApplicationConfig().apply {
                put("database.url", "jdbc:sqlite::memory:")
                put("paperclip.baseUrl", "http://paperclip")
                put("paperclip.apiToken", "token")
                put("jules.apiBaseUrl", "http://jules")
                put("jules.apiKey", "key")
                put("bridge.allowedRepositories", listOf("org/repo"))
                put("bridge.invariantsFile", "src/test/resources/invariants.md")
                put("bridge.authToken", "auth")
            }
        }

        application {
            module()
        }

        // If it starts and config loads without exception, we're good
        assertTrue(true)
    }
}
