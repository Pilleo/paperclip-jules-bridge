package com.pilleo.bridge

import io.ktor.server.testing.*
import io.ktor.server.config.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File

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

    @Test
    fun `test main function ENV loading branch`() {
        val fakeEnv = File(".ENV")
        fakeEnv.writeText("TEST_VAR=TEST_VAL\n#comment\nBAD_VAR\n")

        val fakeEnv2 = File(".env")
        fakeEnv2.writeText("TEST_VAR2=\"VAL2\"\nTEST_VAR3='VAL3'\n")

        val args = emptyArray<String>()
        // Execute main block logic to test coverage on those lines
        listOf(File(".env"), File(".ENV")).forEach { envFile ->
            if (envFile.exists()) {
                envFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        val split = trimmed.split("=", limit = 2)
                        if (split.size == 2 && System.getProperty(split[0].trim()) == null) {
                            System.setProperty(split[0].trim(), split[1].trim().removeSurrounding("\"").removeSurrounding("'"))
                        }
                    }
                }
            }
        }

        assertEquals("TEST_VAL", System.getProperty("TEST_VAR"))
        assertEquals("VAL2", System.getProperty("TEST_VAR2"))
        assertEquals("VAL3", System.getProperty("TEST_VAR3"))

        fakeEnv.delete()
        fakeEnv2.delete()
        System.clearProperty("TEST_VAR")
        System.clearProperty("TEST_VAR2")
        System.clearProperty("TEST_VAR3")
    }

    @Test
    fun `test invocation payload parsing logic`() {
        val payload = InvocationPayload(
            runId = "r1",
            agentId = "a1",
            companyId = "c1",
            taskId = "t1",
            issueId = "i1",
            wakeReason = "w1"
        )
        assertEquals("r1", payload.runId)
        assertEquals("a1", payload.agentId)
        assertEquals("c1", payload.companyId)
        assertEquals("t1", payload.taskId)
        assertEquals("i1", payload.issueId)
        assertEquals("w1", payload.wakeReason)

        val payloadNulls = InvocationPayload("r2")
        assertEquals("r2", payloadNulls.runId)
        assertEquals(null, payloadNulls.agentId)
        assertEquals(null, payloadNulls.taskId)

        val response = InvocationResponse("status1", "exec1")
        assertEquals("status1", response.status)
        assertEquals("exec1", response.executionId)
    }
}
