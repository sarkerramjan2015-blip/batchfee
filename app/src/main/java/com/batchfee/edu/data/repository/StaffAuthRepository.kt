package com.batchfee.edu.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

class StaffAuthRepository {
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)
    private val auth = FirebaseAuth.getInstance()

    suspend fun signIn(staffCode: String, password: String): String {
        val result = functions.getHttpsCallable("loginStaff").call(
            mapOf("staffCode" to staffCode.trim(), "password" to password)
        ).await().data as? Map<*, *>
        val token = result?.get("customToken") as? String
            ?: throw IllegalStateException("Staff login service returned an invalid response.")
        return auth.signInWithCustomToken(token).await().user?.uid
            ?: throw IllegalStateException("Staff authentication did not return an account.")
    }

    fun userMessage(error: Throwable): String {
        val functionsError = error as? FirebaseFunctionsException
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.NOT_FOUND -> "Invalid Staff ID or password."
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                "Too many login attempts. Please wait a few minutes and try again."
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                "Staff login service is temporarily unavailable. Please try again."
            else -> functionsError?.message?.takeIf { it.isNotBlank() }
                ?: "Staff login failed. Please try again."
        }
    }
}
