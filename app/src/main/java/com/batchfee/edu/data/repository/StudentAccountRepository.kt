package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Owner/staff student-account operations run only in the trusted Firebase backend.
 * The Android client never creates Auth users or writes login lookup documents.
 */
class StudentAccountRepository {
    private val functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)

    suspend fun provision(
        instituteId: String,
        studentId: String,
        password: String
    ): String = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable("provisionStudentAccount")
            .call(
                mapOf(
                    "instituteId" to instituteId,
                    "studentId" to studentId,
                    "password" to password
                )
            )
            .await()
        val data = result.data as? Map<*, *>
            ?: error("Invalid account provisioning response.")
        data["firebaseUid"] as? String
            ?: error("Student account UID was not returned.")
    }

    suspend fun disable(instituteId: String, studentId: String) {
        callStudentAction("disableStudentAccount", instituteId, studentId)
    }

    suspend fun isSecurelyLinked(instituteId: String, studentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = functions.getHttpsCallable("getStudentAccountStatus")
                .call(mapOf("instituteId" to instituteId, "studentId" to studentId))
                .await()
            val data = result.data as? Map<*, *> ?: return@withContext false
            data["securelyLinked"] as? Boolean ?: false
        }

    private suspend fun callStudentAction(name: String, instituteId: String, studentId: String) {
        withContext(Dispatchers.IO) {
            functions.getHttpsCallable(name)
                .call(mapOf("instituteId" to instituteId, "studentId" to studentId))
                .await()
        }
    }

    companion object {
        const val FUNCTIONS_REGION = "asia-south1"
    }
}
