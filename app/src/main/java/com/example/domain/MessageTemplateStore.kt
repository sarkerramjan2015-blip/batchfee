package com.example.domain

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.InstituteContactNumber

/**
 * Central message-template engine. Every outgoing message flow loads its
 * template from the institute's editable reminder_templates row (falling back
 * to a polished default) and substitutes placeholders here.
 *
 * All templates follow one style:
 *   Dear Guardian, + body + - InstituteName + Contact: number
 */
object MessageTemplateStore {
    const val TYPE_ATTENDANCE_ABSENT = "AttendanceAbsent"
    const val TYPE_DUE_FEE = "DueFee"
    const val TYPE_BIRTHDAY = "Birthday"
    const val TYPE_PAYMENT_CONFIRMATION = "PaymentConfirmation"
    const val TYPE_ENQUIRY_FOLLOW_UP = "EnquiryFollowUp"
    const val TYPE_RESULT = "ResultPublished"
    const val TYPE_WELCOME = "WelcomeMessage"

    private val defaults = mapOf(
        TYPE_ATTENDANCE_ABSENT to """
            Dear Guardian,

            {studentName} ({studentCode}) was absent from {batchName} on {date}.

            Please let us know the reason at your earliest convenience.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent(),
        TYPE_DUE_FEE to """
            Dear Guardian,

            {studentName} has a pending fee of BDT {amount} for {period}.

            Please pay at your earliest convenience.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent(),
        TYPE_BIRTHDAY to """
            Dear Guardian,

            Happy Birthday to {studentName}!

            Wishing a wonderful day and a great year ahead.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent(),
        TYPE_PAYMENT_CONFIRMATION to """
            Dear Guardian,

            Payment of BDT {amount} for {studentName} has been received for {period}.

            Thank you.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent(),
        TYPE_ENQUIRY_FOLLOW_UP to """
            Dear Guardian,

            Thank you for your enquiry at {instituteName}.

            We would love to welcome your child to our classes. Please contact us for details.

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent(),
        TYPE_RESULT to """
            Dear Guardian,

            {studentName}'s result for {examName} is published.

            Marks: {marks} | Grade: {grade} | Position: {position}

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent(),
        TYPE_WELCOME to """
            Dear Guardian,

            Welcome! {studentName} is now admitted to {instituteName}.

            Student ID: {studentCode}
            Class: {className}

            - {instituteName}
            Contact: {instituteContact}
        """.trimIndent()
    )

    fun defaultFor(type: String): String? = defaults[type]

    /** Loads the institute's saved template, falling back to the polished default. */
    suspend fun load(db: AppDatabase, instituteId: String?, type: String): String? {
        val stored = instituteId
            ?.let { db.reminderTemplateDao().getTemplateByTypeOnce(it, type) }
            ?.messageTemplate
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return stored ?: defaultFor(type)
    }

    /** Substitutes {placeholders}, then drops label lines whose values were empty. */
    fun apply(template: String, values: Map<String, String>): String {
        var out = template.trim()
        values.forEach { (key, value) -> out = out.replace("{$key}", value.orEmpty()) }
        return out.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val t = line.trim()
                t.isEmpty() || t == "-" || t.equals("Contact:", ignoreCase = true) ||
                    t.matches(Regex("^[A-Za-z ]+:\\s*$"))
            }
            .joinToString("\n")
    }

    /** Institute phone in +880 format, falling back to the stored phone string. */
    suspend fun loadInstituteContact(db: AppDatabase, instituteId: String?): String {
        val institute = instituteId?.let { db.instituteDao().getInstitute(it) } ?: return ""
        return InstituteContactNumber.primary(institute.phone, institute.whatsappNumber).orEmpty()
    }
}
