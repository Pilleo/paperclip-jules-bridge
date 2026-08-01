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
import java.util.UUID

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    configureSerialization()
    // configureSecurity()
    // configureRouting(repo)
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
    }

    // Simple custom interceptor for Bearer token validation
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

fun Application.configureRouting(repository: RunRepository) {
    routing {
        get("/health/live") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        get("/health/ready") {
            call.respondText("Ready", status = HttpStatusCode.OK)
        }

        post("/v1/invocations") {
            val payload = call.receive<InvocationPayload>()

            // Generate a unique execution ID for this run attempt
            val executionId = UUID.randomUUID().toString()

            // Prefer issueId, fallback to taskId
            val effectiveTaskId = payload.issueId ?: payload.taskId ?: "unknown"

            val finalExecutionId = repository.insertIfAbsent(
                runId = executionId,
                paperclipRunId = payload.runId,
                paperclipTaskId = effectiveTaskId,
                repository = "unknown", // Will be filled later by prompt builder/config
                baseBranch = "main", // Default
                promptHash = "pending",
                state = "RECEIVED"
            )

            call.respond(HttpStatusCode.Accepted, InvocationResponse("accepted", finalExecutionId))
        }
    }
}
