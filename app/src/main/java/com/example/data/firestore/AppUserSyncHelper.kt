package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.models.UserEntity
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
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
    val status: String = "active",
    val platformRole: String? = null
)

object AppUserSyncHelper {
    private const val COLLECTION = "app_users"
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun upsertManagedUser(record: ManagedUserRecord) = withContext(Dispatchers.IO) {
        try {
            val fields = mutableMapOf<String, Any?>(
                    "name" to record.name,
                    "email" to record.email,
                    "role" to record.role,
                    "instituteId" to record.instituteId,
                    "createdAtMs" to record.createdAtMs,
                    "status" to record.status
                )
            // A normal institute profile sync must never erase a server-issued
            // platform role. Only the trusted platform callable may assign it.
            record.platformRole?.let { fields["platformRole"] = it }
            firestore.collection(COLLECTION).document(record.id).set(fields).await()
        } catch (e: Exception) {
            FirebaseFailureReporter.report(e, "sync app user to Firestore", permissionDeniedIsExpected = true)
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
                status = doc.getString("status") ?: "active",
                platformRole = doc.getString("platformRole")
            )
        } catch (e: Exception) {
            FirebaseFailureReporter.report(e, "sync app user from Firestore", permissionDeniedIsExpected = true)
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

