package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "financial_outbox",
    primaryKeys = ["instituteId", "operationId"],
    indices = [Index(value = ["instituteId", "status"])]
)
data class FinancialOutboxEntity(
    val operationId: String,
    val instituteId: String,
    val action: String,
    val requestJson: String,
    val status: String,
    val attempts: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastError: String?
)
