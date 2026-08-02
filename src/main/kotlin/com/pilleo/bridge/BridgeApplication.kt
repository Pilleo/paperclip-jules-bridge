package com.pilleo.bridge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

@SpringBootApplication
class BridgeApplication

fun main(args: Array<String>) {
    // Load local ENV
    listOf(File(".env"), File(".ENV")).forEach { envFile ->
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val split = trimmed.split("=", limit = 2)
                    if (split.size == 2 && System.getProperty(split[0].trim()) == null) {
                        System.setProperty(split[0].trim(), split[1].trim().removeSurrounding("\"").removeSurrounding("'"))
                    }
                }
            }
        }
    }

    runApplication<BridgeApplication>(*args)
}

@Component
class StartupValidator(private val julesClient: JulesClient) {
    private val logger = LoggerFactory.getLogger(StartupValidator::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun validateOnStartup() {
        try {
            // Using a virtual thread to validate without blocking the main event stream directly but waiting
            val thread = Thread.ofVirtual().start {
                try {
                    julesClient.validateAuth()
                } catch (e: Exception) {
                    logger.error("CRITICAL Startup Failed: Jules API Authentication is invalid.", e)
                }
            }
            thread.join()
        } catch (e: Exception) {
            logger.error("Startup validation error.", e)
        }
    }
}
