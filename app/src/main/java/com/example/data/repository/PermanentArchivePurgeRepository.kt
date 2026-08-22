package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Mirrors a completed server-side purge only after Firebase confirms success.
 * The server is authoritative, so a failed call always leaves the local archive intact.
 */
class PermanentArchivePurgeRepository(private val db: AppDatabase) {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    suspend fun purgeBatch(instituteId: String, batchId: String) {
        functions.getHttpsCallable("permanentlyPurgeBatch").call(
            mapOf(
                "instituteId" to instituteId,
                "batchId" to batchId
            )
        ).await()

        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val batchArgs = arrayOf<Any?>(instituteId, batchId)
            // Delete children before their parent fee/work records. Students themselves stay in the app.
            listOf(
                "DELETE FROM payment_reversals WHERE instituteId = ? AND feeId IN (SELECT id FROM fees WHERE instituteId = ? AND batchId = ?)",
                "DELETE FROM payments WHERE instituteId = ? AND feeId IN (SELECT id FROM fees WHERE instituteId = ? AND batchId = ?)",
                "DELETE FROM receipts WHERE instituteId = ? AND feeId IN (SELECT id FROM fees WHERE instituteId = ? AND batchId = ?)",
                "DELETE FROM homework_submissions WHERE instituteId = ? AND homeworkId IN (SELECT id FROM homework WHERE instituteId = ? AND batchId = ?)",
                "DELETE FROM assignment_submissions WHERE instituteId = ? AND assignmentId IN (SELECT id FROM assignments WHERE instituteId = ? AND batchId = ?)",
                "DELETE FROM batch_students WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM attendance WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM absent_messages WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM results WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM exams WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM works WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM homework WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM assignments WHERE instituteId = ? AND batchId = ?",
                "DELETE FROM fees WHERE instituteId = ? AND batchId = ?"
            ).forEach { statement -> sql.execSQL(statement, batchArgs) }
            sql.execSQL(
                "DELETE FROM financial_outbox WHERE instituteId = ? AND requestJson LIKE ?",
                arrayOf(instituteId, "%$batchId%")
            )
            sql.execSQL(
                "DELETE FROM deletion_outbox WHERE instituteId = ? AND entityType = 'batch' AND entityId = ?",
                batchArgs
            )
            db.batchDao().deleteBatch(instituteId, batchId)
        }
    }

    suspend fun purgeStaff(instituteId: String, staffId: String) {
        functions.getHttpsCallable("permanentlyPurgeStaff").call(
            mapOf(
                "instituteId" to instituteId,
                "staffId" to staffId
            )
        ).await()

        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val args = arrayOf<Any?>(instituteId, staffId)
            listOf(
                "DELETE FROM staff_attendance WHERE instituteId = ? AND staffId = ?",
                "DELETE FROM salaries WHERE instituteId = ? AND staffId = ?",
                "DELETE FROM audit_logs WHERE instituteId = ? AND userId = ?",
                "DELETE FROM users WHERE instituteId = ? AND id = ?",
                "DELETE FROM deletion_outbox WHERE instituteId = ? AND entityType = 'staff' AND entityId = ?"
            ).forEach { statement -> sql.execSQL(statement, args) }
            db.staffDao().deleteStaff(instituteId, staffId)
        }
    }

    suspend fun purgeInstitute(instituteId: String) {
        functions.getHttpsCallable("permanentlyPurgeInstitute").call(
            mapOf("instituteId" to instituteId)
        ).await()

        // Mirror the server result only after the trusted purge succeeds. Child
        // tables are cleared before parent tables to preserve local FK integrity.
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val args = arrayOf<Any?>(instituteId)
            listOf(
                "payment_reversals", "receipts", "payments", "results", "attendance",
                "absent_messages", "homework_submissions", "assignment_submissions",
                "batch_students", "fees", "exams", "homework", "assignments", "works",
                "staff_attendance", "salaries", "audit_logs", "expenses", "enquiries",
                "reminder_templates", "financial_outbox", "deletion_outbox", "staff",
                "students", "batches", "users"
            ).forEach { table ->
                sql.execSQL("DELETE FROM $table WHERE instituteId = ?", args)
            }
            sql.execSQL("DELETE FROM institutes WHERE id = ?", args)
        }
    }
}
