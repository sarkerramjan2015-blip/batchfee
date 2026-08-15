package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    indices = [Index(value = ["instituteId", "archivedAtMs", "expenseDateMs"])]
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val category: String,
    val title: String,
    val amount: Double,
    val expenseDateMs: Long,
    val paymentMethod: String?,
    val description: String?,
    val attachmentUri: String?,
    val createdByUserId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)

