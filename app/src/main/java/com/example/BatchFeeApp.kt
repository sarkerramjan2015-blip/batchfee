package com.example

import android.app.Application
import com.example.data.database.AppDatabase
import com.example.domain.ThemePreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BatchFeeApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())
    
    val database by lazy { AppDatabase.getDatabase(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.init(this)

        FirebaseApp.initializeApp(this)

        FirebaseAppCheck.getInstance().apply {
            installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }

        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
        }

        applicationScope.launch {
            AppDatabase.ensureDemoDataSeeded(database)
        }
    }
}
