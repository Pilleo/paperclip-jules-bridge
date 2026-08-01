package com.pilleo.bridge

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptBuilderTest {

    @Test
    fun `test build prompt successfully`() {
        val invariantsFile = File("src/test/resources/invariants.md")
        val builder = PromptBuilder(
            allowedRepositories = listOf("org/allowed-repo"),
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

        val invariantsContent = "Test invariants"
        val fallback = try { File(invariantsFile.absolutePath).readText().replace("\r\n", "\n").trim() } catch(e: Exception) { "" }

        val expected = "Task ID: issue_123\n" +
            "Title: Fix login bug\n\n" +
            "Description:\n" +
            "Users cannot login when using Safari.\n\n" +
            "Invariants:\n" +
            "$fallback\n\n" +
            "Instructions:\n" +
            "Please address the issue described above. When completed, create a pull request.\n" +
            "Your commit message and PR description should explicitly reference the task ID: issue_123."

        assertEquals(expected.trim(), result.replace("\r\n", "\n").trim())
    }

    @Test
    fun `test build prompt rejects unauthorized repository`() {
        val invariantsFile = File("src/test/resources/invariants.md")
        val builder = PromptBuilder(
            allowedRepositories = listOf("org/allowed-repo"),
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
            allowedRepositories = listOf("org/allowed-repo"),
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
