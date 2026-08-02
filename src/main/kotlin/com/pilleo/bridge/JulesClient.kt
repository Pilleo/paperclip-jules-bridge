package com.pilleo.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Component
class JulesClient(
    @Value("\${jules.apiBaseUrl}") private val baseUrl: String,
    @Value("\${jules.apiKey}") private val apiKey: String
) {
    private val restClient = RestClient.create(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createSession(request: JulesSessionRequest): JulesSession {
        return withRetry {
            val bodyString = json.encodeToString(request)
            val responseString = restClient.post()
                .uri("/sessions")
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(bodyString)
                .retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("Failed to parse response body")
            json.decodeFromString<JulesSession>(responseString)
        }
    }

    suspend fun getSession(sessionId: String): JulesSession {
        return withRetry {
            val responseString = restClient.get()
                .uri("/sessions/{sessionId}", sessionId)
                .header("x-goog-api-key", apiKey)
                .retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("Failed to parse response body")
            json.decodeFromString<JulesSession>(responseString)
        }
    }

    fun validateAuth() {
        try {
            restClient.get()
                .uri("/sessions?pageSize=1")
                .header("x-goog-api-key", apiKey)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException) {
            if (e.statusCode.value() == 401 || e.statusCode.value() == 403) {
                throw IllegalStateException("Jules API Key is invalid or unauthorized: ${e.statusCode}")
            }
            throw IllegalStateException("Jules API Auth validation failed with unexpected status: ${e.statusCode}", e)
        } catch (e: Exception) {
            throw IllegalStateException("Network failure reaching Jules API for validation", e)
        }
    }

    private suspend fun <T> withRetry(maxRetries: Int = 3, block: () -> T): T {
        var currentAttempt = 0
        while (true) {
            try {
                return block()
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
