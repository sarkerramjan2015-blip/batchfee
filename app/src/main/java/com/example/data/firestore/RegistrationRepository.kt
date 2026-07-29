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

    suspend fun syncInstituteInfo(instituteId: String, name: String, phone: String?, logoUri: String?) {
        val data = mutableMapOf<String, Any>("instituteName" to name)
        phone?.takeIf { it.isNotBlank() }?.let { data["phone"] = it }
        logoUri?.takeIf { it.isNotBlank() }?.let { data["profilePhotoUri"] = it }
        firestore.collection("institutes").document(instituteId).set(data, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    fun getRegistrationFormUrl(instituteId: String): String {
        val expiryMs = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
        return "https://batchfee-477b8.web.app/register.html?instituteId=$instituteId&t=$expiryMs"
    }

    suspend fun logRejectedRegistration(instituteId: String, registration: PendingRegistration) {
        firestore.collection("registrations")
            .document(instituteId)
            .collection("rejected")
            .document(registration.requestId)
            .set(
                mapOf(
                    "instituteId" to instituteId,
                    "fullName" to registration.fullName,
                    "phone" to registration.phone,
                    "guardianName" to registration.guardianName,
                    "gender" to registration.gender,
                    "dateOfBirthMs" to registration.dateOfBirthMs,
                    "schoolName" to registration.schoolName,
                    "className" to registration.className,
                    "address" to registration.address,
                    "submittedAt" to registration.submittedAt,
                    "rejectedAt" to System.currentTimeMillis()
                )
            ).await()
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

