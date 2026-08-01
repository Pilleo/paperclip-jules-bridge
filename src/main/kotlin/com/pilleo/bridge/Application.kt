package com.pilleo.bridge

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.util.UUID

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val config = environment.config

    val dbUrl = config.propertyOrNull("database.url")?.getString() ?: "jdbc:sqlite:runs.sqlite"

    // Run Migrations
    Flyway.configure()
        .dataSource(dbUrl, "", "")
        .load()
        .migrate()

    val repository = RunRepository(dbUrl)

    val paperclipBaseUrl = config.property("paperclip.baseUrl").getString()
    val paperclipApiToken = config.property("paperclip.apiToken").getString()
    val paperclipClient = PaperclipClient(paperclipBaseUrl, paperclipApiToken)

    val julesApiBaseUrl = config.property("jules.apiBaseUrl").getString()
    val julesApiKey = config.property("jules.apiKey").getString()
    val requirePlanApproval = config.propertyOrNull("jules.requirePlanApproval")?.getString()?.toBoolean() ?: false
    val automationMode = config.propertyOrNull("jules.automationMode")?.getString() ?: "AUTO_CREATE_PR"
    val julesClient = JulesClient(julesApiBaseUrl, julesApiKey)

    val allowedRepositories = config.property("bridge.allowedRepositories").getList()
    val invariantsFile = config.property("bridge.invariantsFile").getString()
    val promptBuilder = PromptBuilder(allowedRepositories, invariantsFile)

    val bridgeAuthToken = config.property("bridge.authToken").getString()

    val pollIntervalSeconds = config.propertyOrNull("polling.intervalSeconds")?.getString()?.toLong() ?: 45
    val maxSessionAgeHours = config.propertyOrNull("polling.maxSessionAgeHours")?.getString()?.toLong() ?: 12

    val pollingWorker = PollingWorker(
        repository = repository,
        julesClient = julesClient,
        paperclipClient = paperclipClient,
        pollIntervalSeconds = pollIntervalSeconds,
        maxSessionAgeHours = maxSessionAgeHours
    )

    pollingWorker.start()

    environment.monitor.subscribe(ApplicationStopping) {
        pollingWorker.stop()
    }

    configureSerialization()
    configureSecurity(bridgeAuthToken)
    configureRouting(repository, paperclipClient, julesClient, promptBuilder, allowedRepositories, requirePlanApproval, automationMode)
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        })
    }
}

fun Application.configureSecurity(expectedToken: String) {
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, cause.localizedMessage ?: "Internal Server Error")
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path == "/v1/invocations") {
            val authHeader = call.request.header("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ") || authHeader.removePrefix("Bearer ") != expectedToken) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }
}

@Serializable
data class InvocationPayload(
    val runId: String,
    val agentId: String? = null,
    val companyId: String? = null,
    val taskId: String? = null,
    val issueId: String? = null,
    val wakeReason: String? = null
)

@Serializable
data class InvocationResponse(
    val status: String,
    val executionId: String
)

fun Application.configureRouting(
    repository: RunRepository,
    paperclipClient: PaperclipClient,
    julesClient: JulesClient,
    promptBuilder: PromptBuilder,
    allowedRepositories: List<String>,
    requirePlanApproval: Boolean,
    automationMode: String
) {
    routing {
        get("/health/live") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        get("/health/ready") {
            // Since we ran migrations on boot, this is a basic readiness check.
            call.respondText("Ready", status = HttpStatusCode.OK)
        }

        post("/v1/invocations") {
            val payload = call.receive<InvocationPayload>()
            val executionId = UUID.randomUUID().toString()
            val effectiveTaskId = payload.issueId ?: payload.taskId ?: "unknown"

            val targetRepository = allowedRepositories.firstOrNull() ?: "unknown"
            val baseBranch = "main"

            // 1. Persist run initially
            val finalExecutionId = repository.insertIfAbsent(
                runId = executionId,
                paperclipRunId = payload.runId,
                paperclipTaskId = effectiveTaskId,
                repository = targetRepository,
                baseBranch = baseBranch,
                promptHash = "pending",
                state = "RECEIVED"
            )

            // If it's a new run, we need to process it. Otherwise just return existing executionId
            if (finalExecutionId == executionId) {
                // Async or background task to create session. For now we will do it inline or we can let a background worker do it.
                // Based on plan: "handle failures gracefully... while still returning a 202"
                // The webhook endpoint should return quickly. Let's do a fast best-effort inline, or we could defer entirely to worker.
                // Wait, architecture plan says:
                // Invocation API (Ktor route)
                // ├─ parse payload
                // ├─ idempotency check on runId
                // ├─ fetch full issue via Paperclip API
                // ├─ build Jules prompt
                // ├─ create Jules session
                // ├─ persist run row
                // └─ return 202 + executionId

                try {
                    val issue = paperclipClient.getIssue(effectiveTaskId)
                    if (issue == null) {
                        repository.updateState(finalExecutionId, "FAILED")
                    } else {
                        val prompt = promptBuilder.buildPrompt(issue, targetRepository)
                        val promptHash = promptBuilder.hashPrompt(prompt)

                        val sessionReq = JulesSessionRequest(
                            prompt = prompt,
                            title = "Issue: ${issue.title}",
                            sourceContext = SourceContext(
                                source = "sources/github/$targetRepository",
                                githubRepoContext = GithubRepoContext(startingBranch = baseBranch)
                            ),
                            requirePlanApproval = requirePlanApproval,
                            automationMode = automationMode
                        )

                        val session = julesClient.createSession(sessionReq)

                        repository.updateSession(
                            runId = finalExecutionId,
                            julesSessionId = session.id,
                            julesSessionUrl = "", // Set to empty or URL if available
                            julesState = session.state,
                            state = "SESSION_RUNNING",
                            prUrl = null
                        )

                        // Also update the prompt hash and set proper state
                        repository.updateRunStartDetails(finalExecutionId, promptHash, session.id, session.state) // We don't have access to connection directly here easily, but repository should handle it
                    }
                } catch (e: Exception) {
                    repository.updateState(finalExecutionId, "FAILED")
                }
            }

            call.respond(HttpStatusCode.Accepted, InvocationResponse("accepted", finalExecutionId))
        }
    }
}
