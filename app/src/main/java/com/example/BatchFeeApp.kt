package com.batchfee.edu

import android.app.Application
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.ThemePreferences
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
        SessionManager.initialize(this)

        FirebaseApp.initializeApp(this)

        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)

        // TODO: RE-ENABLE APP CHECK BEFORE PLAY STORE UPLOAD
        // FirebaseAppCheck.getInstance().apply {
        //     installAppCheckProviderFactory(
        //         PlayIntegrityAppCheckProviderFactory.getInstance()
        //     )
        // }

        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
        }

        // Demo account provisioning makes multiple Auth and Firestore requests. It is useful
        // only to developers and must never compete with a real customer's startup/login.
        if (BuildConfig.DEBUG) {
            applicationScope.launch {
                if (database.userDao().getUserByEmail("superadmin@batchfee.app") == null) {
                    AppDatabase.ensureDemoDataSeeded(database)
                }
            }
        }
    }
}
