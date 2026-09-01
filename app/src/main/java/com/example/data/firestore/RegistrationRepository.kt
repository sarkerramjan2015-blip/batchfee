package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.models.PendingRegistration
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.batchfee.edu.data.repository.StudentAccountRepository
import com.batchfee.edu.data.repository.callTrustedFunction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class RegistrationRepository {
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)

    data class PublicRegistrationProfile(
        val instituteId: String,
        val instituteName: String,
        val slug: String,
        val phone: String?,
        val logoUri: String?
    )

    fun pendingRegistrationsFlow(instituteId: String): Flow<List<PendingRegistration>> = callbackFlow {
        val listener = firestore.collection("registrations")
            .document(instituteId)
            .collection("pending")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Access may legitimately disappear on logout, expiry or a staff
                    // permission change. Do not turn a listener rejection into an
                    // uncaught ViewModel coroutine exception.
                    FirebaseFailureReporter.report(
                        error,
                        operation = "pending registrations listener",
                        permissionDeniedIsExpected = true
                    )
                    // Surface the terminal listener error to the ViewModel so
                    // it can refresh a stale token and re-attach briefly. A
                    // silent close left the screen permanently empty until it
                    // was opened again.
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

    /**
     * Publishes only the fields a visitor needs to identify a registration form.
     * The private institute document is never made publicly readable.
     */
    suspend fun syncPublicRegistrationProfile(instituteId: String): PublicRegistrationProfile {
        val data = callTrustedFunction(
            functions,
            "createRegistrationProfile",
            mapOf("instituteId" to instituteId)
        ) as? Map<*, *> ?: error("Registration service returned an invalid response.")
        return PublicRegistrationProfile(
            instituteId = data["instituteId"] as? String ?: instituteId,
            instituteName = data["instituteName"] as? String ?: "Institute",
            slug = data["slug"] as? String
                ?: error("Registration service did not return a link."),
            phone = data["phone"] as? String,
            logoUri = data["profilePhotoUri"] as? String
        )
    }

    fun getRegistrationFormUrl(profile: PublicRegistrationProfile): String {
        return "https://batchfee-477b8.web.app/register/${android.net.Uri.encode(profile.slug)}"
    }

    fun getRegistrationShareText(profile: PublicRegistrationProfile): String =
        "${profile.instituteName}\nOfficial Student Registration Form\n${getRegistrationFormUrl(profile)}"

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
                    "bloodGroup" to registration.bloodGroup,
                    "schoolName" to registration.schoolName,
                    "className" to registration.className,
                    "address" to registration.address,
                    "submittedAt" to registration.submittedAt,
                    "rejectedAt" to System.currentTimeMillis()
                )
            ).await()
    }

    companion object {
        const val PUBLIC_REGISTRATION_PROFILES = "public_registration_profiles"

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
                    bloodGroup = getString("bloodGroup"),
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

