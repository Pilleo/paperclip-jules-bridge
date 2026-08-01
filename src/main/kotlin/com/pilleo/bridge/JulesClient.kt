package com.pilleo.bridge

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow
import kotlin.random.Random

@Serializable
data class JulesSessionRequest(
    val prompt: String,
    val title: String,
    val sourceContext: SourceContext,
    val requirePlanApproval: Boolean = false,
    val automationMode: String = "AUTO_CREATE_PR"
)

@Serializable
data class SourceContext(
    val source: String,
    val githubRepoContext: GithubRepoContext
)

@Serializable
data class GithubRepoContext(
    val startingBranch: String
)

@Serializable
data class JulesSession(
    val id: String,
    val state: String,
    val outputs: List<JulesOutput> = emptyList()
)

@Serializable
data class JulesOutput(
    val pullRequest: JulesPullRequest? = null
)

@Serializable
data class JulesPullRequest(
    val url: String
)

class JulesClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
) {
    suspend fun createSession(request: JulesSessionRequest): JulesSession {
        return withRetry {
            val response = httpClient.post("$baseUrl/sessions") {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to create Jules session: ${response.status}")
            }
            response.body<JulesSession>()
        }
    }

    suspend fun getSession(sessionId: String): JulesSession {
        return withRetry {
            val response = httpClient.get("$baseUrl/sessions/$sessionId") {
                header("x-goog-api-key", apiKey)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to fetch Jules session: ${response.status}")
            }
            response.body<JulesSession>()
        }
    }

    suspend fun validateAuth() {
        val response = httpClient.get("$baseUrl/sessions?pageSize=1") {
            header("x-goog-api-key", apiKey)
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw IllegalStateException("Jules API Key is invalid or unauthorized: ${response.status}")
        }
    }

    private suspend fun <T> withRetry(maxRetries: Int = 3, block: suspend () -> T): T {
        var currentAttempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (currentAttempt >= maxRetries) {
                    throw e
                }
                currentAttempt++
                val delayMs = (2.0.pow(currentAttempt) * 1000).toLong() + Random.nextLong(0, 1000)
                delay(delayMs)
            }
        }
    }
}
