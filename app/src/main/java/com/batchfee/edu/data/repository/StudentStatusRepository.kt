package com.batchfee.edu.data.repository

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StudentEntity
import com.google.firebase.functions.FirebaseFunctionsException

/**
 * Student Active/Inactive is a cloud-first operational change. The trusted
 * backend must confirm the write before Room or the visible status moves, so a
 * session, permission, or network failure can never create a local-only status.
 */
class StudentStatusRepository(
    private val db: AppDatabase,
    private val gateway: StudentStatusGateway = FirebaseStudentStatusGateway()
) {
    suspend fun setStatus(student: StudentEntity, becomingActive: Boolean) {
        val updated = student.copy(
            status = if (becomingActive) "active" else "inactive",
            updatedAtMs = System.currentTimeMillis()
        )
        gateway.updateStatus(updated)
        db.studentDao().updateStudent(updated)
    }
}

fun studentStatusErrorMessage(error: Exception): String = when {
    error is StudentStatusSessionException ->
        "Session expired. Please sign in again."
    error is FirebaseFunctionsException &&
        error.code == FirebaseFunctionsException.Code.PERMISSION_DENIED ->
        "You do not have permission to update this student's status."
    else ->
        "Student status could not be updated. Check your connection and try again."
}
