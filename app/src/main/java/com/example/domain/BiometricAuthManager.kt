package com.example.domain

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthManager {
    private const val PREFS_NAME = "batchfee_biometric"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_INSTITUTE_ID = "institute_id"
    private const val KEY_ROLE = "role"
    private const val KEY_EMAIL = "email"

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

    data class SavedSession(
        val userId: String,
        val instituteId: String?,
        val role: String,
        val email: String?
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun savedSession(context: Context): SavedSession? {
        if (!isEnabled(context)) return null
        val userId = prefs(context).getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val role = prefs(context).getString(KEY_ROLE, null)?.takeIf { it.isNotBlank() } ?: return null
        val instituteId = prefs(context).getString(KEY_INSTITUTE_ID, null)?.takeIf { it.isNotBlank() }
        val email = prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
        return SavedSession(userId = userId, instituteId = instituteId, role = role, email = email)
    }

    fun canAuthenticate(context: Context): Boolean {
        return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun availabilityMessage(context: Context): String? {
        return when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "This device has no fingerprint sensor."
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Fingerprint sensor is not available right now."
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Add a fingerprint in phone settings first."
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "A security update is required for biometric login."
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Biometric login is not supported on this device."
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Biometric status is unknown. Try again."
            else -> "Biometric login is not available."
        }
    }

    fun enableForCurrentSession(context: Context, email: String? = null): String? {
        val userId = SessionManager.currentUserId.value ?: return "No active user session found."
        val role = SessionManager.currentUserRole.value ?: return "No active user role found."
        val instituteId = SessionManager.currentInstituteId.value
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_INSTITUTE_ID, instituteId.orEmpty())
            .putString(KEY_ROLE, role)
            .putString(KEY_EMAIL, email.orEmpty())
            .apply()
        return null
    }

    fun refreshCurrentSession(context: Context, email: String? = null) {
        if (isEnabled(context)) {
            enableForCurrentSession(context, email)
        }
    }

    fun disable(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun findFragmentActivity(context: Context): FragmentActivity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return current
            current = current.baseContext
        }
        return null
    }

    fun showPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Use password",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButtonText(negativeButtonText)
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    onError("Fingerprint not recognized. Try again.")
                }
            }
        )
        prompt.authenticate(promptInfo)
    }
}
