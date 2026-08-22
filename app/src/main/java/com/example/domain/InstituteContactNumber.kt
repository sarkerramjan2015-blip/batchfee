package com.batchfee.edu.domain

/** Canonical contact-number rules shared by institute registration and profile editing. */
object InstituteContactNumber {
    private val bangladeshMobile = Regex("^1[3-9]\\d{8}$")

    /** Accepts 01, 1, +8801 prefixes and common separators; stores +8801XXXXXXXXX. */
    fun normalizeBangladesh(value: String?): String? {
        var digits = value.orEmpty().filter(Char::isDigit)
        if (digits.startsWith("880")) digits = digits.drop(3)
        if (digits.startsWith("0")) digits = digits.drop(1)
        return digits.takeIf { bangladeshMobile.matches(it) }?.let { "+880$it" }
    }

    /** Phone is authoritative when present; WhatsApp safely fills legacy blank phone records. */
    fun primary(phone: String?, whatsappNumber: String?): String? =
        normalizedOrLegacy(phone) ?: normalizedOrLegacy(whatsappNumber)

    /** Preserve an explicit legacy WhatsApp number, otherwise reuse the primary phone. */
    fun whatsapp(phone: String?, whatsappNumber: String?): String? =
        normalizedOrLegacy(whatsappNumber) ?: normalizedOrLegacy(phone)

    private fun normalizedOrLegacy(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalizeBangladesh(trimmed) ?: trimmed
    }
}
