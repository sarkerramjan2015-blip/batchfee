package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fees")
data class FeeEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val studentId: String,
    val batchId: String?,
    val feePeriod: String,
    val feeType: String,
    val dueDateMs: Long,
    val baseAmount: Double,
    val discountAmount: Double,
    val lateFeeAmount: Double,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double,
    val status: String,
    val note: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val cancelledAtMs: Long?
)
