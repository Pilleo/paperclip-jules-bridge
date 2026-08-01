package com.pilleo.bridge

import java.io.File
import java.security.MessageDigest

class PromptBuilder(
    private val allowedRepositories: List<String>,
    private val invariantsFilePath: String
) {
    fun buildPrompt(issue: PaperclipIssue, targetRepository: String): String {
        if (!allowedRepositories.contains(targetRepository)) {
            throw IllegalArgumentException("Repository $targetRepository is not in the allowed list.")
        }

        val invariantsContent = try {
            File(invariantsFilePath).readText().replace("\r\n", "\n").trim()
        } catch (e: Exception) {
            "No specific invariants provided."
        }

        return "Task ID: ${issue.id}\n" +
            "Title: ${issue.title}\n\n" +
            "Description:\n" +
            "${issue.description ?: "No description provided."}\n\n" +
            "Invariants:\n" +
            "$invariantsContent\n\n" +
            "Instructions:\n" +
            "Please address the issue described above. When completed, create a pull request.\n" +
            "Your commit message and PR description should explicitly reference the task ID: ${issue.id}."
    }

    fun hashPrompt(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(prompt.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
