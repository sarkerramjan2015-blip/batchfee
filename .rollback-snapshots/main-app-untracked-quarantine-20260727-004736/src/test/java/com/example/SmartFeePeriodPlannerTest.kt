package com.batchfee.edu

import com.batchfee.edu.domain.FeeMonthStatus
import com.batchfee.edu.domain.MonthlyFeeSchedule
import com.batchfee.edu.domain.MonthlyFeeSnapshot
import com.batchfee.edu.domain.SmartFeePeriodPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SmartFeePeriodPlannerTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = date(2026, 7, 24)
    private val months = (3..10).map { MonthlyFeeSchedule.BillingMonth(2026, it) }

    @Test fun monthsBeforeFeeStartAreDisabled() {
        val result = build(admission = date(2026, 6, 1))
        assertEquals(FeeMonthStatus.DISABLED, result.first { it.period.month == 5 }.status)
        assertFalse(result.first { it.period.month == 5 }.enabled)
    }

    @Test fun oldestPartialIsDefaultBeforeOlderUnpaidMonths() {
        val result = build(fees = listOf(fee(6, due = 400.0, paid = 600.0), fee(7, due = 1_000.0)))
        assertEquals(6, SmartFeePeriodPlanner.defaultStart(result)!!.period.month)
    }

    @Test fun rangeContainsOnlyTheRequestedCalendarMonths() {
        val result = build()
        val selected = SmartFeePeriodPlanner.range(result, month(6), month(8))
        assertEquals(listOf(6, 7, 8), selected.map { it.period.month })
    }

    @Test fun dueCurrentAndAdvanceAreClassifiedFromActualMonths() {
        val result = build()
        assertEquals(FeeMonthStatus.DUE, result.first { it.period.month == 6 }.status)
        assertEquals(FeeMonthStatus.CURRENT, result.first { it.period.month == 7 }.status)
        assertEquals(FeeMonthStatus.ADVANCE, result.first { it.period.month == 8 }.status)
    }

    @Test fun partialPaymentRemainsPartial() {
        val result = build(fees = listOf(fee(7, due = 400.0, paid = 600.0)))
        assertEquals(FeeMonthStatus.PARTIAL, result.first { it.period.month == 7 }.status)
        assertEquals(400.0, result.first { it.period.month == 7 }.remainingDue, 0.0001)
    }

    @Test fun autoAllocationPaysPartialThenOldestDue() {
        val result = build(fees = listOf(fee(6, due = 400.0, paid = 600.0), fee(7, due = 1_000.0)))
        val allocation = SmartFeePeriodPlanner.allocate(result.filter { it.period.month in 6..7 }, 900.0, 0.0, autoOrder = true)
        assertEquals(listOf(6, 7), allocation.map { it.period.month })
        assertEquals(listOf(400.0, 500.0), allocation.map { it.collectedAmount })
    }

    @Test fun customSelectionCanTargetLaterMonthWhileOlderDueRemains() {
        val result = build(fees = listOf(fee(6), fee(7), fee(8)))
        val selected = result.filter { it.period.month == 7 }
        assertTrue(SmartFeePeriodPlanner.hasEarlierUnselectedDue(selected, result))
        assertEquals(7, SmartFeePeriodPlanner.allocate(selected, 1_000.0, 0.0, false).single().period.month)
    }

    @Test fun skippedMonthStaysOutOfCustomAllocation() {
        val result = build(fees = listOf(fee(6), fee(7), fee(8)))
        val selected = result.filter { it.period.month in setOf(6, 8) }
        assertEquals(listOf(6, 8), SmartFeePeriodPlanner.allocate(selected, 2_000.0, 0.0, false).map { it.period.month })
    }

    @Test fun discountChangesOnlySelectedMonthPayableAmount() {
        val result = build(fees = listOf(fee(6), fee(7)))
        val june = result.first { it.period.month == 6 }
        assertEquals(900.0, SmartFeePeriodPlanner.payableAfterDiscount(june, 10.0), 0.0001)
        assertEquals(1_000.0, SmartFeePeriodPlanner.payableAfterDiscount(result.first { it.period.month == 7 }, 0.0), 0.0001)
    }

    @Test fun paidMonthCannotProduceASecondCollectionAllocation() {
        val result = build(fees = listOf(fee(6, due = 0.0, paid = 1_000.0)))
        assertEquals(FeeMonthStatus.PAID, result.first { it.period.month == 6 }.status)
        assertFalse(result.first { it.period.month == 6 }.enabled)
        assertTrue(SmartFeePeriodPlanner.allocate(result.filter { it.period.month == 6 }, 1_000.0, 0.0, true).isEmpty())
    }

    @Test fun repeatedPlanningKeepsProblemFiveFeeIdentityStable() {
        val idOne = MonthlyFeeSchedule.monthlyFeeId("inst", "student", "batch", 2026, 8)
        val idTwo = MonthlyFeeSchedule.monthlyFeeId("inst", "student", "batch", 2026, 8)
        assertEquals(idOne, idTwo)
    }

    @Test fun selectedMonthTotalsAreConsistentForDashboardProfileAndBatch() {
        val selected = build(fees = listOf(fee(6), fee(7))).filter { it.period.month in 6..7 }
        val due = selected.sumOf { SmartFeePeriodPlanner.payableAfterDiscount(it, 0.0) }
        assertEquals(2_000.0, due, 0.0001)
    }

    private fun build(
        admission: Long = date(2026, 3, 1),
        fees: List<MonthlyFeeSnapshot> = emptyList()
    ) = SmartFeePeriodPlanner.buildMonths(months, admission, admission, 1_000.0, fees, now, utc)

    private fun fee(month: Int, due: Double = 1_000.0, paid: Double = 0.0) = MonthlyFeeSnapshot(
        id = "monthly_due_$month", period = month(month), baseAmount = 1_000.0, discountAmount = 0.0,
        lateFeeAmount = 0.0, totalAmount = 1_000.0, paidAmount = paid, dueAmount = due
    )

    private fun month(value: Int) = MonthlyFeeSchedule.BillingMonth(2026, value)

    private fun date(year: Int, month: Int, day: Int): Long = Calendar.getInstance(utc).run {
        clear(); set(year, month - 1, day, 12, 0, 0); timeInMillis
    }
}
