package com.batchfee.edu.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * Calls an authenticated Firebase Function and repairs one stale-session
 * failure without changing the original payload. Callers must keep their
 * operation/entity IDs in that payload so the server can replay safely.
 */
internal suspend fun callTrustedFunction(
    functions: FirebaseFunctions,
    functionName: String,
    payload: Map<String, Any?>
): Any? = callTrustedFunctionOnce(
    refreshToken = {
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() != null
    },
    invoke = { functions.getHttpsCallable(functionName).call(payload).await().data }
)

/**
 * Exactly one forced token refresh followed by exactly one replay when the
 * server answers UNAUTHENTICATED. Any other failure is rethrown unchanged and
 * is never retried, so PERMISSION_DENIED cannot become a refresh loop.
 */
internal suspend fun callTrustedFunctionOnce(
    refreshToken: suspend () -> Boolean,
    invoke: suspend () -> Any?
): Any? {
    try {
        return invoke()
    } catch (error: FirebaseFunctionsException) {
        if (error.code != FirebaseFunctionsException.Code.UNAUTHENTICATED) throw error
        if (!refreshToken()) throw error
        return invoke()
    }
}
