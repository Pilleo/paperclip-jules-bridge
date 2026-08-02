package com.pilleo.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PollingWorker(
    private val repository: RunRepository,
    private val julesClient: JulesClient,
    private val paperclipClient: PaperclipClient,
    private val workerId: String = UUID.randomUUID().toString(),
    private val pollIntervalSeconds: Long = 45,
    private val leaseDurationSeconds: Long = 60,
    private val maxSessionAgeHours: Long = 12
) {
    private val logger = LoggerFactory.getLogger(PollingWorker::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        job = scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (e: Exception) {
                    logger.error("Error in polling loop", e)
                }
                delay(pollIntervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    suspend fun poll() {
        val now = Instant.now()
        // We will fetch ALL active runs from DB, attempt to claim lease, and process them.
        val activeRuns = repository.findAllActiveRuns()

        for (run in activeRuns) {
            val isLeased = repository.claimLease(run.id, workerId, now.plusSeconds(leaseDurationSeconds), now)
            if (isLeased) {
                try {
                    processRun(run)
                } catch (e: Exception) {
                    logger.error("Failed to process run \${run.id}", e)
                }
            }
        }
    }

    private suspend fun processRun(run: Run) {
        val now = Instant.now()

        // Handle Timeout check
        if (run.createdAt.plus(maxSessionAgeHours, ChronoUnit.HOURS).isBefore(now)) {
            handleTimeout(run)
            return
        }

        if (run.state == "RECEIVED" || run.state == "CREATING_SESSION") {
            // Need to implement prompt generation and session creation here...
            // Skipped for pure polling domain for now, assumes session created elsewhere or via API worker.
            return
        }

        if (run.julesSessionId == null) {
            return
        }

        if (run.state == "SESSION_RUNNING" || run.julesState == "PAUSED") {
            val julesSession = julesClient.getSession(run.julesSessionId)

            repository.updateSession(
                runId = run.id,
                julesSessionId = run.julesSessionId,
                julesSessionUrl = run.julesSessionUrl ?: "",
                julesState = julesSession.state,
                state = when (julesSession.state) {
                    "COMPLETED", "FAILED" -> "SESSION_COMPLETED"
                    else -> "SESSION_RUNNING" // QUEUED, PLANNING, IN_PROGRESS, PAUSED
                },
                prUrl = julesSession.outputs.firstOrNull()?.pullRequest?.url
            )

            val updatedRun = repository.findByPaperclipRunId(run.paperclipRunId) ?: return

            if (updatedRun.state == "SESSION_COMPLETED") {
                deliverCallback(updatedRun)
            }
        } else if (run.state == "SESSION_COMPLETED" && run.callbackDeliveredAt == null) {
            // Retry delivering callback
            deliverCallback(run)
        }
    }

    private suspend fun handleTimeout(run: Run) {
        repository.updateState(run.id, "TIMED_OUT")
        if (run.callbackDeliveredAt == null) {
            val callbackReq = CallbackRequest(
                status = "failed",
                result = "Bridge timeout after \${maxSessionAgeHours} hours.",
                errorMessage = "Bridge timeout"
            )
            val success = paperclipClient.sendCallback(run.paperclipRunId, callbackReq)
            if (success) {
                repository.markCallbackDelivered(run.id)
            }
        }
    }

    private suspend fun deliverCallback(run: Run) {
        if (run.callbackDeliveredAt != null) return

        val isSuccess = run.julesState == "COMPLETED"
        val status = if (isSuccess) "succeeded" else "failed"

        val callbackReq = CallbackRequest(
            status = status,
            result = run.prUrl?.let { "PR Created: \${it}" } ?: "Session completed without PR.",
            errorMessage = if (!isSuccess) "Jules session failed" else null
        )

        // Transition Paperclip Issue to in_review if it was successful and we have a PR
        if (isSuccess && run.prUrl != null) {
            try {
                paperclipClient.updateIssueStatus(
                    issueId = run.paperclipTaskId,
                    status = "in_review",
                    comment = "Jules created a PR: \${run.prUrl}"
                )
            } catch (e: Exception) {
                logger.warn("Failed to update issue status to in_review", e)
            }
        }

        val success = paperclipClient.sendCallback(run.paperclipRunId, callbackReq)
        if (success) {
            repository.markCallbackDelivered(run.id)
        }
    }
}
