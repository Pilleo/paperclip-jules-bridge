package com.pilleo.bridge

import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import java.io.File
import java.security.MessageDigest

@Component
class PromptBuilder(
    @Value("\${bridge.allowedRepositories:}") private val allowedRepositoriesStr: String,
    @Value("\${bridge.invariantsFile:}") private val invariantsFilePath: String
) {

    private val allowedRepositories = allowedRepositoriesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun buildPrompt(issue: PaperclipIssue, targetRepository: String): String {
        if (allowedRepositories.isNotEmpty() && !allowedRepositories.contains(targetRepository)) {
            throw IllegalArgumentException("Repository " + targetRepository + " is not in the allowed list.")
        }

        val invariantsContent = try {
            if (invariantsFilePath.isNotBlank()) File(invariantsFilePath).readText().replace("\r\n", "\n").trim() else "No specific invariants provided."
        } catch (e: Exception) {
            "No specific invariants provided."
        }

        val descriptionText = issue.description ?: "No description provided."

        return "Task ID: " + issue.id + "\n" +
            "Title: " + issue.title + "\n\n" +
            "Description:\n" +
            descriptionText + "\n\n" +
            "Invariants:\n" +
            invariantsContent + "\n\n" +
            "Instructions:\n" +
            "Please address the issue described above. When completed, create a pull request.\n" +
            "Your commit message and PR description should explicitly reference the task ID: " + issue.id + "."
    }

    fun hashPrompt(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(prompt.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
