package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/** Mirrors a completed server purge locally. Never removes local data before the server succeeds. */
class PermanentStudentPurgeRepository(private val db: AppDatabase) {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    suspend fun purge(instituteId: String, studentId: String) {
        functions.getHttpsCallable("permanentlyPurgeStudent").call(
            mapOf(
                "instituteId" to instituteId,
                "studentId" to studentId
            )
        ).await()

        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val args = arrayOf<Any?>(instituteId, studentId)
            listOf(
                "batch_students", "attendance", "fees", "payments", "receipts", "results",
                "absent_messages", "homework_submissions", "assignment_submissions"
            ).forEach { table -> sql.execSQL("DELETE FROM $table WHERE instituteId = ? AND studentId = ?", args) }
            db.studentDao().deleteStudent(instituteId, studentId)
        }
    }
}
