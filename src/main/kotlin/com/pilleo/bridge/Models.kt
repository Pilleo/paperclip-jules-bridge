package com.pilleo.bridge

import kotlinx.serialization.Serializable
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
