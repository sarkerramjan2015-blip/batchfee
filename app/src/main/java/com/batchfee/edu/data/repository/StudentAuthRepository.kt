package com.batchfee.edu.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class StudentLoginResult(
    val success: Boolean,
    val message: String = "",
    val firebaseUid: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val studentCode: String = "",
    val instituteId: String = "",
    val instituteCode: String = "",
    val sessionExpiresAtMs: Long = 0L
)

/** Student authentication through the non-public, server-validated login endpoint. */
class StudentAuthRepository {
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)

    suspend fun login(
        studentCodeInput: String,
        password: String
    ): StudentLoginResult = withContext(Dispatchers.IO) {
        try {
            val callableResult = functions.getHttpsCallable("loginStudent")
                .call(
                    mapOf(
                        "studentCode" to studentCodeInput.trim(),
                        "password" to password
                    )
                )
                .await()
            val data = callableResult.data as? Map<*, *>
                ?: return@withContext StudentLoginResult(false, "Login service returned an invalid response.")
            val customToken = data.string("customToken")
            val expectedUid = data.string("firebaseUid")
            val studentId = data.string("studentId")
            val instituteId = data.string("instituteId")
            val expiresAtMs = (data["sessionExpiresAtMs"] as? Number)?.toLong() ?: 0L
            if (customToken.isBlank() || expectedUid.isBlank() || studentId.isBlank() ||
                instituteId.isBlank() || expiresAtMs <= System.currentTimeMillis()) {
                return@withContext StudentLoginResult(false, "Login service returned an invalid response.")
            }

            val authResult = auth.signInWithCustomToken(customToken).await()
            if (authResult.user?.uid != expectedUid) {
                auth.signOut()
                return@withContext StudentLoginResult(false, "Student identity verification failed.")
            }
            val token = authResult.user?.getIdToken(true)?.await()
            val claims = token?.claims.orEmpty()
            val claimsValid = claims["student"] == true &&
                claims["studentId"] == studentId &&
                claims["instituteId"] == instituteId &&
                (claims["studentSessionExpiresAt"] as? Number)?.toLong() == expiresAtMs
            if (!claimsValid) {
                auth.signOut()
                return@withContext StudentLoginResult(false, "Student identity verification failed.")
            }

            StudentLoginResult(
                success = true,
                firebaseUid = expectedUid,
                studentId = studentId,
                studentName = data.string("studentName").ifBlank { "Student" },
                studentCode = data.string("studentCode"),
                instituteId = instituteId,
                instituteCode = data.string("instituteCode"),
                sessionExpiresAtMs = expiresAtMs
            )
        } catch (error: Exception) {
            StudentLoginResult(false, mapLoginError(error))
        }
    }

    private fun mapLoginError(error: Exception): String {
        val functionsError = error as? FirebaseFunctionsException
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.NOT_FOUND ->
                "Invalid student ID or password."
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                "Too many login attempts. Try again later."
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                "Login service is temporarily unavailable. Check your connection and try again."
            else -> "Student login failed. Please try again."
        }
    }

    fun logout() {
        auth.signOut()
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""
}
