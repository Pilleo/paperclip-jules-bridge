package com.pilleo.bridge

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.sql.ResultSet

@Repository
class RunRepository(private val jdbcTemplate: JdbcTemplate) {

    private fun mapRow(rs: ResultSet, rowNum: Int): Run {
        return Run(
            id = rs.getString("id"),
            paperclipRunId = rs.getString("paperclip_run_id"),
            paperclipTaskId = rs.getString("paperclip_task_id"),
            paperclipAgentId = rs.getString("paperclip_agent_id"),
            paperclipCompanyId = rs.getString("paperclip_company_id"),
            julesSessionId = rs.getString("jules_session_id"),
            julesSessionUrl = rs.getString("jules_session_url"),
            repository = rs.getString("repository"),
            baseBranch = rs.getString("base_branch"),
            promptHash = rs.getString("prompt_hash"),
            state = rs.getString("state"),
            julesState = rs.getString("jules_state"),
            prUrl = rs.getString("pr_url"),
            prNumber = rs.getObject("pr_number") as? Int,
            errorCode = rs.getString("error_code"),
            errorMessage = rs.getString("error_message"),
            createdAt = Instant.parse(rs.getString("created_at")),
            updatedAt = Instant.parse(rs.getString("updated_at")),
            lastPolledAt = rs.getString("last_polled_at")?.let { Instant.parse(it) },
            nextPollAt = rs.getString("next_poll_at")?.let { Instant.parse(it) },
            pollAttempts = rs.getInt("poll_attempts"),
            callbackAttempts = rs.getInt("callback_attempts"),
            callbackDeliveredAt = rs.getString("callback_delivered_at")?.let { Instant.parse(it) },
            leaseOwner = rs.getString("lease_owner"),
            leaseExpiresAt = rs.getString("lease_expires_at")?.let { Instant.parse(it) }
        )
    }

    fun insertIfAbsent(
        runId: String,
        paperclipRunId: String,
        paperclipTaskId: String,
        repository: String,
        baseBranch: String,
        promptHash: String,
        state: String
    ): String {
        val now = Instant.now().toString()
        jdbcTemplate.update(
            """
            INSERT INTO runs (
                id, paperclip_run_id, paperclip_task_id, repository,
                base_branch, prompt_hash, state, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(paperclip_run_id) DO NOTHING
            """.trimIndent(),
            runId, paperclipRunId, paperclipTaskId, repository, baseBranch, promptHash, state, now, now
        )

        val existingIds = jdbcTemplate.queryForList(
            "SELECT id FROM runs WHERE paperclip_run_id = ?",
            String::class.java,
            paperclipRunId
        )

        return existingIds.first() ?: runId
    }

    fun findByPaperclipRunId(paperclipRunId: String): Run? {
        val runs = jdbcTemplate.query("SELECT * FROM runs WHERE paperclip_run_id = ?", this::mapRow, paperclipRunId)
        return runs.firstOrNull()
    }

    fun findAllActiveRuns(): List<Run> {
        val activeStates = listOf("RECEIVED", "CREATING_SESSION", "SESSION_RUNNING", "PR_CREATED", "CALLBACK_PENDING", "SESSION_COMPLETED")
        val placeholders = activeStates.joinToString(",") { "?" }
        val sql = "SELECT * FROM runs WHERE state IN ($placeholders) AND callback_delivered_at IS NULL"
        return jdbcTemplate.query(sql, this::mapRow, *activeStates.toTypedArray())
    }

    fun claimLease(runId: String, owner: String, expiresAt: Instant, now: Instant): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE runs
            SET lease_owner = ?, lease_expires_at = ?, updated_at = ?
            WHERE id = ? AND (lease_owner IS NULL OR lease_expires_at < ?)
            """.trimIndent(),
            owner, expiresAt.toString(), now.toString(), runId, now.toString()
        )
        return updated > 0
    }

    fun updateState(runId: String, newState: String) {
        jdbcTemplate.update(
            "UPDATE runs SET state = ?, updated_at = ? WHERE id = ?",
            newState, Instant.now().toString(), runId
        )
    }

    fun markCallbackDelivered(runId: String) {
        jdbcTemplate.update(
            "UPDATE runs SET callback_delivered_at = ?, updated_at = ? WHERE id = ?",
            Instant.now().toString(), Instant.now().toString(), runId
        )
    }

    fun updateSession(runId: String, julesSessionId: String, julesSessionUrl: String, julesState: String, state: String, prUrl: String?) {
        jdbcTemplate.update(
            """
            UPDATE runs
            SET jules_session_id = ?, jules_session_url = ?, jules_state = ?, state = ?, pr_url = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            julesSessionId, julesSessionUrl, julesState, state, prUrl, Instant.now().toString(), runId
        )
    }

    fun updateRunStartDetails(runId: String, promptHash: String, julesSessionId: String, julesState: String) {
        jdbcTemplate.update(
            """
            UPDATE runs
            SET prompt_hash = ?, jules_session_id = ?, jules_state = ?, state = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            promptHash, julesSessionId, julesState, "SESSION_RUNNING", Instant.now().toString(), runId
        )
    }
}
