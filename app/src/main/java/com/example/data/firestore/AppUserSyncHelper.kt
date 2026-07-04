package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.models.UserEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class ManagedUserRecord(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val instituteId: String? = null,
    val createdAtMs: Long,
    val status: String = "active"
)

object AppUserSyncHelper {
    private const val COLLECTION = "app_users"
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun upsertManagedUser(record: ManagedUserRecord) = withContext(Dispatchers.IO) {
        try {
            firestore.collection(COLLECTION).document(record.id).set(
                mapOf(
                    "name" to record.name,
                    "email" to record.email,
                    "role" to record.role,
                    "instituteId" to record.instituteId,
                    "createdAtMs" to record.createdAtMs,
                    "status" to record.status
                )
            ).await()
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            throw e
        }
    }

    suspend fun fetchManagedUser(uid: String): ManagedUserRecord? = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection(COLLECTION).document(uid).get().await()
            if (!doc.exists()) return@withContext null
            ManagedUserRecord(
                id = doc.id,
                name = doc.getString("name") ?: "",
                email = doc.getString("email") ?: "",
                role = doc.getString("role") ?: "",
                instituteId = doc.getString("instituteId"),
                createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
                status = doc.getString("status") ?: "active"
            )
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            null
        }
    }

    fun toUserEntity(record: ManagedUserRecord, passwordHash: String = ""): UserEntity =
        UserEntity(
            id = record.id,
            instituteId = record.instituteId,
            name = record.name,
            email = record.email,
            passwordHash = passwordHash,
            role = record.role,
            createdAtMs = record.createdAtMs
        )
}

