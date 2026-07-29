package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.FinanceSyncHelper
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.domain.MonthlyFeeSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MonthlyFeeReconciliationResult(
    val createdCount: Int,
    val remoteRowsRestored: Boolean
)

/** Reconciles only missing month-level obligations; it never edits a legacy fee or payment. */
class MonthlyFeeReconciliationRepository(private val db: AppDatabase) {
    /**
     * Materializes exact future/current monthly obligations selected by collection.
     * IDs match Problem 05 reconciliation, so an advance payment cannot create a
     * second fee when that month becomes current.
     */
    suspend fun ensureMonthlyObligations(
        instituteId: String,
        studentId: String,
        batchId: String,
        monthlyAmount: Double,
        months: List<MonthlyFeeSchedule.BillingMonth>,
        now: Long = System.currentTimeMillis()
    ): List<FeeEntity> = withContext(Dispatchers.IO) {
        require(monthlyAmount > 0.0) { "Monthly fee must be greater than zero." }
        val wantedIds = months.distinct().map { month ->
            MonthlyFeeSchedule.monthlyFeeId(instituteId, studentId, batchId, month.year, month.month)
        }.toSet()
        var remoteRowsRestored = false
        months.distinct().forEach { month ->
            val id = MonthlyFeeSchedule.monthlyFeeId(instituteId, studentId, batchId, month.year, month.month)
            if (db.feeDao().getFeeById(id, instituteId) != null) return@forEach
            val fee = monthlyFee(
                id = id,
                instituteId = instituteId,
                studentId = studentId,
                batchId = batchId,
                month = month,
                amount = monthlyAmount,
                now = now
            )
            if (FinanceSyncHelper.createFeeIfAbsent(fee)) {
                db.withTransaction { db.feeDao().insertFee(fee) }
            } else {
                remoteRowsRestored = true
            }
        }
        if (remoteRowsRestored) FinanceSyncHelper.syncAllFromFirestore(db, instituteId)
        db.feeDao().getAllFeesOnce(instituteId).filter { it.id in wantedIds }
    }

    suspend fun reconcile(instituteId: String, asOfMs: Long = System.currentTimeMillis()): MonthlyFeeReconciliationResult =
        withContext(Dispatchers.IO) {
            if (instituteId.isBlank()) return@withContext MonthlyFeeReconciliationResult(0, false)

            val students = db.studentDao().getStudentsByInstituteOnce(instituteId).associateBy { it.id }
            val batches = db.batchDao().getBatchesByInstituteOnce(instituteId)
                .filter { it.status.equals("active", ignoreCase = true) && it.monthlyFeeAmount > 0.0 }
                .associateBy { it.id }
            val enrollments = db.batchStudentDao().getActiveEnrollmentsOnce(instituteId)
                .mapNotNull { enrollment ->
                    val student = students[enrollment.studentId]
                        ?.takeIf { it.status.equals("active", ignoreCase = true) }
                        ?: return@mapNotNull null
                    val batch = batches[enrollment.batchId] ?: return@mapNotNull null
                    MonthlyFeeSchedule.Enrollment(
                        studentId = student.id,
                        batchId = batch.id,
                        admissionDateMs = student.admissionDateMs,
                        joinedAtMs = enrollment.joinedAtMs,
                        monthlyFeeAmount = batch.monthlyFeeAmount
                    )
                }
            val existingFees = db.feeDao().getAllFeesIncludingCancelledOnce(instituteId).map { fee ->
                MonthlyFeeSchedule.ExistingFee(
                    id = fee.id,
                    studentId = fee.studentId,
                    batchId = fee.batchId,
                    feePeriod = fee.feePeriod,
                    feeType = fee.feeType,
                    cancelled = fee.cancelledAtMs != null
                )
            }
            val missing = MonthlyFeeSchedule.planMissingObligations(
                instituteId = instituteId,
                enrollments = enrollments,
                existingFees = existingFees,
                asOfMs = asOfMs
            )

            var createdCount = 0
            var remoteRowsRestored = false
            missing.forEach { obligation ->
                val fee = monthlyFee(
                    id = obligation.id,
                    instituteId = instituteId,
                    studentId = obligation.studentId,
                    batchId = obligation.batchId,
                    month = MonthlyFeeSchedule.BillingMonth(obligation.year, obligation.month),
                    amount = obligation.amount,
                    now = asOfMs
                )
                if (FinanceSyncHelper.createFeeIfAbsent(fee)) {
                    db.withTransaction { db.feeDao().insertFee(fee) }
                    createdCount++
                } else {
                    remoteRowsRestored = true
                }
            }
            if (remoteRowsRestored) FinanceSyncHelper.syncAllFromFirestore(db, instituteId)
            MonthlyFeeReconciliationResult(createdCount, remoteRowsRestored)
        }

    private fun monthlyFee(
        id: String,
        instituteId: String,
        studentId: String,
        batchId: String,
        month: MonthlyFeeSchedule.BillingMonth,
        amount: Double,
        now: Long
    ) = FeeEntity(
        id = id,
        instituteId = instituteId,
        studentId = studentId,
        batchId = batchId,
        feePeriod = MonthlyFeeSchedule.monthLabel(month.year, month.month),
        feeType = "monthly_fee",
        dueDateMs = MonthlyFeeSchedule.monthStartMs(month.year, month.month),
        baseAmount = amount,
        discountAmount = 0.0,
        lateFeeAmount = 0.0,
        totalAmount = amount,
        paidAmount = 0.0,
        dueAmount = amount,
        status = "unpaid",
        note = null,
        createdAtMs = now,
        updatedAtMs = now,
        cancelledAtMs = null
    )
}
