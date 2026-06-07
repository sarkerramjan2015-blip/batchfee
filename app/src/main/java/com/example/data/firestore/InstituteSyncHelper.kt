package com.example.data.firestore

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
}
