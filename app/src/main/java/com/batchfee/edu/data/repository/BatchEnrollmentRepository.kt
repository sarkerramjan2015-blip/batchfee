package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.BatchStudentSyncHelper
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.isCourseBatch
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID

/**
 * The single enrollment path for a student assigned from either the student
 * profile or a batch. Monthly batches retain their admission-date prorating;
 * courses instead receive one immutable, one-time ledger fee.
 */
class BatchEnrollmentRepository(private val db: AppDatabase) {
    private val feeRepository = FeeCollectionRepository(db)
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)

    data class UnassignResult(
        val removedAtMs: Long,
        val cancelledFeeIds: List<String>,
        val retainedHistoryFeeCount: Int
    )

    data class HardRemoveResult(
        val removedFeeCount: Int,
        val removedAttendanceCount: Int,
        val removedPaymentCount: Int,
        val removedReceiptCount: Int
    )

    data class ShiftResult(
        val sourceEnrollmentId: String,
        val targetEnrollmentId: String,
        val targetBatchId: String,
        val shiftDateMs: Long,
        val billingMode: String,
        val firstMonthFeePeriod: String?,
        val firstMonthFeeAmount: Double?,
        val courseFeeCreated: Boolean
    )

    suspend fun enroll(
        instituteId: String,
        studentId: String,
        batch: BatchEntity,
        enrollmentStartMs: Long,
        enrollmentId: String = UUID.randomUUID().toString()
    ): BatchStudentEntity {
        val enrollment = BatchStudentEntity(
            id = enrollmentId,
            instituteId = instituteId,
            batchId = batch.id,
            studentId = studentId,
            joinedAtMs = enrollmentStartMs,
            status = "active",
            leftAtMs = null,
            firstMonthFeePeriod = if (batch.isCourseBatch()) null
            else MonthlyDueCalculator.periodFor(enrollmentStartMs),
            firstMonthFeeAmount = if (batch.isCourseBatch()) null
            else MonthlyDueCalculator.calculateFirstMonthFee(batch.monthlyFeeAmount, enrollmentStartMs)
        )

        // Cloud is written first. If this fails there is no local enrollment
        // that could later create an incorrect fee while offline.
        BatchStudentSyncHelper.upsertEnrollment(enrollment)
        db.withTransaction { db.batchStudentDao().enrollStudent(enrollment) }

        if (batch.isCourseBatch()) {
            createCourseFeeIfNeeded(instituteId, studentId, batch, enrollmentStartMs)
        }
        return enrollment
    }

    /**
     * Removes one selected batch enrollment only.  The server decides which
     * linked fees are safe to cancel: unpaid batch fees disappear from the due
     * list, while any fee with a payment/receipt history is retained for audit.
     * Room changes only after that cloud transaction succeeds.
     */
    suspend fun unassign(enrollment: BatchStudentEntity): UnassignResult {
        require(enrollment.status.equals("active", ignoreCase = true)) {
            "This batch is already no longer assigned."
        }
        val response = callTrustedFunction(
            functions,
            "unassignStudentFromBatch",
            mapOf(
                "instituteId" to enrollment.instituteId,
                "studentId" to enrollment.studentId,
                "enrollmentId" to enrollment.id
            )
        ) as? Map<*, *> ?: error("Batch removal service returned an invalid response.")

        val removedAtMs = (response["removedAtMs"] as? Number)?.toLong()
            ?: error("Batch removal service did not confirm the removal time.")
        val cancelledFeeIds = (response["cancelledFeeIds"] as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()
        val retainedHistoryFeeCount = (response["retainedHistoryFeeCount"] as? Number)
            ?.toInt() ?: 0

        db.withTransaction {
            db.batchStudentDao().enrollStudent(
                enrollment.copy(status = "removed", leftAtMs = removedAtMs)
            )
            if (cancelledFeeIds.isNotEmpty()) {
                db.feeDao().markFeesCancelled(
                    instituteId = enrollment.instituteId,
                    feeIds = cancelledFeeIds,
                    cancelledAtMs = removedAtMs,
                    updatedAtMs = removedAtMs
                )
            }
        }
        return UnassignResult(removedAtMs, cancelledFeeIds, retainedHistoryFeeCount)
    }

    /**
     * Moves one active enrollment through the trusted server transaction.
     * Nothing is changed in Room until Firestore confirms both the source
     * removal and target enrollment. The server also creates a course's
     * one-time fee in that same transaction when the target is a course.
     */
    suspend fun shift(
        sourceEnrollment: BatchStudentEntity,
        targetBatch: BatchEntity,
        shiftDateMs: Long = System.currentTimeMillis(),
        targetEnrollmentId: String = UUID.randomUUID().toString(),
        operationId: String = UUID.randomUUID().toString()
    ): ShiftResult {
        require(sourceEnrollment.status.equals("active", ignoreCase = true)) {
            "This student is no longer active in the source batch."
        }
        require(sourceEnrollment.instituteId == targetBatch.instituteId) {
            "Source and target batch must belong to the same institute."
        }
        require(sourceEnrollment.batchId != targetBatch.id) {
            "Source and target batch must be different."
        }
        require(shiftDateMs >= sourceEnrollment.joinedAtMs) {
            "Shift date cannot be before the student's source-batch join date."
        }

        val response = callTrustedFunction(
            functions,
            "shiftStudentBetweenBatches",
            mapOf(
                "instituteId" to sourceEnrollment.instituteId,
                "studentId" to sourceEnrollment.studentId,
                "sourceEnrollmentId" to sourceEnrollment.id,
                "targetBatchId" to targetBatch.id,
                "targetEnrollmentId" to targetEnrollmentId,
                "shiftDateMs" to shiftDateMs,
                "operationId" to operationId
            )
        ) as? Map<*, *> ?: error("Batch shift service returned an invalid response.")

        val confirmedTargetEnrollmentId = response["targetEnrollmentId"] as? String
            ?: error("Batch shift service did not confirm the new enrollment.")
        val confirmedShiftDateMs = (response["shiftDateMs"] as? Number)?.toLong()
            ?: error("Batch shift service did not confirm the shift date.")
        val confirmedMode = (response["billingMode"] as? String)
            ?.takeIf { it.equals("course", ignoreCase = true) }
            ?: "monthly"
        val firstMonthFeePeriod = response["firstMonthFeePeriod"] as? String
        val firstMonthFeeAmount = (response["firstMonthFeeAmount"] as? Number)?.toDouble()
        val targetEnrollment = BatchStudentEntity(
            id = confirmedTargetEnrollmentId,
            instituteId = sourceEnrollment.instituteId,
            batchId = targetBatch.id,
            studentId = sourceEnrollment.studentId,
            joinedAtMs = confirmedShiftDateMs,
            status = "active",
            leftAtMs = null,
            firstMonthFeePeriod = firstMonthFeePeriod,
            firstMonthFeeAmount = firstMonthFeeAmount
        )

        db.withTransaction {
            db.batchStudentDao().enrollStudent(
                sourceEnrollment.copy(status = "removed", leftAtMs = confirmedShiftDateMs)
            )
            db.batchStudentDao().enrollStudent(targetEnrollment)
        }
        return ShiftResult(
            sourceEnrollmentId = sourceEnrollment.id,
            targetEnrollmentId = confirmedTargetEnrollmentId,
            targetBatchId = targetBatch.id,
            shiftDateMs = confirmedShiftDateMs,
            billingMode = confirmedMode,
            firstMonthFeePeriod = firstMonthFeePeriod,
            firstMonthFeeAmount = firstMonthFeeAmount,
            courseFeeCreated = response["courseFeeCreated"] as? Boolean ?: false
        )
    }

    /**
     * Permanent "Hard Remove": the trusted backend deletes the enrollment and
     * every fee, payment, receipt, reversal, attendance and absent-message
     * record scoped to this exact student+batch pair, writes an audit document,
     * and only then is the local mirror cleaned up.
     */
    suspend fun hardRemove(
        enrollment: BatchStudentEntity,
        reason: String
    ): HardRemoveResult {
        require(enrollment.status.equals("active", ignoreCase = true)) {
            "This batch is no longer assigned."
        }
        require(reason.trim().length >= 3) { "A removal reason is required." }
        val response = callTrustedFunction(
            functions,
            "hardRemoveStudentFromBatch",
            mapOf(
                "instituteId" to enrollment.instituteId,
                "studentId" to enrollment.studentId,
                "batchId" to enrollment.batchId,
                "reason" to reason.trim(),
                "operationId" to UUID.randomUUID().toString()
            )
        ) as? Map<*, *> ?: error("Batch removal service returned an invalid response.")

        val result = HardRemoveResult(
            removedFeeCount = (response["removedFeeCount"] as? Number)?.toInt() ?: 0,
            removedAttendanceCount = (response["removedAttendanceCount"] as? Number)?.toInt() ?: 0,
            removedPaymentCount = (response["removedPaymentCount"] as? Number)?.toInt() ?: 0,
            removedReceiptCount = (response["removedReceiptCount"] as? Number)?.toInt() ?: 0
        )

        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val feeLinkedArgs = arrayOf<Any?>(
                enrollment.instituteId, enrollment.instituteId,
                enrollment.studentId, enrollment.batchId
            )
            listOf(
                "DELETE FROM payment_reversals WHERE instituteId = ? AND feeId IN (SELECT id FROM fees WHERE instituteId = ? AND studentId = ? AND batchId = ?)",
                "DELETE FROM payments WHERE instituteId = ? AND feeId IN (SELECT id FROM fees WHERE instituteId = ? AND studentId = ? AND batchId = ?)",
                "DELETE FROM receipts WHERE instituteId = ? AND feeId IN (SELECT id FROM fees WHERE instituteId = ? AND studentId = ? AND batchId = ?)"
            ).forEach { sql.execSQL(it, feeLinkedArgs) }
            val scopedArgs = arrayOf<Any?>(
                enrollment.instituteId, enrollment.studentId, enrollment.batchId
            )
            listOf(
                "DELETE FROM fees WHERE instituteId = ? AND studentId = ? AND batchId = ?",
                "DELETE FROM attendance WHERE instituteId = ? AND studentId = ? AND batchId = ?",
                "DELETE FROM absent_messages WHERE instituteId = ? AND studentId = ? AND batchId = ?",
                "DELETE FROM batch_students WHERE instituteId = ? AND studentId = ? AND batchId = ?"
            ).forEach { sql.execSQL(it, scopedArgs) }
        }
        return result
    }

    private suspend fun createCourseFeeIfNeeded(
        instituteId: String,
        studentId: String,
        batch: BatchEntity,
        enrollmentStartMs: Long
    ) {
        try {
            feeRepository.createFee(
                instituteId = instituteId,
                studentId = studentId,
                batchId = batch.id,
                feePeriod = "Course",
                feeType = "course_fee",
                sourceId = "course:${batch.id}",
                dueDateMs = batch.startDateMs ?: enrollmentStartMs,
                baseAmount = batch.courseFeeAmount,
                discountAmount = 0.0,
                lateFeeAmount = 0.0,
                note = "Course fee · ${batch.name}"
            )
        } catch (error: Exception) {
            // A retry after a slow response reaches the ledger's deterministic
            // key and is therefore already complete, not a second charge.
            if (!error.message.orEmpty().contains("already exists", ignoreCase = true)) throw error
        }
    }
}
