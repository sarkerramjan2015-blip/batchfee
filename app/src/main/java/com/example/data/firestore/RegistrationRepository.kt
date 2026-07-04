package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.models.PendingRegistration
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class RegistrationRepository {
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun pendingRegistrationsFlow(instituteId: String): Flow<List<PendingRegistration>> = callbackFlow {
        val listener = firestore.collection("registrations")
            .document(instituteId)
            .collection("pending")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    doc.toRegistration()
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun getPendingRegistration(instituteId: String, requestId: String): PendingRegistration? {
        val doc = firestore.collection("registrations")
            .document(instituteId)
            .collection("pending")
            .document(requestId)
            .get()
            .await()
        return doc.toRegistration()
    }

    suspend fun deletePendingRegistration(instituteId: String, requestId: String) {
        firestore.collection("registrations")
            .document(instituteId)
            .collection("pending")
            .document(requestId)
            .delete()
            .await()
    }

    suspend fun syncInstituteInfo(instituteId: String, name: String, phone: String?) {
        val data = mutableMapOf<String, Any>("name" to name)
        phone?.takeIf { it.isNotBlank() }?.let { data["phone"] = it }
        firestore.collection("institutes").document(instituteId).set(data).await()
    }

    fun getRegistrationFormUrl(instituteId: String): String {
        return "https://batchfee-477b8.web.app/register.html?instituteId=$instituteId"
    }

    companion object {
        private fun com.google.firebase.firestore.DocumentSnapshot.toRegistration(): PendingRegistration? {
            return try {
                PendingRegistration(
                    requestId = id,
                    instituteId = getString("instituteId") ?: return null,
                    fullName = getString("fullName") ?: "",
                    phone = getString("phone") ?: "",
                    guardianName = getString("guardianName"),
                    whatsappNumber = getString("whatsappNumber"),
                    gender = getString("gender"),
                    dateOfBirthMs = getLong("dateOfBirthMs"),
                    schoolName = getString("schoolName"),
                    className = getString("className"),
                    address = getString("address"),
                    submittedAt = getLong("submittedAt") ?: System.currentTimeMillis(),
                    status = getString("status") ?: "pending"
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

