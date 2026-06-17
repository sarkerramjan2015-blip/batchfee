package com.example.data.firestore

import com.example.data.database.AppDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object InstituteCacheRefreshManager {
    private const val DEFAULT_MIN_INTERVAL_MS = 30_000L
    private val lastRefreshAtMs = mutableMapOf<String, Long>()
    private val refreshMutex = Mutex()

    suspend fun refreshIfStale(
        db: AppDatabase,
        instituteId: String,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
    ) {
        if (instituteId.isBlank()) return
        val shouldRefresh = refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastRefreshAtMs[instituteId] ?: 0L
            if (now - last < minIntervalMs) {
                false
            } else {
                lastRefreshAtMs[instituteId] = now
                true
            }
        }
        if (shouldRefresh) {
            CoreDataSyncCoordinator.refreshInstituteCache(db, instituteId)
        }
    }

    suspend fun forceRefresh(db: AppDatabase, instituteId: String) {
        if (instituteId.isBlank()) return
        refreshMutex.withLock {
            lastRefreshAtMs[instituteId] = System.currentTimeMillis()
        }
        CoreDataSyncCoordinator.refreshInstituteCache(db, instituteId)
    }
}
