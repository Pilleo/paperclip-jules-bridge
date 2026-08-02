package com.pilleo.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.client.RestClient
import org.springframework.web.client.HttpClientErrorException
import org.springframework.http.MediaType
import kotlinx.coroutines.delay
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
    val project: JsonElement? = null
)

@Serializable
data class IssueUpdateRequest(
    val status: String,
    val comment: String? = null
)

@Serializable
data class CallbackRequest(
    val status: String,
    val result: String,
    val errorMessage: String? = null,
    val usage: UsageData? = null,
    val costUsd: Double? = null,
    val model: String? = null,
    val provider: String? = null
)

@Serializable
data class UsageData(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int
)

@Component
class PaperclipClient(
    @Value("\${paperclip.baseUrl}") private val baseUrl: String,
    @Value("\${bridge.authToken}") private val bridgeToken: String
) {
    private val restClient = RestClient.create(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getIssue(issueId: String): PaperclipIssue? {
        return withRetry {
            try {
                val responseString = restClient.get()
                    .uri("/api/issues/{issueId}", issueId)
                    .header("Authorization", "Bearer ${bridgeToken}")
                    .retrieve()
                    .body(String::class.java)
                responseString?.let { json.decodeFromString<PaperclipIssue>(it) }
            } catch (e: HttpClientErrorException.NotFound) {
                null
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun updateIssueStatus(issueId: String, status: String, comment: String? = null): Boolean {
        return withRetry {
            try {
                val req = IssueUpdateRequest(status, comment)
                val bodyStr = json.encodeToString(req)
                restClient.patch()
                    .uri("/api/issues/{issueId}", issueId)
                    .header("Authorization", "Bearer ${bridgeToken}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyStr)
                    .retrieve()
                    .toBodilessEntity()
                true
            } catch (e: HttpClientErrorException.UnprocessableEntity) {
                false
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun sendCallback(runId: String, request: CallbackRequest): Boolean {
        return withRetry {
            try {
                val bodyStr = json.encodeToString(request)
                restClient.post()
                    .uri("/api/heartbeat-runs/{runId}/callback", runId)
                    .header("Authorization", "Bearer ${bridgeToken}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyStr)
                    .retrieve()
                    .toBodilessEntity()
                true
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private suspend fun <T> withRetry(maxRetries: Int = 3, block: () -> T): T {
        var currentAttempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HttpClientErrorException.NotFound) {
                throw e
            } catch (e: HttpClientErrorException.UnprocessableEntity) {
                throw e
            } catch (e: Exception) {
                if (currentAttempt >= maxRetries) {
                    throw e
                }
                currentAttempt++
                val delayMs = (2.0.pow(currentAttempt) * 100).toLong() + Random.nextLong(0, 100)
                delay(delayMs)
            }
        }
    }
}
