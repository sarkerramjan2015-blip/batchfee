package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.domain.InstituteContactNumber
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object InstituteSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()

    suspend fun updateStudentCount(instituteId: String, count: Int) {
        withContext(Dispatchers.IO) {
            try {
                firestore.collection("institutes").document(instituteId)
                    .update("studentCount", count).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "update student count", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun updateStaffCount(instituteId: String, count: Int) {
        withContext(Dispatchers.IO) {
            try {
                firestore.collection("institutes").document(instituteId)
                    .update("staffCount", count).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "update staff count", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun updateBatchCount(instituteId: String, count: Int) {
        withContext(Dispatchers.IO) {
            try {
                firestore.collection("institutes").document(instituteId)
                    .update("batchCount", count).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "update batch count", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun syncInstituteFromFirestore(db: AppDatabase, instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = firestore.collection("institutes").document(instituteId).get().await()
                if (!snapshot.exists()) return@withContext
                val data = snapshot.data ?: emptyMap<String, Any?>()
                val localProfilePhotoUri = db.instituteDao().getInstitute(instituteId)?.profilePhotoUri
                val now = System.currentTimeMillis()
                val currentPlanId = data["currentPlanId"] as? String ?: "plan_free_trial"
                val subscriptionStatus = data["subscriptionStatus"] as? String
                    ?: if (currentPlanId == "plan_free_trial") "trial" else "active"
                val cloudPhone = data["phone"] as? String
                val cloudWhatsApp = data["whatsappNumber"] as? String
                db.instituteDao().insertInstitute(
                    InstituteEntity(
                        id = snapshot.id,
                        name = data["instituteName"] as? String ?: data["name"] as? String ?: "Institute",
                        currentPlanId = currentPlanId,
                        subscriptionStatus = subscriptionStatus,
                        trialStartDateMs = (data["createdAt"] as? Number)?.toLong() ?: now,
                        trialEndDateMs = (data["trialEndDate"] as? Number)?.toLong() ?: now,
                        currentPeriodEndMs = (data["currentPeriodEndMs"] as? Number)?.toLong()
                            ?: ((data["trialEndDate"] as? Number)?.toLong() ?: now),
                        createdAtMs = (data["createdAt"] as? Number)?.toLong() ?: now,
                        phone = InstituteContactNumber.primary(cloudPhone, cloudWhatsApp),
                        address = data["address"] as? String,
                        whatsappNumber = InstituteContactNumber.whatsapp(cloudPhone, cloudWhatsApp),
                        // Preserve local file URIs. For remote URLs, use Firestore as authority
                        // with local fallback so a stale Firestore doc doesn't nuke a valid URL.
                        profilePhotoUri = when {
                            localProfilePhotoUri != null && localProfilePhotoUri.startsWith("file:") -> localProfilePhotoUri
                            else -> {
                                val cloudUri = data["profilePhotoUri"] as? String
                                if (!cloudUri.isNullOrBlank()) cloudUri else localProfilePhotoUri
                            }
                        },
                        ownerName = data["ownerName"] as? String,
                        email = data["email"] as? String,
                        instituteCode = data["instituteCode"] as? String,
                        securityPin = data["securityPin"] as? String
                    )
                )
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync institute from Firestore", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun syncInstituteToFirestore(institute: InstituteEntity) {
        withContext(Dispatchers.IO) {
            try {
                firestore.collection("institutes").document(institute.id).set(
                    mapOf(
                        "instituteName" to institute.name,
                        "phone" to institute.phone,
                        "address" to institute.address,
                        "whatsappNumber" to institute.whatsappNumber,
                        // The media callable returns a Firebase Storage download URL for the
                        // public institute logo, so it remains display-compatible across devices.
                        "profilePhotoUri" to institute.profilePhotoUri?.takeUnless { it.startsWith("file:") },
                        "ownerName" to institute.ownerName,
                        "email" to institute.email,
                        "instituteCode" to institute.instituteCode
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync institute to Firestore", permissionDeniedIsExpected = true)
                throw e
            }
        }
    }
}

