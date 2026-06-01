package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val paymentId: String,
    val feeId: String,
    val studentId: String,
    val receiptNumber: String,
    val receiptDateMs: Long,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double,
    val paymentMethod: String,
    val receiptText: String?,
    val createdAtMs: Long
)
