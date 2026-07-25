package com.batchfee.edu.data.repository

import com.batchfee.edu.data.models.SubscriptionRequest
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID

class SubscriptionRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    suspend fun submitRequest(request: SubscriptionRequest): SubscriptionRequest {
        val docId = request.requestId.ifBlank { "SR-${System.currentTimeMillis()}" }
        val doc = request.copy(requestId = docId)
        firestore.collection(COLLECTION)
            .document(docId)
            .set(doc.toFirestore())
            .await()
        return doc
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

    suspend fun approveRequest(requestId: String, reviewerUserId: String) {
        val now = System.currentTimeMillis()
        firestore.collection(COLLECTION)
            .document(requestId)
            .update(
                mapOf(
                    "status" to "approved",
                    "reviewedBy" to reviewerUserId,
                    "reviewedAt" to now
                )
            )
            .await()
    }

    suspend fun rejectRequest(requestId: String, reviewerUserId: String, note: String? = null) {
        val now = System.currentTimeMillis()
        val update = mutableMapOf<String, Any>(
            "status" to "rejected",
            "reviewedBy" to reviewerUserId,
            "reviewedAt" to now
        )
        note?.takeIf { it.isNotBlank() }?.let { update["reviewerNote"] = it }
        firestore.collection(COLLECTION)
            .document(requestId)
            .update(update)
            .await()
    }

    private companion object {
        const val COLLECTION = "subscriptionRequests"
    }
}

