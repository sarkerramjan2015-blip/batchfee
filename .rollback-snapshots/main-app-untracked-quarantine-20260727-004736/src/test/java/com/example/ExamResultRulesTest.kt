package com.batchfee.edu

import com.batchfee.edu.domain.ExamResultRules
import org.junit.Assert.*
import org.junit.Test

class ExamResultRulesTest {
    @Test fun negativeAndAboveTotalMarksAreRejected() {
        assertFalse(ExamResultRules.validateMark(-1.0, 100.0))
        assertFalse(ExamResultRules.validateMark(101.0, 100.0))
        assertTrue(ExamResultRules.validateMark(0.0, 100.0))
    }

    @Test fun absentStudentIsNotPassedOrRankedByMark() {
        val absent = ExamResultRules.outcome(0.0, 100.0, 40.0, isAbsent = true)
        assertEquals("Absent", absent.grade)
        assertFalse(absent.passed)
        assertEquals(0.0, absent.percentage, 0.0)
    }

    @Test fun passPercentageAndExistingGradeRuleAreConsistent() {
        assertFalse(ExamResultRules.outcome(39.0, 100.0, 40.0).passed)
        val passing = ExamResultRules.outcome(80.0, 100.0, 40.0)
        assertTrue(passing.passed)
        assertEquals(80.0, passing.percentage, 0.0)
        assertEquals("A+", passing.grade)
    }

    @Test fun reviewMovesDraftThroughMarksPendingToReady() {
        val none = ExamResultRules.review(3, listOf(null, null, null), listOf(false, false, false))
        val partial = ExamResultRules.review(3, listOf(ExamResultRules.outcome(50.0, 100.0, 40.0), null, null), listOf(false, false, false))
        val complete = ExamResultRules.review(3, listOf(ExamResultRules.outcome(50.0, 100.0, 40.0), ExamResultRules.outcome(0.0, 100.0, 40.0, true), ExamResultRules.outcome(40.0, 100.0, 40.0)), listOf(false, true, false))
        assertEquals(ExamResultRules.DRAFT, ExamResultRules.statusForReview(none))
        assertEquals(ExamResultRules.MARKS_PENDING, ExamResultRules.statusForReview(partial))
        assertEquals(ExamResultRules.READY_TO_PUBLISH, ExamResultRules.statusForReview(complete))
    }

    @Test fun incompleteExamCannotPublish() {
        assertFalse(ExamResultRules.canPublish(3, 2))
        assertTrue(ExamResultRules.canPublish(3, 3))
    }

    @Test fun publishedAndArchivedResultsCannotBeSilentlyEdited() {
        assertTrue(ExamResultRules.canEditResults(ExamResultRules.DRAFT))
        assertFalse(ExamResultRules.canEditResults(ExamResultRules.PUBLISHED))
        assertFalse(ExamResultRules.canEditResults(ExamResultRules.ARCHIVED))
    }

    @Test fun positionUsesCompetitionRankingForTies() {
        assertEquals(listOf(1, 1, 3), ExamResultRules.ranksDescending(listOf(90.0, 90.0, 80.0)))
    }
}
