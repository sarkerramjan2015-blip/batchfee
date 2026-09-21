package com.batchfee.edu.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicQuestionSubjectsTest {
    private val groups = listOf("Science", "Humanities", "Business Studies")

    @Test
    fun commonSubjectsAreNotRepeatedInsideGroups() {
        for (level in listOf("SSC", "HSC")) {
            val common = academicSubjects(level, "Common Subjects").toSet()
            assertTrue(common.contains("Bangla 1st Paper"))
            assertTrue(common.contains("Information & Communication Technology"))
            for (group in groups) {
                assertTrue(academicSubjects(level, group).intersect(common).isEmpty())
            }
            assertEquals(level, academicQuestionClassName(level, "Common Subjects"))
            assertEquals("$level - Science", academicQuestionClassName(level, "Science"))
        }
    }

    @Test
    fun hscSubjectsHaveBothPapersExceptIct() {
        for (category in groups + listOf("Common Subjects", "Other Subjects")) {
            val subjects = academicSubjects("HSC", category).toSet()
            assertFalse(subjects.isEmpty())
            subjects.filterNot { it == "Information & Communication Technology" }.forEach { subject ->
                val base = subject.removeSuffix(" 1st Paper").removeSuffix(" 2nd Paper")
                assertTrue("Missing 1st Paper for $base", "$base 1st Paper" in subjects)
                assertTrue("Missing 2nd Paper for $base", "$base 2nd Paper" in subjects)
            }
        }
    }

    @Test
    fun optionalSubjectsHaveOneSharedQuestionBankPath() {
        for (level in listOf("SSC", "HSC")) {
            assertEquals(level, academicQuestionClassName(level, "Other Subjects"))
            assertTrue(academicSubjects(level, "Other Subjects").isNotEmpty())
        }
    }
}
