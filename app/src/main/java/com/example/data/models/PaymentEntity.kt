package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payments",
    indices = [
        Index(value = ["instituteId", "operationId"], unique = true),
        Index(value = ["instituteId", "paymentDateMs"]),
        Index(value = ["instituteId", "feeId", "paymentDateMs"])
    ]
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val feeId: String,
    val studentId: String,
    val amount: Double,
    val paymentMethod: String,
    val transactionId: String?,
    val receiptNumber: String,
    val paymentDateMs: Long,
    val collectedByUserId: String,
    val status: String,
    val note: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val operationId: String? = null,
    val ledgerVersion: Int = 0
)

