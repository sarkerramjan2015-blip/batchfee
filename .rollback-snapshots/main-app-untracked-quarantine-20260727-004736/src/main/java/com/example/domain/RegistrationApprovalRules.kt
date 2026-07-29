package com.batchfee.edu.domain

object RegistrationApprovalRules {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"

    fun normalizePhone(value: String?): String {
        val digits = value.orEmpty().filter(Char::isDigit)
        return if (digits.length == 11 && digits.startsWith("0")) "880${digits.drop(1)}" else digits
    }

    fun isValidSubmission(name: String?, phone: String?): Boolean =
        name?.trim()?.isNotEmpty() == true && normalizePhone(phone).length >= 7

    fun instituteDisplayName(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun isDuplicatePhone(phone: String?, knownPhones: Collection<String>): Boolean {
        val normalized = normalizePhone(phone)
        return normalized.isNotEmpty() && knownPhones.any { normalizePhone(it) == normalized }
    }

    fun canApprove(status: String): Boolean = status == PENDING
}
