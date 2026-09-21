package com.batchfee.edu.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionMarkPolicyTest {
    private fun question(marks: Int) = ReviewableQuestion(
        sourceQuestionId = "q1",
        questionText = "Question",
        options = listOf("A", "B", "C", "D"),
        correctAnswer = "A",
        explanation = "",
        difficulty = "medium",
        marks = marks,
    )

    @Test
    fun mcqIsAlwaysOneMark() {
        assertTrue(QuestionReviewPolicy.validate("mcq", question(1)).isValid)
        assertFalse(QuestionReviewPolicy.validate("mcq", question(2)).isValid)
    }

    @Test
    fun creativeIsTenMarksForOneToFourSubparts() {
        assertTrue(QuestionReviewPolicy.validate("creative", question(10).copy(options = emptyList())).isValid)
        assertFalse(QuestionReviewPolicy.validate("creative", question(9).copy(options = emptyList())).isValid)
    }

    @Test
    fun shortQuestionCanUseConfiguredMarks() {
        assertTrue(QuestionReviewPolicy.validate("short", question(2).copy(options = emptyList())).isValid)
    }
}
