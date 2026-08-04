package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.InstituteEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun updateStaffCount(instituteId: String, count: Int) {
        withContext(Dispatchers.IO) {
            try {
                firestore.collection("institutes").document(instituteId)
                    .update("staffCount", count).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun updateBatchCount(instituteId: String, count: Int) {
        withContext(Dispatchers.IO) {
            try {
                firestore.collection("institutes").document(instituteId)
                    .update("batchCount", count).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
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
                        phone = data["phone"] as? String,
                        address = data["address"] as? String,
                        whatsappNumber = data["whatsappNumber"] as? String,
                        // Local logos are free and device-only. Preserve them during a cloud refresh
                        // instead of replacing them with an unavailable Firebase Storage URL.
                        profilePhotoUri = localProfilePhotoUri?.takeIf { it.startsWith("file:") }
                            ?: data["profilePhotoUri"] as? String,
                        ownerName = data["ownerName"] as? String,
                        email = data["email"] as? String,
                        instituteCode = data["instituteCode"] as? String,
                        securityPin = data["securityPin"] as? String
                    )
                )
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
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
                        // Cloudinary returns a public HTTPS URL, so this value is safe to sync
                        // and can be displayed by every authorised app user on any device.
                        "profilePhotoUri" to institute.profilePhotoUri?.takeUnless { it.startsWith("file:") },
                        "ownerName" to institute.ownerName,
                        "email" to institute.email,
                        "instituteCode" to institute.instituteCode,
                        "currentPlanId" to institute.currentPlanId,
                        "subscriptionStatus" to institute.subscriptionStatus,
                        "trialStartDateMs" to institute.trialStartDateMs,
                        "trialEndDateMs" to institute.trialEndDateMs,
                        "currentPeriodEndMs" to institute.currentPeriodEndMs,
                        "createdAt" to institute.createdAtMs
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }
}

