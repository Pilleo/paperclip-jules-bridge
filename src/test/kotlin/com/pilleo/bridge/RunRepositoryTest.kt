package com.pilleo.bridge

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RunRepositoryTest {

    private lateinit var dbFile: File
    private lateinit var repository: RunRepository

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-db-", ".sqlite")
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        Flyway.configure()
            .dataSource(jdbcUrl, "", "")
            .load()
            .migrate()

        repository = RunRepository(jdbcUrl)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    @Test
    fun `test insert and fetch run`() {
        val runId = "test-run-123"
        val paperclipRunId = "pc-run-456"
        val paperclipTaskId = "pc-task-789"
        val repositoryName = "org/repo"
        val baseBranch = "main"
        val promptHash = "hash123"
        val state = "RECEIVED"

        val insertedId = repository.insertIfAbsent(
            runId = runId,
            paperclipRunId = paperclipRunId,
            paperclipTaskId = paperclipTaskId,
            repository = repositoryName,
            baseBranch = baseBranch,
            promptHash = promptHash,
            state = state
        )

        assertEquals(runId, insertedId)

        val run = repository.findByPaperclipRunId(paperclipRunId)
        assertNotNull(run)
        assertEquals(runId, run.id)
        assertEquals(paperclipRunId, run.paperclipRunId)
        assertEquals(paperclipTaskId, run.paperclipTaskId)
        assertEquals(repositoryName, run.repository)
        assertEquals(baseBranch, run.baseBranch)
        assertEquals(promptHash, run.promptHash)
        assertEquals(state, run.state)
    }

    @Test
    fun `test insertIfAbsent is idempotent`() {
        val runId1 = "test-run-1"
        val runId2 = "test-run-2"
        val paperclipRunId = "pc-run-456"

        val firstInsertId = repository.insertIfAbsent(
            runId = runId1,
            paperclipRunId = paperclipRunId,
            paperclipTaskId = "task-1",
            repository = "org/repo",
            baseBranch = "main",
            promptHash = "hash1",
            state = "RECEIVED"
        )

        assertEquals(runId1, firstInsertId)

        val secondInsertId = repository.insertIfAbsent(
            runId = runId2,
            paperclipRunId = paperclipRunId,
            paperclipTaskId = "task-2",
            repository = "org/repo2",
            baseBranch = "develop",
            promptHash = "hash2",
            state = "RECEIVED"
        )

        assertEquals(runId1, secondInsertId) // Should return the existing ID

        val run = repository.findByPaperclipRunId(paperclipRunId)
        assertEquals("task-1", run?.paperclipTaskId) // Original data kept
    }

    @Test
    fun `test lease claiming atomicity`() {
        val runId = "test-run-1"
        repository.insertIfAbsent(runId, "pc-run-1", "task-1", "org/repo", "main", "hash", "RECEIVED")

        val now = Instant.now()
        val owner1 = "worker-1"
        val expiresAt1 = now.plusSeconds(30)

        // Initial claim should succeed
        assertTrue(repository.claimLease(runId, owner1, expiresAt1, now))

        val runAfterClaim = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals(owner1, runAfterClaim.leaseOwner)

        // Claim by another worker before expiry should fail
        val owner2 = "worker-2"
        val expiresAt2 = now.plusSeconds(60)
        assertFalse(repository.claimLease(runId, owner2, expiresAt2, now))

        val runAfterFailedClaim = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals(owner1, runAfterFailedClaim.leaseOwner) // Still owner1

        // Claim by another worker after expiry should succeed
        val later = now.plusSeconds(31)
        assertTrue(repository.claimLease(runId, owner2, expiresAt2, later))

        val runAfterExpiredClaim = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals(owner2, runAfterExpiredClaim.leaseOwner)
    }

    @Test
    fun `test update session details`() {
        val runId = "test-run-1"
        repository.insertIfAbsent(runId, "pc-run-1", "task-1", "org/repo", "main", "hash", "RECEIVED")

        repository.updateSession(runId, "jules-123", "http://jules/123", "IN_PROGRESS", "SESSION_RUNNING", "https://pr.url")

        val run = repository.findByPaperclipRunId("pc-run-1")!!
        assertEquals("jules-123", run.julesSessionId)
        assertEquals("http://jules/123", run.julesSessionUrl)
        assertEquals("IN_PROGRESS", run.julesState)
        assertEquals("SESSION_RUNNING", run.state)
        assertEquals("https://pr.url", run.prUrl)
    }
}
