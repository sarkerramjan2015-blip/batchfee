package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity

/**
 * A read-only billing projection for the student app.
 *
 * Owner screens already use [MonthlyDueCalculator] as the source of truth for
 * admission-date, enrollment and monthly-arrears rules.  Student screens read
 * their data directly from Firestore, so this small adapter applies those exact
 * rules without creating or modifying any ledger record.
 */
data class StudentBillingFee(
    val id: String,
    val batchId: String?,
    val feePeriod: String,
    val feeType: String,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double,
    val status: String,
    val cancelledAtMs: Long? = null
)

data class StudentBillingEnrollment(
    val batchId: String,
    val status: String,
    val joinedAtMs: Long,
    val leftAtMs: Long?,
    val firstMonthFeePeriod: String?,
    val firstMonthFeeAmount: Double?,
    val customMonthlyFeeAmount: Double?,
    val customFeeEffectiveFromPeriod: String?
)

data class StudentBillingBatch(
    val id: String,
    val name: String,
    val monthlyFeeAmount: Double,
    val admissionFeeAmount: Double,
    val billingMode: String = "monthly",
    val courseFeeAmount: Double = 0.0
)

data class StudentBillingSummary(
    val totalAmount: Double = 0.0,
    val totalPaid: Double = 0.0,
    val totalDue: Double = 0.0
)

object StudentBillingSummaryCalculator {
    fun calculate(
        studentAdmissionDateMs: Long,
        fees: List<StudentBillingFee>,
        enrollments: List<StudentBillingEnrollment>,
        batches: Collection<StudentBillingBatch>,
        asOfMs: Long = System.currentTimeMillis()
    ): StudentBillingSummary {
        val batchesById = batches.associateBy { it.id }
        val activeFees = fees.filterNot(::isCancelled)

        fun isEligibleMonthlyFee(fee: StudentBillingFee): Boolean = enrollments
            .filter { it.batchId == fee.batchId }
            .any { enrollment ->
                MonthlyDueCalculator.isMonthlyFeeWithinEnrollmentWindow(
                    feePeriod = fee.feePeriod,
                    studentAdmissionDateMs = studentAdmissionDateMs,
                    enrollmentJoinedAtMs = enrollment.joinedAtMs,
                    firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                    billingEndedAtMs = enrollment.leftAtMs
                )
            }

        // A running-month installment is not an arrear yet.  The owner due
        // report follows the same rule; one-time charges remain visible now.
        val visibleActualFees = activeFees.filterNot { fee ->
            MonthlyDueCalculator.isMonthlyFeeType(fee.feeType) &&
                fee.dueAmount > 0.0 &&
                (!MonthlyDueCalculator.isMonthlyInstallmentDue(
                    fee.feeType,
                    fee.feePeriod,
                    asOfMs
                ) || !isEligibleMonthlyFee(fee))
        }
        val visibleMonthlyKeys = visibleActualFees
            .filter { MonthlyDueCalculator.isMonthlyFeeType(it.feeType) }
            .map { "${it.batchId.orEmpty()}|${it.feePeriod}" }
            .toSet()

        var total = visibleActualFees.sumOf { it.totalAmount }
        var paid = visibleActualFees.sumOf { it.paidAmount }
        var due = visibleActualFees.sumOf { it.dueAmount.coerceAtLeast(0.0) }

        enrollments.forEach { enrollment ->
            val batch = batchesById[enrollment.batchId] ?: return@forEach
            if (!batch.isCourseBatch() && batch.monthlyFeeAmount > 0.0) {
                val billingStartMs = MonthlyDueCalculator.effectiveBillingStartMs(
                    studentAdmissionDateMs,
                    enrollment.joinedAtMs,
                    enrollment.firstMonthFeePeriod
                )
                val existingMonthlyFees = activeFees
                    .filter {
                        it.batchId == batch.id &&
                            MonthlyDueCalculator.isMonthlyFeeType(it.feeType)
                    }
                    .map { it.toMonthlyLedgerFee() }
                MonthlyDueCalculator.computeMonthlyOutstandingItems(
                    admissionDateMs = billingStartMs,
                    monthlyFeeAmount = batch.monthlyFeeAmount,
                    batchId = batch.id,
                    batchName = batch.name,
                    existingMonthlyFees = existingMonthlyFees,
                    firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                    firstMonthFeeAmount = enrollment.firstMonthFeeAmount,
                    customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                    customFeeEffectiveFromPeriod = enrollment.customFeeEffectiveFromPeriod,
                    billingEndedAtMs = enrollment.leftAtMs,
                    asOfMs = asOfMs
                ).forEach { item ->
                    if ("${batch.id}|${item.period}" !in visibleMonthlyKeys) {
                        total += item.monthlyFeeAmount
                        paid += item.paidAmount
                        due += item.outstanding
                    }
                }
            }

            val admissionAlreadyCreated = activeFees.any { fee ->
                fee.batchId == batch.id && fee.feeType.equals("admission_fee", ignoreCase = true)
            }
            if (enrollment.status.equals("active", ignoreCase = true) &&
                batch.admissionFeeAmount > 0.0 && !admissionAlreadyCreated) {
                total += batch.admissionFeeAmount
                due += batch.admissionFeeAmount
            }

            val courseFeeAlreadyCreated = activeFees.any { fee ->
                fee.batchId == batch.id && fee.feeType.equals("course_fee", ignoreCase = true)
            }
            if (enrollment.status.equals("active", ignoreCase = true) &&
                batch.isCourseBatch() && batch.courseFeeAmount > 0.0 && !courseFeeAlreadyCreated) {
                total += batch.courseFeeAmount
                due += batch.courseFeeAmount
            }
        }

        return StudentBillingSummary(totalAmount = total, totalPaid = paid, totalDue = due)
    }

    private fun isCancelled(fee: StudentBillingFee): Boolean =
        fee.cancelledAtMs != null || fee.status.equals("cancelled", ignoreCase = true)

    private fun StudentBillingFee.toMonthlyLedgerFee(): FeeEntity = FeeEntity(
        id = id,
        instituteId = "",
        studentId = "",
        batchId = batchId,
        feePeriod = feePeriod,
        feeType = feeType,
        dueDateMs = 0L,
        baseAmount = totalAmount,
        discountAmount = 0.0,
        lateFeeAmount = 0.0,
        totalAmount = totalAmount,
        paidAmount = paidAmount,
        dueAmount = dueAmount,
        status = status,
        note = null,
        createdAtMs = 0L,
        updatedAtMs = 0L,
        cancelledAtMs = cancelledAtMs
    )
}

private fun StudentBillingBatch.isCourseBatch(): Boolean =
    billingMode.equals("course", ignoreCase = true)
