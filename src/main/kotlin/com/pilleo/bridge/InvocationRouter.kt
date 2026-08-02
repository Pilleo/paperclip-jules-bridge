package com.pilleo.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import org.springframework.beans.factory.annotation.Value
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlinx.coroutines.runBlocking

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

@Configuration
class InvocationRouter(
    private val runRepository: RunRepository,
    private val julesClient: JulesClient,
    private val paperclipClient: PaperclipClient,
    private val promptBuilder: PromptBuilder,
    @Value("\${bridge.authToken}") private val bridgeAuthToken: String,
    @Value("\${bridge.allowedRepositories:}") private val allowedRepositoriesStr: String
) {
    private val logger = LoggerFactory.getLogger(InvocationRouter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val allowedRepositories = allowedRepositoriesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    @Bean
    fun bridgeRoutes(): RouterFunction<ServerResponse> = router {
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

            // Prefer issueId over taskId
            val taskId = payload.issueId ?: payload.taskId
            if (taskId == null) {
                return@POST ServerResponse.status(400).body("Missing issueId/taskId")
            }

            val targetRepo = allowedRepositories.firstOrNull() ?: "Pilleo/mazewall"

            // Idempotency Check & initial insert
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

            // If it's a new run, we need to create the session asynchronously
            if (insertedRunId == executionId) {
                Thread.ofVirtual().start {
                    try {
                        runBlocking {
                            createJulesSession(payload.runId, taskId, targetRepo)
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
                // Ensure DB connection is alive
                runRepository.findAllActiveRuns()
                ServerResponse.ok().body("OK")
            } catch (e: Exception) {
                ServerResponse.status(503).body("Database connection failed")
            }
        }
    }

    private suspend fun createJulesSession(paperclipRunId: String, taskId: String, targetRepo: String) {
        val run = runRepository.findByPaperclipRunId(paperclipRunId) ?: return

        try {
            runRepository.updateState(run.id, "CREATING_SESSION")

            val issue = paperclipClient.getIssue(taskId)
            if (issue == null) {
                runRepository.updateState(run.id, "SESSION_FAILED")
                logger.error("Could not fetch issue \$taskId from Paperclip")
                return
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
            throw e
        }
    }
}
