package com.batchfee.student.data.firebase

import com.batchfee.student.data.models.*
import com.batchfee.student.demo.DemoDataProvider

/**
 * Repository that returns demo/mock data.
 * In demo mode, all data comes from [DemoDataProvider].
 * When Firebase is added back, this will switch between Firebase and demo based on a flag.
 */
class StudentFirestoreRepository {

    companion object {
        private var _forceDemo = true
        fun forceDemoMode() { _forceDemo = true }
    }

    // ── Student Profile ──
    suspend fun getStudent(instituteId: String, studentId: String): Student? {
        return DemoDataProvider.mockStudent
    }

    // ── Institute Info ──
    suspend fun getInstitute(instituteId: String): Institute? {
        return DemoDataProvider.mockInstitute
    }

    // ── Batches ──
    suspend fun getStudentBatches(instituteId: String, studentId: String): List<Batch> {
        return DemoDataProvider.mockBatches
    }

    // ── Fees ──
    suspend fun getFees(instituteId: String, studentId: String): List<Fee> {
        return DemoDataProvider.mockFees
    }

    // ── Payments & Receipts ──
    suspend fun getPayments(instituteId: String, studentId: String): List<Payment> {
        return DemoDataProvider.mockPayments
    }

    suspend fun getReceipts(instituteId: String, studentId: String): List<Receipt> {
        return DemoDataProvider.mockReceipts
    }

    // ── Attendance ──
    suspend fun getAttendance(instituteId: String, studentId: String, batchId: String): List<Attendance> {
        return DemoDataProvider.getAttendanceForBatch(batchId)
    }

    suspend fun getAttendanceSummary(
        instituteId: String,
        studentId: String,
        batchId: String
    ): AttendanceSummary {
        return DemoDataProvider.getAttendanceSummaryForBatch(batchId)
    }

    // ── Exams ──
    suspend fun getExams(instituteId: String, batchId: String): List<Exam> {
        return DemoDataProvider.mockExams.filter { it.batchId == batchId }
    }

    // ── Results ──
    suspend fun getResults(instituteId: String, studentId: String): List<Result> {
        return DemoDataProvider.mockResults.filter { it.studentId == studentId }
    }

    suspend fun getResultsForExam(instituteId: String, examId: String): List<Result> {
        return DemoDataProvider.mockResults.filter { it.examId == examId }
    }

    // ── Homework ──
    suspend fun getHomework(instituteId: String, batchId: String): List<Homework> {
        return DemoDataProvider.mockHomework.filter { it.batchId == batchId }
    }

    // ── Notices ──
    suspend fun getNotices(instituteId: String, batchIds: List<String>): List<Notice> {
        return DemoDataProvider.mockNotices.filter { notice ->
            notice.targetBatchIds == null || notice.targetBatchIds.any { it in batchIds }
        }
    }
}
