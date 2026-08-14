package com.batchfee.edu

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.media.SecureMediaInterceptor
import com.batchfee.edu.domain.ThemePreferences
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StudentSessionManager
import com.batchfee.edu.security.AppCheckProviderInstaller
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BatchFeeApp : Application(), ImageLoaderFactory {
    val applicationScope = CoroutineScope(SupervisorJob())
    
    val database by lazy { AppDatabase.getDatabase(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.init(this)
        FirebaseApp.initializeApp(this)
        AppCheckProviderInstaller.install()
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)

        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
        }

        SessionManager.initialize(this)
        StudentSessionManager.initialize(this)
        applicationScope.launch {
            StudentSessionManager.restoreFromFirebase()
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

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SecureMediaInterceptor()) }
        .build()
}
