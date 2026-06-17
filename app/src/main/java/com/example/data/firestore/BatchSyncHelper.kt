package com.example.data.firestore

import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object BatchSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()

    private fun batchesCollection(instituteId: String) =
        firestore.collection("institutes").document(instituteId).collection("batches")

    suspend fun upsertBatch(batch: BatchEntity) {
        withContext(Dispatchers.IO) {
            try {
                batchesCollection(batch.instituteId)
                    .document(batch.id)
                    .set(batch.toFirestore())
                    .await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                throw e
            }
        }
    }

    suspend fun archiveBatch(batch: BatchEntity) {
        withContext(Dispatchers.IO) {
            try {
                batchesCollection(batch.instituteId)
                    .document(batch.id)
                    .set(batch.copy(archivedAtMs = System.currentTimeMillis()).toFirestore())
                    .await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = batchesCollection(instituteId).get().await()
                snapshot.documents
                    .mapNotNull { document -> document.toBatchEntity(instituteId) }
                    .forEach { batch -> db.batchDao().insertBatch(batch) }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun BatchEntity.toFirestore(): Map<String, Any?> = mapOf(
        "instituteId" to instituteId,
        "batchCode" to batchCode,
        "name" to name,
        "subject" to subject,
        "className" to className,
        "teacherName" to teacherName,
        "monthlyFeeAmount" to monthlyFeeAmount,
        "admissionFeeAmount" to admissionFeeAmount,
        "startDateMs" to startDateMs,
        "endDateMs" to endDateMs,
        "scheduleDays" to scheduleDays,
        "startTime" to startTime,
        "endTime" to endTime,
        "maxStudents" to maxStudents,
        "status" to status,
        "description" to description,
        "createdAtMs" to createdAtMs,
        "updatedAtMs" to updatedAtMs,
        "archivedAtMs" to archivedAtMs
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toBatchEntity(
        instituteId: String
    ): BatchEntity? {
        val batchCode = getString("batchCode") ?: return null
        val name = getString("name") ?: return null
        return BatchEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            batchCode = batchCode,
            name = name,
            subject = getString("subject"),
            className = getString("className"),
            teacherName = getString("teacherName"),
            monthlyFeeAmount = getDoubleCompat("monthlyFeeAmount") ?: 0.0,
            admissionFeeAmount = getDoubleCompat("admissionFeeAmount") ?: 0.0,
            startDateMs = getLongCompat("startDateMs"),
            endDateMs = getLongCompat("endDateMs"),
            scheduleDays = getString("scheduleDays"),
            startTime = getString("startTime"),
            endTime = getString("endTime"),
            maxStudents = getLongCompat("maxStudents")?.toInt(),
            status = getString("status") ?: "active",
            description = getString("description"),
            createdAtMs = getLongCompat("createdAtMs") ?: System.currentTimeMillis(),
            updatedAtMs = getLongCompat("updatedAtMs") ?: System.currentTimeMillis(),
            archivedAtMs = getLongCompat("archivedAtMs")
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.getLongCompat(field: String): Long? =
        (get(field) as? Number)?.toLong()

    private fun com.google.firebase.firestore.DocumentSnapshot.getDoubleCompat(field: String): Double? =
        (get(field) as? Number)?.toDouble()
}
