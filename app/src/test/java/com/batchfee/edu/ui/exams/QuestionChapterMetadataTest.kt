package com.batchfee.edu.ui.exams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionChapterMetadataTest {
    @Test
    fun chapterNumberCreatesAStableQuestionBankKey() {
        assertEquals("Chapter 7", canonicalQuestionChapter("7"))
        assertEquals("", canonicalQuestionChapter("0"))
        assertEquals("", canonicalQuestionChapter("1000"))
    }

    @Test
    fun displayLabelKeepsTeacherTitleAndOptionalTopicSeparateFromTheKey() {
        assertEquals(
            "Chapter 7 · Motion · Uniform motion",
            displayQuestionChapter("Chapter 7", "Motion", "Uniform motion"),
        )
    }

    @Test
    fun banglaFirstPaperGetsOnlyHelpfulSectionSuggestions() {
        assertTrue(banglaFirstPaperSections("Bangla 1st Paper").contains("Prose (Gadya)"))
        assertTrue(banglaFirstPaperSections("Bangla 1st Paper").contains("Drama"))
        assertTrue(banglaFirstPaperSections("Bangla 2nd Paper").isEmpty())
    }
}
