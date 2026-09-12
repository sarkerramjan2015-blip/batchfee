package com.batchfee.edu.ui.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchAttendanceSummaryTest {
    @Test
    fun lateCountsAsAttendedButNotPunctual() {
        val summary = BatchAttendanceSummary(
            totalStudents = 26,
            presentCount = 20,
            absentCount = 2,
            leaveCount = 1,
            lateCount = 3,
            lateMinutesTotal = 51
        )

        assertEquals(92f, summary.attendanceRatePct, 0.01f)
        assertEquals(86.956f, summary.punctualityRatePct, 0.01f)
        assertEquals(17f, summary.averageLateMinutes, 0.01f)
        assertEquals(26, summary.markedCount)
    }

    @Test
    fun legacyHolidayRemainsVisibleButDoesNotChangeAttendanceRate() {
        val summary = BatchAttendanceSummary(
            totalStudents = 4,
            presentCount = 1,
            absentCount = 1,
            lateCount = 1,
            holidayCount = 1
        )

        assertEquals(66.666f, summary.attendanceRatePct, 0.01f)
        assertEquals(50f, summary.punctualityRatePct, 0.01f)
        assertEquals(4, summary.markedCount)
    }
}
