package com.batchfee.edu.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StudentBillingSummaryCalculatorTest {

    @Test
    fun studentSummaryFollowsAdmissionProrationAndExcludesCancelledCharges() {
        val june10 = date(2026, Calendar.JUNE, 10)
        val august15 = date(2026, Calendar.AUGUST, 15)
        val batch = StudentBillingBatch(
            id = "batch-1",
            name = "HSC 2027",
            monthlyFeeAmount = 1_000.0,
            admissionFeeAmount = 500.0
        )
        val enrollment = StudentBillingEnrollment(
            batchId = batch.id,
            status = "active",
            joinedAtMs = june10,
            leftAtMs = null,
            firstMonthFeePeriod = "Jun 2026",
            firstMonthFeeAmount = 700.0,
            customMonthlyFeeAmount = null,
            customFeeEffectiveFromPeriod = null
        )
        val summary = StudentBillingSummaryCalculator.calculate(
            studentAdmissionDateMs = june10,
            enrollments = listOf(enrollment),
            batches = listOf(batch),
            asOfMs = august15,
            fees = listOf(
                fee(
                    id = "jul-paid",
                    batchId = batch.id,
                    feePeriod = "Jul 2026",
                    feeType = "monthly_fee",
                    total = 1_000.0,
                    paid = 1_000.0,
                    due = 0.0,
                    status = "paid"
                ),
                fee(
                    id = "exam",
                    batchId = batch.id,
                    feePeriod = "Aug 2026",
                    feeType = "exam_fee",
                    total = 200.0,
                    paid = 0.0,
                    due = 200.0,
                    status = "pending"
                ),
                fee(
                    id = "cancelled",
                    batchId = batch.id,
                    feePeriod = "Jun 2026",
                    feeType = "exam_fee",
                    total = 500.0,
                    paid = 0.0,
                    due = 500.0,
                    status = "cancelled"
                )
            )
        )

        // Jun is prorated to 700; Jul is paid; Aug is running month. The
        // virtual admission and the unpaid exam remain due. The cancelled fee
        // is never included in any student-facing number.
        assertEquals(2_400.0, summary.totalAmount, 0.001)
        assertEquals(1_000.0, summary.totalPaid, 0.001)
        assertEquals(1_400.0, summary.totalDue, 0.001)
    }

    private fun fee(
        id: String,
        batchId: String,
        feePeriod: String,
        feeType: String,
        total: Double,
        paid: Double,
        due: Double,
        status: String
    ) = StudentBillingFee(
        id = id,
        batchId = batchId,
        feePeriod = feePeriod,
        feeType = feeType,
        totalAmount = total,
        paidAmount = paid,
        dueAmount = due,
        status = status
    )

    private fun date(year: Int, month: Int, day: Int): Long = Calendar
        .getInstance(TimeZone.getTimeZone("Asia/Dhaka"))
        .apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }
        .timeInMillis
}
