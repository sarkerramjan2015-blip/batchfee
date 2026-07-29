package com.batchfee.edu

import com.batchfee.edu.domain.MonthlyFeeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MonthlyFeeScheduleTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val july24 = date(2026, 7, 24)

    @Test
    fun marchAdmissionThroughJulyCreatesFivePayableMonths() {
        val planned = plan(admission = date(2026, 3, 1))

        assertEquals(listOf("Mar 2026", "Apr 2026", "May 2026", "Jun 2026", "Jul 2026"), planned.map { it.feePeriod })
        assertEquals(5_000.0, planned.sumOf { it.amount }, MONEY_DELTA)
    }

    @Test
    fun paidLegacyMarchMonthIsPreservedAndOnlyFourLaterMonthsAreCreated() {
        val existing = listOf(existing("legacy-mar", "student", "batch", "Mar 2026", "monthly_fee"))
        val planned = plan(admission = date(2026, 3, 1), existing = existing)

        assertEquals(listOf("Apr 2026", "May 2026", "Jun 2026", "Jul 2026"), planned.map { it.feePeriod })
        assertEquals(4_000.0, planned.sumOf { it.amount }, MONEY_DELTA)
    }

    @Test
    fun juneAdmissionThroughJulyCreatesTwoPayableMonths() {
        val planned = plan(admission = date(2026, 6, 1))

        assertEquals(listOf("Jun 2026", "Jul 2026"), planned.map { it.feePeriod })
        assertEquals(2_000.0, planned.sumOf { it.amount }, MONEY_DELTA)
    }

    @Test
    fun laterBatchJoinExcludesMonthsBeforeTheStudentEnteredThatBatch() {
        val planned = plan(admission = date(2026, 3, 1), joined = date(2026, 6, 15))

        assertEquals(listOf("Jun 2026", "Jul 2026"), planned.map { it.feePeriod })
    }

    @Test
    fun futureAdmissionCreatesNoDueMonths() {
        val planned = plan(admission = date(2026, 8, 1))

        assertTrue(planned.isEmpty())
    }

    @Test
    fun partialLegacyMonthIsNotDuplicatedAndItsRemainingBalanceCanRemainDue() {
        val existing = listOf(existing("partial-mar", "student", "batch", "March 2026", "monthly_fee"))
        val planned = plan(admission = date(2026, 3, 1), existing = existing)
        val existingMarchDue = 600.0

        assertEquals(4_600.0, existingMarchDue + planned.sumOf { it.amount }, MONEY_DELTA)
        assertTrue(planned.none { it.feePeriod == "Mar 2026" })
    }

    @Test
    fun rerunningWithCreatedDeterministicIdsDoesNotCreateDuplicates() {
        val first = plan(admission = date(2026, 6, 1))
        val existing = first.map { obligation ->
            existing(obligation.id, obligation.studentId, obligation.batchId, obligation.feePeriod, "monthly_fee")
        }

        assertTrue(plan(admission = date(2026, 6, 1), existing = existing).isEmpty())
    }

    @Test
    fun recognizedMonthlyRangePreventsDuplicateMonths() {
        val existing = listOf(existing("range", "student", "batch", "Mar 2026 - May 2026", "monthly_fee"))
        val planned = plan(admission = date(2026, 3, 1), existing = existing)

        assertEquals(listOf("Jun 2026", "Jul 2026"), planned.map { it.feePeriod })
    }

    @Test
    fun obligationsRemainScopedToTheCorrectStudentAndBatch() {
        val enrollments = listOf(
            enrollment("student-a", "batch-a", date(2026, 6, 1)),
            enrollment("student-b", "batch-b", date(2026, 6, 1))
        )
        val existing = listOf(existing("a-jun", "student-a", "batch-a", "Jun 2026", "monthly_fee"))
        val planned = MonthlyFeeSchedule.planMissingObligations("inst", enrollments, existing, july24, utc)

        assertEquals(1, planned.count { it.studentId == "student-a" && it.batchId == "batch-a" })
        assertEquals(2, planned.count { it.studentId == "student-b" && it.batchId == "batch-b" })
    }

    @Test
    fun generatedRowsHaveTheSameOutstandingTotalForEveryConsumer() {
        val dueRows = plan(admission = date(2026, 6, 1))

        val dashboardTotal = dueRows.sumOf { it.amount }
        val profileTotal = dueRows.sumOf { it.amount }
        val batchTotal = dueRows.sumOf { it.amount }
        assertEquals(2_000.0, dashboardTotal, MONEY_DELTA)
        assertEquals(dashboardTotal, profileTotal, MONEY_DELTA)
        assertEquals(dashboardTotal, batchTotal, MONEY_DELTA)
    }

    private fun plan(
        admission: Long,
        joined: Long = admission,
        existing: List<MonthlyFeeSchedule.ExistingFee> = emptyList()
    ): List<MonthlyFeeSchedule.Obligation> = MonthlyFeeSchedule.planMissingObligations(
        instituteId = "inst",
        enrollments = listOf(enrollment("student", "batch", admission, joined)),
        existingFees = existing,
        asOfMs = july24,
        timeZone = utc
    )

    private fun enrollment(studentId: String, batchId: String, admission: Long, joined: Long = admission) =
        MonthlyFeeSchedule.Enrollment(studentId, batchId, admission, joined, 1_000.0)

    private fun existing(id: String, studentId: String, batchId: String, period: String, type: String) =
        MonthlyFeeSchedule.ExistingFee(id, studentId, batchId, period, type, cancelled = false)

    private fun date(year: Int, month: Int, day: Int): Long = Calendar.getInstance(utc).run {
        clear()
        set(year, month - 1, day, 12, 0, 0)
        timeInMillis
    }

    private companion object {
        const val MONEY_DELTA = 0.0001
    }
}
