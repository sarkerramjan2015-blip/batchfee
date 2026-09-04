package com.batchfee.edu.data.firebase

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException

/**
 * Keeps expected authentication, connectivity and access-state failures out of
 * Crashlytics issue counts while retaining a breadcrumb for a later real crash.
 */
object FirebaseFailureReporter {
    /**
     * Records a non-fatal only when it is genuinely unexpected. Cancellation,
     * auth, network and expected Firestore error codes are dropped so routine
     * coroutine teardown and transient failures don't inflate Crashlytics.
     */
    fun recordException(error: Throwable) {
        if (error is CancellationException) return
        if (isExpected(error, permissionDeniedIsExpected = false)) return
        FirebaseCrashlytics.getInstance().recordException(error)
    }

    fun report(
        error: Throwable,
        operation: String,
        permissionDeniedIsExpected: Boolean = false
    ) {
        if (error is CancellationException) return

        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("$operation failed (${error.javaClass.simpleName})")

        if (isExpected(error, permissionDeniedIsExpected)) return
        crashlytics.recordException(error)
    }

    private fun isExpected(error: Throwable, permissionDeniedIsExpected: Boolean): Boolean {
        if (error is FirebaseAuthException || error is FirebaseNetworkException) return true

        if (error is FirebaseFirestoreException) {
            return when (error.code) {
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.CANCELLED,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.UNAVAILABLE -> true

                FirebaseFirestoreException.Code.PERMISSION_DENIED -> permissionDeniedIsExpected
                else -> false
            }
        }

        return error.message?.contains("connection reset", ignoreCase = true) == true
    }
}
