package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.AttendanceSyncHelper
import com.batchfee.edu.data.firestore.BatchStudentSyncHelper
import com.batchfee.edu.data.firestore.ExamSyncHelper
import com.batchfee.edu.data.firestore.FinanceSyncHelper
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.firestore.StudentSyncHelper
import com.batchfee.edu.data.models.StudentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Irreversibly removes a student and all records that are keyed to that student.
 * Remote deletion completes first; a network/permission failure leaves the local data intact.
 */
class StudentDeletionRepository(private val db: AppDatabase) {
    suspend fun permanentlyDelete(student: StudentEntity) = withContext(Dispatchers.IO) {
        val instituteId = student.instituteId
        val studentId = student.id

        BatchStudentSyncHelper.deleteEnrollmentsForStudent(instituteId, studentId)
        FinanceSyncHelper.deleteRecordsForStudent(instituteId, studentId)
        AttendanceSyncHelper.deleteRecordsForStudent(instituteId, studentId)
        ExamSyncHelper.deleteResultsForStudent(instituteId, studentId)
        StudentSyncHelper.deleteStudent(studentId, instituteId)

        db.withTransaction {
            val feeIds = db.feeDao().getFeeIdsForStudent(instituteId, studentId)
            if (feeIds.isNotEmpty()) {
                db.receiptDao().deleteReceiptsByFeeIds(instituteId, feeIds)
                db.paymentDao().deletePaymentsByFeeIds(instituteId, feeIds)
            }
            db.receiptDao().deleteReceiptsForStudent(instituteId, studentId)
            db.paymentDao().deletePaymentsForStudent(instituteId, studentId)
            db.feeDao().deleteFeesForStudent(instituteId, studentId)
            db.batchStudentDao().deleteEnrollmentsForStudent(studentId, instituteId)
            db.attendanceDao().deleteAttendanceForStudent(instituteId, studentId)
            db.absentMessageDao().deleteMessagesForStudent(instituteId, studentId)
            db.resultDao().deleteResultsForStudent(instituteId, studentId)
            db.studentDao().deleteStudent(studentId, instituteId)
        }

        // This count is derived locally after the transaction, then mirrored to the institute doc.
        val activeCount = db.studentDao().getStudentsByInstituteOnce(instituteId).size
        InstituteSyncHelper.updateStudentCount(instituteId, activeCount)
    }
}
