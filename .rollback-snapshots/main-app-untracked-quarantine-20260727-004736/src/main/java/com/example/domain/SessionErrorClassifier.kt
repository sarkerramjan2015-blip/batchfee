package com.batchfee.edu.domain

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException

enum class SessionFailureKind {
    SESSION_INVALID,
    PERMISSION_DENIED,
    TRANSIENT_OR_UNKNOWN
}

object SessionErrorClassifier {
    fun classifyFirestore(code: FirebaseFirestoreException.Code): SessionFailureKind =
        classifyFirestoreCode(code.name)

    /** Kept SDK-free so the session policy can be unit-tested on the JVM. */
    fun classifyFirestoreCode(code: String): SessionFailureKind = when (code) {
        "UNAUTHENTICATED" -> SessionFailureKind.SESSION_INVALID
        "PERMISSION_DENIED" -> SessionFailureKind.PERMISSION_DENIED
        else -> SessionFailureKind.TRANSIENT_OR_UNKNOWN
    }

    fun classify(error: Throwable): SessionFailureKind {
        val firestoreError = error.findCause<FirebaseFirestoreException>()
        if (firestoreError != null) return classifyFirestore(firestoreError.code)

        val authError = error.findCause<FirebaseAuthException>()
        if (authError != null && authError.errorCode in invalidAuthErrorCodes) {
            return SessionFailureKind.SESSION_INVALID
        }

        return SessionFailureKind.TRANSIENT_OR_UNKNOWN
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private val invalidAuthErrorCodes = setOf(
        "ERROR_USER_DISABLED",
        "ERROR_USER_NOT_FOUND",
        "ERROR_USER_TOKEN_EXPIRED",
        "ERROR_INVALID_USER_TOKEN",
        "ERROR_REQUIRES_RECENT_LOGIN"
    )
}
