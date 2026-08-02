package com.pilleo.bridge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import org.springframework.http.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import java.util.function.Supplier

@Serializable
data class InvocationPayload(
    val runId: String,
    val agentId: String? = null,
    val companyId: String? = null,
    val taskId: String? = null,
    val issueId: String? = null,
    val wakeReason: String? = null,
    val context: JsonElement? = null
)

@Serializable
data class InvocationResponse(
    val status: String,
    val executionId: String
)

@SpringBootApplication
class BridgeApplication

fun main(args: Array<String>) {
    // Load local ENV
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

    SpringApplicationBuilder(BridgeApplication::class.java)
        .initializers(BridgeApplicationContextInitializer())
        .run(*args)
}

class BridgeApplicationContextInitializer : ApplicationContextInitializer<GenericApplicationContext> {
    private val logger = LoggerFactory.getLogger(BridgeApplicationContextInitializer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun initialize(context: GenericApplicationContext) {

        context.registerBean("bridgeRoutes", RouterFunction::class.java, Supplier {
            val runRepository = context.getBean(RunRepository::class.java)
            val julesClient = context.getBean(JulesClient::class.java)
            val paperclipClient = context.getBean(PaperclipClient::class.java)
            val promptBuilder = context.getBean(PromptBuilder::class.java)
            val bridgeAuthToken = context.environment.getProperty("bridge.authToken") ?: ""
            val allowedRepositoriesStr = context.environment.getProperty("bridge.allowedRepositories") ?: ""
            val allowedRepositories = allowedRepositoriesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            router {
                POST("/v1/invocations") { request ->
                    val authHeader = request.headers().firstHeader("Authorization")
                    if (authHeader == null || !authHeader.startsWith("Bearer ") || authHeader.removePrefix("Bearer ") != bridgeAuthToken) {
                        return@POST ServerResponse.status(401).build()
                    }

                    val payloadStr = request.body(String::class.java)
                    val payload = try {
                        json.decodeFromString<InvocationPayload>(payloadStr)
                    } catch (e: Exception) {
                        return@POST ServerResponse.status(400).body("Invalid payload")
                    }

                    val executionId = "exec_" + UUID.randomUUID().toString().replace("-", "")

                    val taskId = payload.issueId ?: payload.taskId
                    if (taskId == null) {
                        return@POST ServerResponse.status(400).body("Missing issueId/taskId")
                    }

                    val targetRepo = allowedRepositories.firstOrNull() ?: "Pilleo/mazewall"

                    val insertedRunId = runRepository.insertIfAbsent(
                        runId = executionId,
                        paperclipRunId = payload.runId,
                        paperclipTaskId = taskId,
                        repository = targetRepo,
                        baseBranch = "main",
                        promptHash = "pending",
                        state = "RECEIVED"
                    )

                    val response = InvocationResponse("accepted", insertedRunId)

                    if (insertedRunId == executionId) {
                        Thread.ofVirtual().start {
                            try {
                                runBlocking {
                                    val run = runRepository.findByPaperclipRunId(payload.runId) ?: return@runBlocking
                                    try {
                                        runRepository.updateState(run.id, "CREATING_SESSION")
                                        val issue = paperclipClient.getIssue(taskId)
                                        if (issue == null) {
                                            runRepository.updateState(run.id, "SESSION_FAILED")
                                            return@runBlocking
                                        }
                                        val promptStr = promptBuilder.buildPrompt(issue, targetRepo)
                                        val promptHash = promptBuilder.hashPrompt(promptStr)
                                        val julesRequest = JulesSessionRequest(
                                            prompt = promptStr,
                                            title = issue.title,
                                            sourceContext = SourceContext(
                                                source = "sources/github/" + targetRepo,
                                                githubRepoContext = GithubRepoContext(startingBranch = "main")
                                            )
                                        )
                                        val session = julesClient.createSession(julesRequest)
                                        runRepository.updateRunStartDetails(
                                            runId = run.id,
                                            promptHash = promptHash,
                                            julesSessionId = session.id,
                                            julesState = session.state
                                        )
                                    } catch (e: Exception) {
                                        runRepository.updateState(run.id, "SESSION_FAILED")
                                    }
                                }
                            } catch (e: Exception) {
                                logger.error("Failed to asynchronously start Jules session for run " + payload.runId, e)
                            }
                        }
                    }

                    ServerResponse.accepted()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json.encodeToString(response))
                }

                GET("/health/live") {
                    ServerResponse.ok().body("OK")
                }

                GET("/health/ready") {
                    try {
                        runRepository.findAllActiveRuns()
                        ServerResponse.ok().body("OK")
                    } catch (e: Exception) {
                        ServerResponse.status(503).body("Database connection failed")
                    }
                }
            }
        })

        context.addApplicationListener { event ->
            if (event is org.springframework.boot.context.event.ApplicationReadyEvent) {
                try {
                    val jdbcTemplate = context.getBean(JdbcTemplate::class.java)
                    jdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS runs (
                            id                      TEXT PRIMARY KEY,
                            paperclip_run_id        TEXT UNIQUE NOT NULL,
                            paperclip_task_id       TEXT NOT NULL,
                            paperclip_agent_id      TEXT,
                            paperclip_company_id    TEXT,
                            jules_session_id        TEXT,
                            jules_session_url       TEXT,
                            repository              TEXT NOT NULL,
                            base_branch             TEXT NOT NULL,
                            prompt_hash             TEXT NOT NULL,
                            state                   TEXT NOT NULL,
                            jules_state             TEXT,
                            pr_url                  TEXT,
                            pr_number               INTEGER,
                            error_code              TEXT,
                            error_message           TEXT,
                            created_at              TEXT NOT NULL,
                            updated_at              TEXT NOT NULL,
                            last_polled_at          TEXT,
                            next_poll_at            TEXT,
                            poll_attempts           INTEGER DEFAULT 0,
                            callback_attempts       INTEGER DEFAULT 0,
                            callback_delivered_at   TEXT,
                            lease_owner             TEXT,
                            lease_expires_at        TEXT
                        );
                    """.trimIndent())
                } catch (e: Exception) {
                    logger.error("Failed to execute DB setup hook.", e)
                }

                try {
                    val julesClient = context.getBean(JulesClient::class.java)
                    val thread = Thread.ofVirtual().start {
                        try {
                            julesClient.validateAuth()
                        } catch (e: Exception) {
                            logger.error("CRITICAL Startup Failed: Jules API Authentication is invalid.", e)
                        }
                    }
                    thread.join()
                } catch (e: Exception) {
                    logger.error("Startup validation error.", e)
                }
            }
        }
    }
}
