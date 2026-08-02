package com.pilleo.bridge

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptBuilderTest {

    @Test
    fun `test build prompt successfully`() {
        val invariantsFile = File("src/test/resources/invariants.md")
        val builder = PromptBuilder(
            allowedRepositoriesStr = "org/allowed-repo",
            invariantsFilePath = invariantsFile.absolutePath
        )

        val issue = PaperclipIssue(
            id = "issue_123",
            title = "Fix login bug",
            description = "Users cannot login when using Safari.",
            status = "todo",
            priority = "high",
            parentId = null,
            project = null
        )

        val result = builder.buildPrompt(issue, "org/allowed-repo")

        assertTrue(result.contains("Task ID: issue_123"), "Prompt did not contain task ID: \$result")
        assertTrue(result.contains("Users cannot login when using Safari."), "Prompt did not contain description: \$result")
    }

    @Test
    fun `test build prompt rejects unauthorized repository`() {
        val invariantsFile = File("src/test/resources/invariants.md")
        val builder = PromptBuilder(
            allowedRepositoriesStr = "org/allowed-repo",
            invariantsFilePath = invariantsFile.absolutePath
        )

        val issue = PaperclipIssue(
            id = "issue_123",
            title = "Test",
            description = null,
            status = "todo",
            priority = null,
            parentId = null,
            project = null
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            builder.buildPrompt(issue, "org/unauthorized-repo")
        }

        assertEquals("Repository org/unauthorized-repo is not in the allowed list.", exception.message)
    }

    @Test
    fun `test compute prompt hash`() {
        val invariantsFile = File("src/test/resources/invariants.md")
        val builder = PromptBuilder(
            allowedRepositoriesStr = "org/allowed-repo",
            invariantsFilePath = invariantsFile.absolutePath
        )
        val issue = PaperclipIssue(
            id = "issue_123",
            title = "Test",
            description = null,
            status = "todo",
            priority = null,
            parentId = null,
            project = null
        )

        val prompt = builder.buildPrompt(issue, "org/allowed-repo")
        val hash = builder.hashPrompt(prompt)

        // SHA-256 hash length in hex
        assertEquals(64, hash.length)
    }
}
