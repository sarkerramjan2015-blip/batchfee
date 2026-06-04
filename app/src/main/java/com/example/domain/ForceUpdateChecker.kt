package com.example.domain

import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object ForceUpdateChecker {
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    sealed class UpdateResult {
        data object UpToDate : UpdateResult()
        data class UpdateRequired(val requiredVersion: Int) : UpdateResult()
        data object CheckFailed : UpdateResult()
    }

    suspend fun check(): UpdateResult {
        return try {
            val snapshot = firestore.collection("Config")
                .document("requiredVersionCode")
                .get()
                .await()

            val requiredVersion = snapshot.getLong("version")?.toInt() ?: return UpdateResult.UpToDate
            val currentVersion = BuildConfig.VERSION_CODE

            if (currentVersion < requiredVersion) {
                UpdateResult.UpdateRequired(requiredVersion)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UpdateResult.CheckFailed // offline or network error — allow entry
        }
    }
}
