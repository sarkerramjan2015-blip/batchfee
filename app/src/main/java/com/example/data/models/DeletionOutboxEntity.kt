package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "deletion_outbox",
    primaryKeys = ["instituteId", "operationId"],
    indices = [
        Index(value = ["status"]),
        Index(value = ["instituteId", "entityType", "entityId", "action", "status"])
    ]
)
data class DeletionOutboxEntity(
    val operationId: String,
    val instituteId: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val reason: String,
    val requestJson: String,
    val status: String,
    val attempts: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastError: String?
)
