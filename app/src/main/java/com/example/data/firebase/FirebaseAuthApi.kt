package com.example.data.firebase

import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit

object FirebaseAuthApi {
    private const val API_KEY = "AIzaSyD5Ksi9vr0jJjD5cKZ4okpEKmBgK2OVzTI"
    private const val SIGN_UP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY"
    private const val SIGN_IN_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class SignUpRequest(val email: String, val password: String, val returnSecureToken: Boolean = false)

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class SignUpResponse(val localId: String? = null, val idToken: String? = null)

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class FirebaseError(val error: ErrorDetail)

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class ErrorDetail(val code: Int? = null, val message: String = "Unknown error")

    class SignUpException(val firebaseMessage: String) : Exception(firebaseMessage)

    /**
     * Creates a Firebase Auth user via REST API.
     * This does NOT sign in the new user — the admin's session is unaffected.
     * Returns the new user's UID (localId).
     * Throws [SignUpException] on failure.
     */
    suspend fun createUser(email: String, password: String): String {
        return withContext(Dispatchers.IO) {
            val requestBody = moshi.adapter(SignUpRequest::class.java)
                .toJson(SignUpRequest(email = email, password = password))
            val request = Request.Builder()
                .url(SIGN_UP_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw SignUpException("Empty response from Firebase")
                val parsed = moshi.adapter(SignUpResponse::class.java).fromJson(body)
                parsed?.localId ?: throw SignUpException("No localId in Firebase response")
            } else {
                val errorBody = response.body?.string() ?: ""
                val error = try {
                    moshi.adapter(FirebaseError::class.java).fromJson(errorBody)
                } catch (_: Exception) { null }
                val message = error?.error?.message
                    ?.replace("_", " ")
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Unknown error (${response.code})"
                FirebaseCrashlytics.getInstance().log("Firebase signUp failed: $message")
                throw SignUpException(message)
            }
        }
    }

    /**
     * Signs in via REST API to fetch the real Firebase Auth UID without
     * affecting the SDK auth session. Returns the UID (localId).
     * Throws [SignUpException] on failure.
     */
    suspend fun signInWithPassword(email: String, password: String): String {
        return withContext(Dispatchers.IO) {
            val requestBody = moshi.adapter(SignUpRequest::class.java)
                .toJson(SignUpRequest(email = email, password = password, returnSecureToken = false))
            val request = Request.Builder()
                .url(SIGN_IN_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw SignUpException("Empty response from Firebase")
                val parsed = moshi.adapter(SignUpResponse::class.java).fromJson(body)
                parsed?.localId ?: throw SignUpException("No localId in Firebase response")
            } else {
                val errorBody = response.body?.string() ?: ""
                val error = try {
                    moshi.adapter(FirebaseError::class.java).fromJson(errorBody)
                } catch (_: Exception) { null }
                val message = error?.error?.message
                    ?.replace("_", " ")
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Unknown error (${response.code})"
                FirebaseCrashlytics.getInstance().log("Firebase signIn failed: $message")
                throw SignUpException(message)
            }
        }
    }
}
