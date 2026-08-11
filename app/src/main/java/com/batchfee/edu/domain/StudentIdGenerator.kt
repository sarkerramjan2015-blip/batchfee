package com.batchfee.edu.domain

import java.util.Locale
import java.security.SecureRandom

/**
 * Generates a user-facing, globally scoped Student ID from 80 random bits.
 * The trusted account service also claims the normalized ID transactionally,
 * so a collision cannot create two accounts with the same login ID.
 */
object StudentIdGenerator {
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val randomBytes = ByteArray(10).also(secureRandom::nextBytes)
        val token = randomBytes
            .joinToString(separator = "") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
            .chunked(4)
            .joinToString("-")
        return "BF-$token"
    }
}
