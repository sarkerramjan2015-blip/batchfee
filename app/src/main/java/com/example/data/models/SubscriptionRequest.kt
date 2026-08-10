package com.batchfee.edu.data.models

data class SubscriptionRequest(
    val requestId: String,
    val instituteId: String,
    val instituteName: String,
    val ownerName: String,
    val institutePhone: String?,
    val requestedPlanId: String,
    val durationMonths: Int,
    val amountPaid: Double,
    val transactionLast4: String,
    val paymentMethod: String,
    val status: String = "pending",
    val requestSentAt: Long,
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val reviewerNote: String? = null
) {
    companion object {
        fun fromFirestore(id: String, data: Map<String, Any?>): SubscriptionRequest = SubscriptionRequest(
            requestId = id,
            instituteId = data["instituteId"] as? String ?: "",
            instituteName = data["instituteName"] as? String ?: "",
            ownerName = data["ownerName"] as? String ?: "",
            institutePhone = data["institutePhone"] as? String,
            requestedPlanId = data["requestedPlanId"] as? String ?: "",
            durationMonths = (data["durationMonths"] as? Number)?.toInt() ?: 1,
            amountPaid = (data["amountPaid"] as? Number)?.toDouble() ?: 0.0,
            transactionLast4 = data["transactionLast4"] as? String ?: "",
            paymentMethod = data["paymentMethod"] as? String ?: "",
            status = data["status"] as? String ?: "pending",
            requestSentAt = (data["requestSentAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            reviewedBy = data["reviewedBy"] as? String,
            reviewedAt = (data["reviewedAt"] as? Number)?.toLong(),
            reviewerNote = data["reviewerNote"] as? String
        )
    }

    fun toFirestore(): Map<String, Any> = mapOf(
        "instituteId" to instituteId,
        "instituteName" to instituteName,
        "ownerName" to ownerName,
        "institutePhone" to (institutePhone ?: ""),
        "requestedPlanId" to requestedPlanId,
        "durationMonths" to durationMonths,
        "amountPaid" to amountPaid,
        "transactionLast4" to transactionLast4,
        "paymentMethod" to paymentMethod,
        "status" to status,
        "requestSentAt" to requestSentAt,
        "reviewedBy" to (reviewedBy ?: ""),
        "reviewedAt" to (reviewedAt ?: 0L),
        "reviewerNote" to (reviewerNote ?: "")
    )
}

