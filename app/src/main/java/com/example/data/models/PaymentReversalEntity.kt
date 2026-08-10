package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_reversals",
    indices = [
        Index(value = ["instituteId", "paymentId"], unique = true),
        Index(value = ["instituteId", "feeId"])
    ]
)
data class PaymentReversalEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val paymentId: String,
    val feeId: String,
    val studentId: String,
    val amount: Double,
    val receiptNumber: String,
    val reason: String,
    val reversedByUserId: String,
    val reversedAtMs: Long,
    val operationId: String,
    val ledgerVersion: Int = 1
)
