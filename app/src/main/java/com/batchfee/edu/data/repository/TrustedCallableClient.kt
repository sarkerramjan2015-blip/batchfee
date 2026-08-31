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
): Any? {
    suspend fun invoke() = functions.getHttpsCallable(functionName).call(payload).await().data
    return try {
        invoke()
    } catch (error: FirebaseFunctionsException) {
        if (error.code != FirebaseFunctionsException.Code.UNAUTHENTICATED) throw error
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() ?: throw error
        invoke()
    }
}
