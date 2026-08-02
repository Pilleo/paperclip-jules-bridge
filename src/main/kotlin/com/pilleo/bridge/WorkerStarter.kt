package com.pilleo.bridge

import org.springframework.stereotype.Component
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value

@Component
class WorkerStarter(
    private val runRepository: RunRepository,
    private val julesClient: JulesClient,
    private val paperclipClient: PaperclipClient,
    @Value("\${polling.intervalSeconds:45}") private val pollIntervalSeconds: Long,
    @Value("\${polling.maxSessionAgeHours:12}") private val maxSessionAgeHours: Long
) {
    private val logger = LoggerFactory.getLogger(WorkerStarter::class.java)
    private var worker: PollingWorker? = null

    @EventListener(ApplicationReadyEvent::class)
    fun startWorker() {
        logger.info("Starting polling worker...")
        worker = PollingWorker(
            repository = runRepository,
            julesClient = julesClient,
            paperclipClient = paperclipClient,
            pollIntervalSeconds = pollIntervalSeconds,
            maxSessionAgeHours = maxSessionAgeHours
        )
        worker?.start()
    }
}
