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
import kotlinx.serialization.json.JsonElement
import kotlin.math.pow
import kotlin.random.Random

@Serializable
data class PaperclipIssue(
    val id: String,
    val title: String,
    val description: String?,
    val status: String,
    val priority: String?,
    val parentId: String?,
    val project: JsonElement?
)

@Serializable
data class IssuePatchRequest(
    val status: String,
    val comment: String
)

@Serializable
data class CallbackRequest(
    val status: String,
    val result: String,
    val errorMessage: String?,
    val usage: UsageMetrics? = null,
    val costUsd: Double? = null,
    val model: String? = null,
    val provider: String? = null
)

@Serializable
data class UsageMetrics(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int
)

class PaperclipClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
) {
    suspend fun getIssue(issueId: String): PaperclipIssue? {
        return withRetry {
            val response = httpClient.get("$baseUrl/api/issues/$issueId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (response.status == HttpStatusCode.NotFound) {
                return@withRetry null
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to fetch issue: ${response.status}")
            }
            response.body<PaperclipIssue>()
        }
    }

    suspend fun updateIssueStatus(issueId: String, status: String, comment: String): Boolean {
        return withRetry {
            val response = httpClient.patch("$baseUrl/api/issues/$issueId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(IssuePatchRequest(status, comment))
            }
            if (response.status == HttpStatusCode.UnprocessableEntity) {
                // Return false to indicate reconciliation required
                return@withRetry false
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to patch issue: ${response.status}")
            }
            true
        }
    }

    suspend fun sendCallback(runId: String, request: CallbackRequest): Boolean {
        return withRetry {
            val response = httpClient.post("$baseUrl/api/heartbeat-runs/$runId/callback") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to send callback: ${response.status}")
            }
            true
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
