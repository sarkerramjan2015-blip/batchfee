package com.batchfee.edu.data.firebase

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
    data class SignInWithTokenResponse(val localId: String? = null, val idToken: String? = null)

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class DeleteAccountRequest(val idToken: String)

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
     * Deletes a Firebase Auth account by email+password using REST API.
     * First signs in to obtain an idToken, then calls accounts:delete.
     * Returns true on success, false if the account was not found, throws on other errors.
     */
    suspend fun deleteUser(email: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = moshi.adapter(SignUpRequest::class.java)
                    .toJson(SignUpRequest(email = email, password = password, returnSecureToken = true))
                val signInRequest = Request.Builder()
                    .url(SIGN_IN_URL)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val signInResponse = client.newCall(signInRequest).execute()

                if (!signInResponse.isSuccessful) {
                    val errorBody = signInResponse.body?.string() ?: ""
                    val error = try {
                        moshi.adapter(FirebaseError::class.java).fromJson(errorBody)
                    } catch (_: Exception) { null }
                    val code = error?.error?.message ?: ""
                    if (code.contains("EMAIL_NOT_FOUND")) return@withContext false
                    throw SignUpException("Sign-in for deletion failed: ${code.replace("_", " ")}")
                }

                val signInBody = signInResponse.body?.string()
                    ?: throw SignUpException("Empty sign-in response")
                val signInParsed = moshi.adapter(SignInWithTokenResponse::class.java).fromJson(signInBody)
                val idToken = signInParsed?.idToken
                    ?: throw SignUpException("No idToken in sign-in response")

                val deleteUrl = "https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$API_KEY"
                val deleteBody = moshi.adapter(DeleteAccountRequest::class.java)
                    .toJson(DeleteAccountRequest(idToken = idToken))
                val deleteRequest = Request.Builder()
                    .url(deleteUrl)
                    .post(deleteBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val deleteResponse = client.newCall(deleteRequest).execute()

                if (!deleteResponse.isSuccessful) {
                    val errorBody = deleteResponse.body?.string() ?: ""
                    val error = try {
                        moshi.adapter(FirebaseError::class.java).fromJson(errorBody)
                    } catch (_: Exception) { null }
                    val msg = error?.error?.message?.replace("_", " ") ?: "Unknown"
                    if (msg.contains("USER NOT FOUND", ignoreCase = true)) return@withContext false
                    throw SignUpException("Delete failed: $msg")
                }
                true
            } catch (e: SignUpException) { throw e }
            catch (e: Exception) {
                FirebaseCrashlytics.getInstance().log("Firebase deleteUser failed: ${e.message}")
                false
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

    /** Generates a unique virtual email for a student account. */
    fun generateVirtualEmail(studentCode: String, instituteCode: String): String {
        val uniqueId = (1..9999).random().toString().padStart(4, '0')
        return "${studentCode.lowercase()}.${instituteCode.lowercase()}.$uniqueId@s.batchfee.app"
    }
}

