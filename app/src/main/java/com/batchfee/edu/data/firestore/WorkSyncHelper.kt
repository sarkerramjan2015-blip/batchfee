package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.WorkEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object WorkSyncHelper {
    private val firestore = FirebaseFirestore.getInstance()

    private fun worksCollection(instituteId: String) =
        firestore.collection("institutes").document(instituteId).collection("works")

    suspend fun upsertWork(work: WorkEntity) {
        withContext(Dispatchers.IO) {
            try {
                worksCollection(work.instituteId).document(work.id)
                    .set(work.toFirestore()).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun deleteWork(work: WorkEntity) {
        withContext(Dispatchers.IO) {
            try {
                worksCollection(work.instituteId).document(work.id).delete().await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun syncWorksFromFirestore(db: AppDatabase, instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = worksCollection(instituteId).get().await()
                snapshot.documents.mapNotNull { it.toWorkEntity(instituteId) }
                    .forEach { db.workDao().upsertWork(it) }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun WorkEntity.toFirestore(): Map<String, Any?> = mapOf(
        "instituteId" to instituteId,
        "batchId" to batchId,
        "type" to type,
        "title" to title,
        "description" to description,
        "dueDateMs" to dueDateMs,
        "createdAtMs" to createdAtMs,
        "updatedAtMs" to updatedAtMs,
        "archivedAtMs" to archivedAtMs
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toWorkEntity(instituteId: String): WorkEntity? {
        val title = getString("title") ?: return null
        return WorkEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            batchId = getString("batchId"),
            type = getString("type") ?: "HOMEWORK",
            title = title,
            description = getString("description") ?: "",
            dueDateMs = (get("dueDateMs") as? Number)?.toLong(),
            createdAtMs = (get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            archivedAtMs = (get("archivedAtMs") as? Number)?.toLong()
        )
    }
}
