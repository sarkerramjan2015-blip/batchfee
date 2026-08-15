package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Removes a work item and every related submission from cloud and local data. */
class PermanentWorkPurgeRepository(private val db: AppDatabase) {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun purgeHomework(instituteId: String, homeworkId: String) {
        deleteMatchingCloudDocuments(instituteId, "homework_submissions", "homeworkId", homeworkId)
        firestore.collection("institutes").document(instituteId)
            .collection("homework").document(homeworkId).delete().await()
        db.withTransaction {
            db.homeworkDao().deleteSubmissionsForHomework(instituteId, homeworkId)
            db.homeworkDao().deletePermanently(homeworkId, instituteId)
        }
    }

    suspend fun purgeAssignment(instituteId: String, assignmentId: String) {
        deleteMatchingCloudDocuments(instituteId, "assignment_submissions", "assignmentId", assignmentId)
        firestore.collection("institutes").document(instituteId)
            .collection("assignments").document(assignmentId).delete().await()
        db.withTransaction {
            db.assignmentDao().deleteSubmissionsForAssignment(instituteId, assignmentId)
            db.assignmentDao().deletePermanently(assignmentId, instituteId)
        }
    }

    private suspend fun deleteMatchingCloudDocuments(
        instituteId: String,
        collection: String,
        foreignKey: String,
        workId: String
    ) {
        val ref = firestore.collection("institutes").document(instituteId).collection(collection)
        while (true) {
            val documents = ref.whereEqualTo(foreignKey, workId).limit(400).get().await().documents
            if (documents.isEmpty()) return
            val batch = firestore.batch()
            documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
}
