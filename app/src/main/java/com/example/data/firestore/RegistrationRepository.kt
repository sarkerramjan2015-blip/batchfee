package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.models.PendingRegistration
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class RegistrationRepository {
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

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
    suspend fun syncPublicRegistrationProfile(
        instituteId: String,
        name: String,
        phone: String?,
        logoUri: String?
    ): PublicRegistrationProfile {
        val cleanName = name.trim().ifBlank { "Institute" }
        val slug = buildRegistrationSlug(cleanName, instituteId)
        val publicLogoUri = logoUri?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("file:") }
        val profile = PublicRegistrationProfile(
            instituteId = instituteId,
            instituteName = cleanName,
            slug = slug,
            phone = phone?.trim()?.takeIf { it.isNotBlank() },
            logoUri = publicLogoUri
        )
        val data = mapOf(
            "instituteId" to profile.instituteId,
            "instituteName" to profile.instituteName,
            "slug" to profile.slug,
            "phone" to profile.phone,
            "profilePhotoUri" to profile.logoUri,
            "updatedAtMs" to System.currentTimeMillis()
        )
        firestore.batch().apply {
            // Human-readable URL profile.
            set(firestore.collection(PUBLIC_REGISTRATION_PROFILES).document(profile.slug), data)
            // Keeps previously shared ?instituteId= links branded too.
            set(
                firestore.collection(PUBLIC_REGISTRATION_PROFILES).document("id_$instituteId"),
                data
            )
        }.commit().await()
        return profile
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

        private fun buildRegistrationSlug(name: String, instituteId: String): String {
            val words = name
                .lowercase(java.util.Locale.ROOT)
                .trim()
                .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                .trim('-')
                .take(48)
                .ifBlank { "institute" }
            val suffix = instituteId.takeLast(6).lowercase(java.util.Locale.ROOT)
            return "$words-$suffix"
        }

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

