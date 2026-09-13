package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

/** Mirrors a completed server purge locally. Never removes local data before the server succeeds. */
class PermanentStudentPurgeRepository(private val db: AppDatabase) {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    suspend fun purge(instituteId: String, studentId: String) {
        try {
            callTrustedFunction(functions, "permanentlyPurgeStudent",
                mapOf(
                    "instituteId" to instituteId,
                    "studentId" to studentId
                )
            )
        } catch (error: FirebaseFunctionsException) {
            throw IllegalArgumentException(
                deletionFailureMessage(error, "Could not permanently delete this student."),
                error
            )
        }

        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val args = arrayOf<Any?>(instituteId, studentId)
            listOf(
                "batch_students", "attendance", "fees", "payments", "receipts", "results",
                "absent_messages", "homework_submissions", "assignment_submissions", "payment_reversals"
            ).forEach { table -> sql.execSQL("DELETE FROM $table WHERE instituteId = ? AND studentId = ?", args) }
            db.studentDao().deleteStudent(instituteId, studentId)
        }
    }
}
