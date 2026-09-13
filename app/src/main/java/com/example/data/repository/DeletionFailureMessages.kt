package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctionsException

/**
 * Keeps server-side deletion protections intact while preventing Firebase's raw
 * transport codes from being shown to an institute owner.
 */
internal fun deletionFailureMessage(error: FirebaseFunctionsException, fallback: String): String =
    when (error.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED ->
            "Your sign-in session expired. Sign in again, then try once more."
        FirebaseFunctionsException.Code.PERMISSION_DENIED ->
            "This account is not allowed to change this archived record. Ask the institute owner to review access."
        FirebaseFunctionsException.Code.NOT_FOUND ->
            "This record is no longer available. Refresh the archive and try again."
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
            error.message?.takeIf { it.isNotBlank() }
                ?: "The subscription limit prevents restoring this record right now."
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
        FirebaseFunctionsException.Code.INTERNAL ->
            "The secure deletion service is temporarily unavailable. Check your connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
