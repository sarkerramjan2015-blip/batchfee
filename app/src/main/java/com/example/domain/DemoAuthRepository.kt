package com.example.domain

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

object DemoAuthRepository {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    suspend fun trackDemoLogin(demoAccountType: String) {
        try {
            val result = auth.signInAnonymously().await()
            val userId = result.user?.uid ?: return

            val data = mapOf(
                "guestUid" to userId,
                "demoAccountType" to demoAccountType,
                "createdAt" to Date(),
                "deviceModel" to android.os.Build.MODEL,
                "platform" to "android",
                "lastActiveAt" to Date()
            )

            firestore.collection("Demo_Visitors")
                .document(userId)
                .set(data)
                .await()
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
}
