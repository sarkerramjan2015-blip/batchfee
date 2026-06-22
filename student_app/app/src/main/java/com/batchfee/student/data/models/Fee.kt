package com.batchfee.student.data.models

data class Fee(
    val id: String = "",
    val instituteId: String = "",
    val studentId: String = "",
    val batchId: String? = null,
    val feePeriod: String = "",
    val feeType: String = "",
    val dueDateMs: Long = 0,
    val baseAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val lateFeeAmount: Double = 0.0,
    val totalAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val dueAmount: Double = 0.0,
    val status: String = "",
    val note: String? = null,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0
)

data class Payment(
    val id: String = "",
    val instituteId: String = "",
    val feeId: String = "",
    val studentId: String = "",
    val amount: Double = 0.0,
    val paymentMethod: String = "",
    val transactionId: String? = null,
    val receiptNumber: String = "",
    val paymentDateMs: Long = 0,
    val status: String = "completed",
    val note: String? = null
)

data class Receipt(
    val id: String = "",
    val instituteId: String = "",
    val paymentId: String = "",
    val feeId: String = "",
    val studentId: String = "",
    val receiptNumber: String = "",
    val receiptDateMs: Long = 0,
    val totalAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val dueAmount: Double = 0.0,
    val paymentMethod: String = "",
    val receiptText: String? = null,
    val createdAtMs: Long = 0
)
