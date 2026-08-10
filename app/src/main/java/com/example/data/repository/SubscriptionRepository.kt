package com.batchfee.edu.data.repository

import com.batchfee.edu.data.models.SubscriptionRequest
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class SubscriptionReceiptResult(
    val receiptNumber: String,
    val instituteName: String,
    val ownerName: String,
    val ownerPhone: String,
    val ownerEmail: String,
    val instituteCode: String,
    val instituteAddress: String,
    val planName: String,
    val durationMonths: Int,
    val amountPaid: Double,
    val paymentMethod: String,
    val transactionLast4: String,
    val startDateMs: Long,
    val endDateMs: Long
)

data class SubscriptionInstituteResult(
    val currentPlanId: String,
    val subscriptionStatus: String,
    val currentPeriodEndMs: Long,
    val isActive: Boolean,
    val studentLimit: Int? = null,
    val staffLimit: Int? = null
)

class SubscriptionRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) {

    /**
     * The Function, not the mobile app, resolves the current plan price and owns
     * duplicate-payment checks. A raw transaction reference is never persisted;
     * the Function stores only its hash and last four characters.
     */
    suspend fun submitRequest(
        instituteId: String,
        requestedPlanId: String,
        durationMonths: Int,
        paymentMethod: String,
        transactionReference: String,
        operationId: String = UUID.randomUUID().toString()
    ): SubscriptionRequest {
        val result = commit(
            instituteId = instituteId,
            action = "submit_request",
            operationId = operationId,
            values = mapOf(
                "requestedPlanId" to requestedPlanId,
                "durationMonths" to durationMonths,
                "paymentMethod" to paymentMethod,
                "transactionReference" to transactionReference
            )
        )
        return SubscriptionRequest.fromFirestore(
            id = result.map("request").string("requestId"),
            data = result.map("request")
        )
    }

    suspend fun getPendingRequests(): List<SubscriptionRequest> {
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .whereEqualTo("status", "pending")
                .orderBy("requestSentAt")
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { data ->
                    SubscriptionRequest.fromFirestore(doc.id, data)
                }
            }
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            emptyList()
        }
    }

    suspend fun getRequestsForInstitute(instituteId: String): List<SubscriptionRequest> {
        return try {
            val snapshot = firestore.collection(COLLECTION)
                .whereEqualTo("instituteId", instituteId)
                .orderBy("requestSentAt")
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { data ->
                    SubscriptionRequest.fromFirestore(doc.id, data)
                }
            }
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            emptyList()
        }
    }

    suspend fun approveRequest(
        instituteId: String,
        requestId: String,
        operationId: String = UUID.randomUUID().toString()
    ): SubscriptionReceiptResult {
        val receipt = commit(
            instituteId = instituteId,
            action = "approve_request",
            operationId = operationId,
            values = mapOf("requestId" to requestId)
        ).map("receipt")
        return SubscriptionReceiptResult(
            receiptNumber = receipt.string("receiptNumber"),
            instituteName = receipt.string("instituteName"),
            ownerName = receipt.string("ownerName"),
            ownerPhone = receipt.optionalString("ownerPhone"),
            ownerEmail = receipt.optionalString("ownerEmail"),
            instituteCode = receipt.optionalString("instituteCode"),
            instituteAddress = receipt.optionalString("instituteAddress"),
            planName = receipt.string("planName"),
            durationMonths = receipt.int("durationMonths"),
            amountPaid = receipt.double("amountPaid"),
            paymentMethod = receipt.string("paymentMethod"),
            transactionLast4 = receipt.optionalString("transactionLast4"),
            startDateMs = receipt.long("startDateMs"),
            endDateMs = receipt.long("endDateMs")
        )
    }

    suspend fun rejectRequest(
        instituteId: String,
        requestId: String,
        note: String? = null,
        operationId: String = UUID.randomUUID().toString()
    ) {
        commit(
            instituteId = instituteId,
            action = "reject_request",
            operationId = operationId,
            values = mapOf("requestId" to requestId, "note" to note)
        )
    }

    suspend fun extendSubscription(
        instituteId: String,
        daysToAdd: Int,
        reason: String,
        operationId: String = UUID.randomUUID().toString()
    ): SubscriptionInstituteResult = commitInstitute(
        instituteId = instituteId,
        action = "extend_subscription",
        operationId = operationId,
        values = mapOf("daysToAdd" to daysToAdd, "reason" to reason.trim())
    )

    suspend fun setInstituteBlocked(
        instituteId: String,
        blocked: Boolean,
        operationId: String = UUID.randomUUID().toString()
    ): SubscriptionInstituteResult = commitInstitute(
        instituteId = instituteId,
        action = "set_institute_blocked",
        operationId = operationId,
        values = mapOf("blocked" to blocked)
    )

    suspend fun manageInstituteSubscription(
        instituteId: String,
        newExpiryMs: Long,
        studentLimit: Int,
        staffLimit: Int,
        planId: String,
        isActive: Boolean,
        operationId: String = UUID.randomUUID().toString()
    ): SubscriptionInstituteResult = commitInstitute(
        instituteId = instituteId,
        action = "manage_institute_subscription",
        operationId = operationId,
        values = mapOf(
            "newExpiryMs" to newExpiryMs,
            "studentLimit" to studentLimit,
            "staffLimit" to staffLimit,
            "planId" to planId,
            "isActive" to isActive
        )
    )

    private suspend fun commitInstitute(
        instituteId: String,
        action: String,
        operationId: String,
        values: Map<String, Any?>
    ): SubscriptionInstituteResult {
        val institute = commit(instituteId, action, operationId, values).map("institute")
        return SubscriptionInstituteResult(
            currentPlanId = institute.string("currentPlanId"),
            subscriptionStatus = institute.string("subscriptionStatus"),
            currentPeriodEndMs = institute.long("currentPeriodEndMs"),
            isActive = institute.boolean("isActive"),
            studentLimit = institute.optionalInt("studentLimit"),
            staffLimit = institute.optionalInt("staffLimit")
        )
    }

    private suspend fun commit(
        instituteId: String,
        action: String,
        operationId: String,
        values: Map<String, Any?>
    ): Map<String, Any?> {
        require(instituteId.isNotBlank()) { "Institute is required." }
        return try {
            val response = functions.getHttpsCallable("commitSubscriptionOperation")
                .call(
                    values + mapOf(
                        "instituteId" to instituteId,
                        "action" to action,
                        "operationId" to operationId
                    )
                )
                .await()
            @Suppress("UNCHECKED_CAST")
            response.data as? Map<String, Any?>
                ?: error("Invalid subscription service response.")
        } catch (error: FirebaseFunctionsException) {
            when (error.code) {
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.ALREADY_EXISTS,
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> throw IllegalArgumentException(
                    error.message ?: "Subscription action was rejected.",
                    error
                )
                else -> throw error
            }
        }
    }

    private companion object {
        const val COLLECTION = "subscriptionRequests"
    }
}

private fun Map<String, Any?>.map(key: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?> ?: error("Missing $key in subscription response.")
}

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: error("Missing $key in subscription response.")

private fun Map<String, Any?>.optionalString(key: String): String = this[key] as? String ?: ""

private fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: error("Missing $key in subscription response.")

private fun Map<String, Any?>.int(key: String): Int =
    (this[key] as? Number)?.toInt() ?: error("Missing $key in subscription response.")

private fun Map<String, Any?>.optionalInt(key: String): Int? = (this[key] as? Number)?.toInt()

private fun Map<String, Any?>.double(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: error("Missing $key in subscription response.")

private fun Map<String, Any?>.boolean(key: String): Boolean =
    this[key] as? Boolean ?: error("Missing $key in subscription response.")

