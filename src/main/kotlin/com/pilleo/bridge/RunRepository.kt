package com.pilleo.bridge

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class Run(
    val id: String,
    val paperclipRunId: String,
    val paperclipTaskId: String,
    val paperclipAgentId: String?,
    val paperclipCompanyId: String?,
    val julesSessionId: String?,
    val julesSessionUrl: String?,
    val repository: String,
    val baseBranch: String,
    val promptHash: String,
    val state: String,
    val julesState: String?,
    val prUrl: String?,
    val prNumber: Int?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastPolledAt: Instant?,
    val nextPollAt: Instant?,
    val pollAttempts: Int,
    val callbackAttempts: Int,
    val callbackDeliveredAt: Instant?,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?
)

class RunRepository(private val jdbcUrl: String) {

    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection(jdbcUrl).use(block)
    }

    private fun mapRow(rs: java.sql.ResultSet): Run {
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
        return withConnection { conn ->
            conn.prepareStatement("SELECT id FROM runs WHERE paperclip_run_id = ?").use { stmt ->
                stmt.setString(1, paperclipRunId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return@withConnection rs.getString("id")
                }
            }

            val now = Instant.now().toString()
            conn.prepareStatement(
                """
                INSERT INTO runs (
                    id, paperclip_run_id, paperclip_task_id, repository,
                    base_branch, prompt_hash, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(paperclip_run_id) DO UPDATE SET updated_at = excluded.updated_at
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, runId)
                stmt.setString(2, paperclipRunId)
                stmt.setString(3, paperclipTaskId)
                stmt.setString(4, repository)
                stmt.setString(5, baseBranch)
                stmt.setString(6, promptHash)
                stmt.setString(7, state)
                stmt.setString(8, now)
                stmt.setString(9, now)

                val rs = stmt.executeQuery()
                rs.next()
                rs.getString("id")
            }
        }
    }

    fun findByPaperclipRunId(paperclipRunId: String): Run? {
        return withConnection { conn ->
            conn.prepareStatement("SELECT * FROM runs WHERE paperclip_run_id = ?").use { stmt ->
                stmt.setString(1, paperclipRunId)
                val rs = stmt.executeQuery()
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun findAllActiveRuns(): List<Run> {
        val activeStates = listOf("RECEIVED", "CREATING_SESSION", "SESSION_RUNNING", "PR_CREATED", "CALLBACK_PENDING", "SESSION_COMPLETED")
        val placeholders = activeStates.joinToString(",") { "?" }
        return withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM runs WHERE state IN ($placeholders) AND callback_delivered_at IS NULL"
            ).use { stmt ->
                activeStates.forEachIndexed { index, state -> stmt.setString(index + 1, state) }
                val rs = stmt.executeQuery()
                val runs = mutableListOf<Run>()
                while (rs.next()) {
                    runs.add(mapRow(rs))
                }
                runs
            }
        }
    }

    fun claimLease(runId: String, owner: String, expiresAt: Instant, now: Instant): Boolean {
        return withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE runs
                SET lease_owner = ?, lease_expires_at = ?, updated_at = ?
                WHERE id = ? AND (lease_owner IS NULL OR lease_expires_at < ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, owner)
                stmt.setString(2, expiresAt.toString())
                stmt.setString(3, now.toString())
                stmt.setString(4, runId)
                stmt.setString(5, now.toString())

                stmt.executeUpdate() > 0
            }
        }
    }

    fun updateState(runId: String, newState: String) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE runs SET state = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, newState)
                stmt.setString(2, Instant.now().toString())
                stmt.setString(3, runId)
                stmt.executeUpdate()
            }
        }
    }

    fun markCallbackDelivered(runId: String) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE runs SET callback_delivered_at = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, Instant.now().toString())
                stmt.setString(2, Instant.now().toString())
                stmt.setString(3, runId)
                stmt.executeUpdate()
            }
        }
    }

    fun updateSession(runId: String, julesSessionId: String, julesSessionUrl: String, julesState: String, state: String, prUrl: String?) {
        withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE runs
                SET jules_session_id = ?, jules_session_url = ?, jules_state = ?, state = ?, pr_url = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, julesSessionId)
                stmt.setString(2, julesSessionUrl)
                stmt.setString(3, julesState)
                stmt.setString(4, state)
                stmt.setString(5, prUrl)
                stmt.setString(6, Instant.now().toString())
                stmt.setString(7, runId)
                stmt.executeUpdate()
            }
        }
    }

    fun updateRunStartDetails(runId: String, promptHash: String, julesSessionId: String, julesState: String) {
        withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE runs
                SET prompt_hash = ?, jules_session_id = ?, jules_state = ?, state = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, promptHash)
                stmt.setString(2, julesSessionId)
                stmt.setString(3, julesState)
                stmt.setString(4, "SESSION_RUNNING")
                stmt.setString(5, Instant.now().toString())
                stmt.setString(6, runId)
                stmt.executeUpdate()
            }
        }
    }
}
