package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTemplateStoreTest {
    @Test
    fun applySubstitutesAllPlaceholders() {
        val template = """
            Dear Guardian,

            {studentName} ({studentCode}) was absent from {batchName} on {date}.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent()

        val result = MessageTemplateStore.apply(
            template,
            mapOf(
                "guardianName" to "Guardian",
                "studentName" to "Rahim",
                "studentCode" to "S101",
                "batchName" to "HSC ICT",
                "date" to "03 Sep 2026",
                "instituteName" to "ABC Coaching",
                "instituteContact" to "+8801712345678"
            )
        )

        assertTrue(result.contains("Dear Guardian,"))
        assertTrue(result.contains("Rahim (S101)"))
        assertTrue(result.contains("HSC ICT"))
        assertTrue(result.contains("- ABC Coaching"))
        assertTrue(result.contains("Contact: +8801712345678"))
    }

    @Test
    fun applyDropsEmptyLines() {
        val template = """
            Dear Guardian,

            {studentName} result is published.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent()

        val result = MessageTemplateStore.apply(
            template,
            mapOf(
                "studentName" to "Rahim",
                "instituteName" to "ABC Coaching",
                "instituteContact" to ""
            )
        )

        assertFalse(result.contains("Contact:", ignoreCase = false))
        assertFalse(result.contains("\n\n\n"))
        assertTrue(result.contains("- ABC Coaching"))
    }

    @Test
    fun allTypeDefaultsFollowDearGuardianStyle() {
        val types = listOf(
            MessageTemplateStore.TYPE_ATTENDANCE_ABSENT,
            MessageTemplateStore.TYPE_DUE_FEE,
            MessageTemplateStore.TYPE_BIRTHDAY,
            MessageTemplateStore.TYPE_PAYMENT_CONFIRMATION,
            MessageTemplateStore.TYPE_ENQUIRY_FOLLOW_UP,
            MessageTemplateStore.TYPE_RESULT,
            MessageTemplateStore.TYPE_WELCOME
        )
        types.forEach { type ->
            val template = MessageTemplateStore.defaultFor(type)
            requireNotNull(template) { "Missing default for $type" }
            val lower = template.lowercase()
            assertTrue("$type must start with Dear Guardian: $template", lower.startsWith("dear guardian"))
            assertTrue("$type must have contact line", lower.contains("{institutecontact}") || lower.contains("contact:"))
        }
    }
}
