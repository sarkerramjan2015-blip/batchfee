package com.batchfee.edu.domain

import java.security.SecureRandom
import java.util.Locale

/**
 * Generates a short, readable Student ID candidate. The trusted backend is the
 * final authority and claims the normalized ID transactionally, so random or
 * manually-entered IDs can never create duplicate student records.
 */
object StudentIdGenerator {
    private val secureRandom = SecureRandom()
    private val validPattern = Regex("^[A-Z0-9]+(?:-[A-Z0-9]+)*$")

    fun generate(): String = "ST-%06d".format(Locale.US, secureRandom.nextInt(900_000) + 100_000)

    fun normalize(value: String): String = value
        .trim()
        .uppercase(Locale.US)

    fun isValid(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.length in 3..20 && validPattern.matches(normalized)
    }
}
