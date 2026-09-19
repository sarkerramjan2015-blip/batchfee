package com.batchfee.edu.data.repository

/** A local, teacher-editable copy of one AI preview. The server validates it again on finalization. */
data class ReviewableQuestion(
    val sourceQuestionId: String,
    val selected: Boolean = true,
    val questionText: String,
    val options: List<String>,
    val correctAnswer: String,
    val explanation: String,
    val difficulty: String,
    val marks: Int,
)

data class ReviewValidation(
    val isValid: Boolean,
    val message: String? = null,
)

object QuestionReviewPolicy {
    // These are the proposed Phase-3 rates, represented as integer poisha.
    // A client quote is informational only; a future billing callable must quote and debit itself.
    fun unitPricePoisha(questionType: String): Int = when (questionType.lowercase()) {
        "mcq" -> 25
        "short" -> 50
        "creative" -> 75
        else -> 0
    }

    fun totalPricePoisha(questionType: String, questions: List<ReviewableQuestion>): Int =
        unitPricePoisha(questionType) * questions.count { it.selected }

    fun validate(questionType: String, question: ReviewableQuestion): ReviewValidation {
        if (question.questionText.trim().isEmpty()) return ReviewValidation(false, "Question text is required.")
        if (question.questionText.length > 8_000) return ReviewValidation(false, "Question text is too long.")
        if (question.correctAnswer.trim().isEmpty()) return ReviewValidation(false, "A model answer is required.")
        if (question.correctAnswer.length > 2_000) return ReviewValidation(false, "The answer is too long.")
        if (question.explanation.length > 4_000) return ReviewValidation(false, "The explanation is too long.")
        if (question.difficulty !in setOf("easy", "medium", "hard")) {
            return ReviewValidation(false, "Choose easy, medium, or hard.")
        }
        if (question.marks !in 1..100) return ReviewValidation(false, "Marks must be between 1 and 100.")
        if (questionType == "mcq") {
            if (question.options.size != 4 || question.options.any { it.trim().isEmpty() }) {
                return ReviewValidation(false, "An MCQ needs four non-empty options.")
            }
            if (question.options.map { it.trim() }.toSet().size != 4) {
                return ReviewValidation(false, "MCQ options must be different.")
            }
            if (question.options.none { it.trim() == question.correctAnswer.trim() }) {
                return ReviewValidation(false, "The MCQ answer must exactly match an option.")
            }
        } else if (question.options.isNotEmpty()) {
            return ReviewValidation(false, "Only MCQs can contain options.")
        }
        return ReviewValidation(true)
    }
}

fun GeneratedQuestionPreview.toReviewable(index: Int): ReviewableQuestion = ReviewableQuestion(
    sourceQuestionId = sourceQuestionId.ifBlank { "generated_${(index + 1).toString().padStart(2, '0')}" },
    questionText = questionText,
    options = if (options.isEmpty()) emptyList() else options.take(4),
    correctAnswer = correctAnswer,
    explanation = explanation,
    difficulty = difficulty.lowercase().ifBlank { "medium" },
    marks = marks.coerceIn(1, 100),
)
