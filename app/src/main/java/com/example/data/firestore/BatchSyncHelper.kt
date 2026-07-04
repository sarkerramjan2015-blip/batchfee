package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.BatchEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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

    suspend fun deleteBatchPermanently(batch: BatchEntity) {
        withContext(Dispatchers.IO) {
            try {
                deleteBatchRelatedData(batch.instituteId, batch.id)
                batchesCollection(batch.instituteId).document(batch.id).delete().await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                throw e
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

    private suspend fun deleteBatchRelatedData(instituteId: String, batchId: String) {
        deleteDocsByBatch(instituteId, "batch_students", batchId)
        deleteDocsByBatch(instituteId, "attendance", batchId)
        deleteDocsByBatch(instituteId, "absent_messages", batchId)
        deleteDocsByBatch(instituteId, "exams", batchId)
        deleteDocsByBatch(instituteId, "results", batchId)

        val feeSnapshot = firestore.collection("institutes").document(instituteId)
            .collection("fees")
            .whereEqualTo("batchId", batchId)
            .get()
            .await()

        val feeIds = feeSnapshot.documents.map { it.id }
        deleteDocuments(feeSnapshot.documents)
        if (feeIds.isNotEmpty()) {
            deleteDocsByFieldChunks(instituteId, "payments", "feeId", feeIds)
            deleteDocsByFieldChunks(instituteId, "receipts", "feeId", feeIds)
        }
    }

    private suspend fun deleteDocsByBatch(instituteId: String, collectionName: String, batchId: String) {
        val snapshot = firestore.collection("institutes").document(instituteId)
            .collection(collectionName)
            .whereEqualTo("batchId", batchId)
            .get()
            .await()
        deleteDocuments(snapshot.documents)
    }

    private suspend fun deleteDocsByFieldChunks(
        instituteId: String,
        collectionName: String,
        fieldName: String,
        values: List<String>
    ) {
        values.distinct().chunked(10).forEach { chunk ->
            val snapshot = firestore.collection("institutes").document(instituteId)
                .collection(collectionName)
                .whereIn(fieldName, chunk)
                .get()
                .await()
            deleteDocuments(snapshot.documents)
        }
    }

    private suspend fun deleteDocuments(documents: List<com.google.firebase.firestore.DocumentSnapshot>) {
        documents.forEach { it.reference.delete().await() }
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

