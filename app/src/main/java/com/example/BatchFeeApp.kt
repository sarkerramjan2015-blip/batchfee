package com.example

import android.app.Application
import com.example.data.database.AppDatabase
import com.example.domain.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BatchFeeApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())
    
    val database by lazy { AppDatabase.getDatabase(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.init(this)
        applicationScope.launch {
            AppDatabase.ensureDemoDataSeeded(database)
        }
    }
}
