package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyDueCalculatorTest {

    @Test
    fun `admission on first day keeps full monthly fee`() {
        assertEquals(3000.0, MonthlyDueCalculator.calculateFirstMonthFee(3000.0, date(2026, Calendar.AUGUST, 1)), 0.0)
    }

    @Test
    fun `admission on twenty first charges ten days and rounds to BDT`() {
        assertEquals(1000.0, MonthlyDueCalculator.calculateFirstMonthFee(3000.0, date(2026, Calendar.AUGUST, 21)), 0.0)
        assertEquals(667.0, MonthlyDueCalculator.calculateFirstMonthFee(2000.0, date(2026, Calendar.AUGUST, 21)), 0.0)
    }

    @Test
    fun `only the frozen first month uses prorated amount`() {
        assertEquals(
            1000.0,
            MonthlyDueCalculator.monthlyFeeAmountForPeriod("Aug 2026", 3000.0, "Aug 2026", 1000.0),
            0.0
        )
        assertEquals(
            3000.0,
            MonthlyDueCalculator.monthlyFeeAmountForPeriod("Sep 2026", 3000.0, "Aug 2026", 1000.0),
            0.0
        )
    }

    @Test
    fun `only completed months are due and a shifted student keeps only prior batch months`() {
        val admitted = date(2026, Calendar.JUNE, 10)
        val asOfAugust = date(2026, Calendar.AUGUST, 15)

        val activePeriods = MonthlyDueCalculator.computeMonthlyOutstandingItems(
            admissionDateMs = admitted,
            monthlyFeeAmount = 3_000.0,
            batchId = "batch-a",
            batchName = "Old batch",
            existingMonthlyFees = emptyList(),
            firstMonthFeePeriod = MonthlyDueCalculator.periodFor(admitted),
            firstMonthFeeAmount = MonthlyDueCalculator.calculateFirstMonthFee(3_000.0, admitted),
            asOfMs = asOfAugust
        ).map { it.period }
        assertEquals(listOf("Jun 2026", "Jul 2026"), activePeriods)

        val shiftedPeriods = MonthlyDueCalculator.computeMonthlyOutstandingItems(
            admissionDateMs = admitted,
            monthlyFeeAmount = 3_000.0,
            batchId = "batch-a",
            batchName = "Old batch",
            existingMonthlyFees = emptyList(),
            firstMonthFeePeriod = MonthlyDueCalculator.periodFor(admitted),
            firstMonthFeeAmount = MonthlyDueCalculator.calculateFirstMonthFee(3_000.0, admitted),
            billingEndedAtMs = date(2026, Calendar.AUGUST, 15),
            asOfMs = asOfAugust
        ).map { it.period }
        assertEquals(listOf("Jun 2026", "Jul 2026"), shiftedPeriods)
    }

    @Test
    fun `admission month is prorated and becomes due next month`() {
        val admitted = date(2026, Calendar.JULY, 10)
        val asOfAugust = date(2026, Calendar.AUGUST, 15)

        val dues = MonthlyDueCalculator.computeMonthlyOutstandingItems(
            admissionDateMs = admitted,
            monthlyFeeAmount = 1_000.0,
            batchId = "batch-a",
            batchName = "HSC",
            existingMonthlyFees = emptyList(),
            firstMonthFeePeriod = MonthlyDueCalculator.periodFor(admitted),
            firstMonthFeeAmount = MonthlyDueCalculator.calculateFirstMonthFee(1_000.0, admitted),
            asOfMs = asOfAugust
        )

        assertEquals(1, dues.size)
        assertEquals("Jul 2026", dues.single().period)
        assertEquals(700.0, dues.single().outstanding, 0.0)
    }

    @Test
    fun `custom monthly fee starts from its saved period and never rewrites earlier months`() {
        assertEquals(
            1_000.0,
            MonthlyDueCalculator.monthlyFeeAmountForPeriod(
                period = "Jul 2026",
                monthlyFeeAmount = 1_000.0,
                firstMonthFeePeriod = "Jun 2026",
                firstMonthFeeAmount = 700.0,
                customMonthlyFeeAmount = 600.0,
                customFeeEffectiveFromPeriod = "Aug 2026"
            ),
            0.0
        )
        assertEquals(
            600.0,
            MonthlyDueCalculator.monthlyFeeAmountForPeriod(
                period = "Aug 2026",
                monthlyFeeAmount = 1_000.0,
                firstMonthFeePeriod = "Jun 2026",
                firstMonthFeeAmount = 700.0,
                customMonthlyFeeAmount = 600.0,
                customFeeEffectiveFromPeriod = "Aug 2026"
            ),
            0.0
        )
    }

    @Test
    fun `advance fee labels are monthly installments rather than immediate one-time dues`() {
        assertEquals(true, MonthlyDueCalculator.isMonthlyFeeType("advance_fee"))
        assertEquals(true, MonthlyDueCalculator.isMonthlyFeeType("Advance Fee"))
        assertEquals(true, MonthlyDueCalculator.isMonthlyFeeType("mixed_period"))
        assertEquals(false, MonthlyDueCalculator.isMonthlyFeeType("exam_fee"))
    }

    @Test
    fun `a multi month advance is due only after its final month and covers both months`() {
        val asOfAugust = date(2026, Calendar.AUGUST, 15)
        val asOfSeptember = date(2026, Calendar.SEPTEMBER, 15)
        val asOfOctober = date(2026, Calendar.OCTOBER, 1)
        val range = "Aug 2026 - Sep 2026"

        assertEquals(listOf("Aug 2026", "Sep 2026"), MonthlyDueCalculator.billingPeriodsCoveredBy(range))
        assertEquals(false, MonthlyDueCalculator.isMonthlyInstallmentDue("advance_fee", range, asOfAugust))
        assertEquals(false, MonthlyDueCalculator.isMonthlyInstallmentDue("advance_fee", range, asOfSeptember))
        assertEquals(true, MonthlyDueCalculator.isMonthlyInstallmentDue("advance_fee", range, asOfOctober))
    }

    @Test
    fun `a real multi month record prevents duplicate virtual monthly dues`() {
        val rangeFee = FeeEntity(
            id = "range", instituteId = "inst", studentId = "student", batchId = "batch",
            feePeriod = "Aug 2026 - Sep 2026", feeType = "advance_fee",
            dueDateMs = 0L, baseAmount = 1_800.0, discountAmount = 0.0, lateFeeAmount = 0.0,
            totalAmount = 1_800.0, paidAmount = 0.0, dueAmount = 1_800.0,
            status = "unpaid", note = null, createdAtMs = 0L, updatedAtMs = 0L, cancelledAtMs = null
        )
        val computed = MonthlyDueCalculator.computeMonthlyOutstandingItems(
            admissionDateMs = date(2026, Calendar.JULY, 1),
            monthlyFeeAmount = 1_000.0,
            batchId = "batch", batchName = "HSC", existingMonthlyFees = listOf(rangeFee),
            asOfMs = date(2026, Calendar.OCTOBER, 1)
        )

        assertEquals(listOf("Jul 2026"), computed.map { it.period })
    }

    private fun date(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(year, month, day, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
