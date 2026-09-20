package com.batchfee.edu.domain

import android.content.Context
import com.batchfee.edu.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object ReviewPromptPreferences {
    const val PREFS_NAME = "batchfee_review_prompt"
    private const val KEY_LAST_SHOWN_AT_MS = "last_shown_at_ms"
    private const val KEY_HAS_RATED = "has_rated"
    private const val KEY_DEBUG_FORCE_ELIGIBLE = "debug_force_eligible"
    private const val SERVER_COLLECTION = "app_ratings"

    const val MIN_AGE_MS = 30L * 24 * 60 * 60 * 1000
    const val RE_PROMPT_MS = 3L * 24 * 60 * 60 * 1000

    fun installedAtMs(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())

    fun shouldShow(context: Context, nowMs: Long): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HAS_RATED, false)) return false
        if (BuildConfig.DEBUG && prefs.getBoolean(KEY_DEBUG_FORCE_ELIGIBLE, false)) return true
        val ageMs = nowMs - installedAtMs(context)
        if (ageMs < MIN_AGE_MS) return false
        val lastShown = prefs.getLong(KEY_LAST_SHOWN_AT_MS, 0L)
        return lastShown == 0L || nowMs - lastShown >= RE_PROMPT_MS
    }

    fun markShown(context: Context, nowMs: Long) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SHOWN_AT_MS, nowMs).apply()
    }

    fun markRated(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAS_RATED, true).apply()
    }

    /** True when this signed-in user already posted a rating, so the prompt
     * stays dismissed even after a reinstall. Offline/errors return false and
     * fall back to the local flag. A confirmed server rating is cached locally. */
    suspend fun isRatedOnServer(context: Context): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        return try {
            val snapshot = FirebaseFirestore.getInstance()
                .collection(SERVER_COLLECTION).document(uid).get().await()
            if (snapshot.exists()) markRated(context)
            snapshot.exists()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun recordServerRating(stars: Int) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        runCatching {
            FirebaseFirestore.getInstance().collection(SERVER_COLLECTION).document(uid)
                .set(mapOf("ratedAtMs" to System.currentTimeMillis(), "stars" to stars))
                .await()
        }
    }
}
