package com.batchfee.edu.domain

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

    private fun date(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(year, month, day, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
