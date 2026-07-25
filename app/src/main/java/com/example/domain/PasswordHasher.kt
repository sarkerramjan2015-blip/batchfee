package com.batchfee.edu.domain

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {
    private const val ITERATIONS = 10000
    private const val SALT_BYTES = 16
    private const val HASH_PREFIX = "SHA256:"

    fun hash(password: String): String {
        val salt = SecureRandom().apply { nextBytes(ByteArray(SALT_BYTES)) }
            .let { bytesToHex(ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }) }
        val hash = sha256("$salt$password")
        return "$HASH_PREFIX$salt:$hash"
    }

    fun verify(password: String, storedHash: String): Boolean {
        if (!storedHash.startsWith(HASH_PREFIX)) return false
        val parts = storedHash.removePrefix(HASH_PREFIX).split(":", limit = 2)
        if (parts.size != 2) return false
        val salt = parts[0]
        val hash = parts[1]
        return sha256("$salt$password") == hash
    }

    fun isHashed(stored: String): Boolean = stored.startsWith(HASH_PREFIX)

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(input.toByteArray()))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789abcdef"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
        }
        return String(hexChars)
    }
}

