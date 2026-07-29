package com.batchfee.edu.domain

data class ExamMarkOutcome(val percentage: Double, val passed: Boolean, val grade: String)

data class ExamReviewSummary(
    val totalStudents: Int,
    val marksEntered: Int,
    val missingMarks: Int,
    val absentStudents: Int,
    val passedStudents: Int,
    val failedStudents: Int
)

/** Small, deterministic rules used by both draft entry and publish review. */
object ExamResultRules {
    const val DRAFT = "draft"
    const val MARKS_PENDING = "marks_pending"
    const val READY_TO_PUBLISH = "ready_to_publish"
    const val PUBLISHED = "published"
    const val ARCHIVED = "archived"

    val examTypes = listOf("Class Test", "Weekly", "Monthly", "Half-Yearly", "Final", "Other")

    fun validateMark(mark: Double, total: Double): Boolean =
        mark >= 0.0 && mark <= total && total > 0.0

    fun outcome(mark: Double, total: Double, pass: Double, isAbsent: Boolean = false): ExamMarkOutcome {
        if (isAbsent) return ExamMarkOutcome(percentage = 0.0, passed = false, grade = "Absent")
        val percentage = if (total > 0) mark / total * 100 else 0.0
        val passed = mark >= pass
        val grade = if (!passed) "F" else when {
            percentage >= 80 -> "A+"
            percentage >= 70 -> "A"
            percentage >= 60 -> "A-"
            percentage >= 50 -> "B"
            percentage >= 40 -> "C"
            else -> "D"
        }
        return ExamMarkOutcome(percentage, passed, grade)
    }

    /** Competition ranking: tied marks share a rank and the next rank is skipped. */
    fun ranksDescending(marks: List<Double>): List<Int> {
        var rank = 0
        var previous: Double? = null
        return marks.mapIndexed { index, mark ->
            if (previous == null || mark != previous) rank = index + 1
            previous = mark
            rank
        }
    }

    fun review(
        totalStudents: Int,
        entries: List<ExamMarkOutcome?>,
        absentFlags: List<Boolean>
    ): ExamReviewSummary {
        val entered = entries.count { it != null }
        val absent = absentFlags.count { it }
        val completed = entries.filterNotNull()
        return ExamReviewSummary(
            totalStudents = totalStudents,
            marksEntered = entered,
            missingMarks = (totalStudents - entered).coerceAtLeast(0),
            absentStudents = absent,
            passedStudents = completed.count { it.passed },
            failedStudents = completed.count { !it.passed }
        )
    }

    fun statusForReview(summary: ExamReviewSummary): String = when {
        summary.marksEntered == 0 -> DRAFT
        summary.missingMarks > 0 -> MARKS_PENDING
        else -> READY_TO_PUBLISH
    }

    fun canPublish(expectedStudents: Int, enteredResults: Int): Boolean =
        expectedStudents > 0 && expectedStudents == enteredResults

    fun canEditResults(status: String): Boolean = status !in setOf(PUBLISHED, ARCHIVED)
}
