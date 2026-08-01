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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

fun main(args: Array<String>) {
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

    // Validate Jules Authentication BEFORE starting the Ktor Netty engine completely.
    // This avoids blocking the Netty EventLoop and safely halts the JVM explicitly if invalid.
    val logger = LoggerFactory.getLogger("ApplicationMain")
    val julesUrl = System.getProperty("JULES_API_KEY") ?: System.getenv("JULES_API_KEY") ?: "default-jules-key"
    val julesBase = System.getProperty("JULES_API_BASE_URL") ?: System.getenv("JULES_API_BASE_URL") ?: "https://jules.googleapis.com/v1alpha"

    val dbProp = System.getProperty("DATABASE_URL") ?: System.getenv("DATABASE_URL")
    if (dbProp == null || !dbProp.contains("memory:")) {
        try {
            runBlocking {
                val testClient = JulesClient(julesBase, julesUrl)
                testClient.validateAuth()
            }
        } catch (e: Exception) {
            logger.error("CRITICAL Startup Failed: Jules API Authentication is invalid. Halting JVM completely.", e)
            Runtime.getRuntime().halt(1)
        }
    }

    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val config = environment.config

    val dbUrl = config.propertyOrNull("database.url")?.getString() ?: "jdbc:sqlite:runs.sqlite"

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
            call.respondText("Ready", status = HttpStatusCode.OK)
        }

        post("/v1/invocations") {
            val payload = call.receive<InvocationPayload>()
            val executionId = UUID.randomUUID().toString()
            val effectiveTaskId = payload.issueId ?: payload.taskId ?: "unknown"

            val targetRepository = allowedRepositories.firstOrNull() ?: "unknown"
            val baseBranch = "main"

            val finalExecutionId = repository.insertIfAbsent(
                runId = executionId,
                paperclipRunId = payload.runId,
                paperclipTaskId = effectiveTaskId,
                repository = targetRepository,
                baseBranch = baseBranch,
                promptHash = "pending",
                state = "RECEIVED"
            )

            if (finalExecutionId == executionId) {
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
                            julesSessionUrl = "",
                            julesState = session.state,
                            state = "SESSION_RUNNING",
                            prUrl = null
                        )

                        repository.updateRunStartDetails(finalExecutionId, promptHash, session.id, session.state)
                    }
                } catch (e: Exception) {
                    repository.updateState(finalExecutionId, "FAILED")
                }
            }

            call.respond(HttpStatusCode.Accepted, InvocationResponse("accepted", finalExecutionId))
        }
    }
}
