package com.batchfee.edu.domain

import com.batchfee.edu.data.models.EnquiryEntity

enum class FollowUpState { OVERDUE, TODAY, UPCOMING, NONE }

data class EnquiryStudentPrefill(
    val name: String,
    val phone: String,
    val address: String?,
    val notes: String
)

object EnquiryFollowUpSupport {
    fun followUpState(followUpAtMs: Long?, startOfTodayMs: Long, startOfTomorrowMs: Long): FollowUpState = when {
        followUpAtMs == null -> FollowUpState.NONE
        followUpAtMs < startOfTodayMs -> FollowUpState.OVERDUE
        followUpAtMs < startOfTomorrowMs -> FollowUpState.TODAY
        else -> FollowUpState.UPCOMING
    }

    fun normalizedPhone(phone: String): String = phone.filter(Char::isDigit).let {
        when {
            it.startsWith("880") -> "+$it"
            it.startsWith("01") && it.length == 11 -> "+880${it.drop(1)}"
            else -> if (phone.trim().startsWith("+")) "+$it" else it
        }
    }

    fun studentPrefill(enquiry: EnquiryEntity): EnquiryStudentPrefill = EnquiryStudentPrefill(
        name = enquiry.name,
        phone = enquiry.phone,
        address = enquiry.address,
        notes = listOfNotNull(enquiry.subjectName.takeIf { it.isNotBlank() }?.let { "Enquiry subject: $it" }, enquiry.followUpNote).joinToString("\n")
    )
}
