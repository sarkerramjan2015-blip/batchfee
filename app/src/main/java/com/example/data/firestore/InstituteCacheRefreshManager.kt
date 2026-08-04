package com.batchfee.edu.data.firestore

import android.os.SystemClock
import android.util.Log
import com.batchfee.edu.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object InstituteCacheRefreshManager {
    private const val TAG = "BatchFeeDataLoad"
    private const val DEFAULT_MIN_INTERVAL_MS = 30_000L
    private const val BACKGROUND_FULL_REFRESH_INTERVAL_MS = 120_000L
    private val lastRefreshAtMs = mutableMapOf<String, Long>()
    private val lastScopedRefreshAtMs = mutableMapOf<Pair<String, InstituteRefreshScope>, Long>()
    private val inFlightScopedRefreshes = mutableSetOf<Pair<String, InstituteRefreshScope>>()
    private val refreshMutex = Mutex()
    // Long-running Firestore reads must never hold a Compose screen on a loading state.
    // This process-lifetime scope is intentionally independent from an individual screen.
    private val backgroundRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts a normal cache refresh without making the caller wait for Firebase. Room remains
     * the source rendered by the UI and its flows update when this refresh completes.
     */
    fun refreshIfStaleInBackground(
        db: AppDatabase,
        instituteId: String,
        minIntervalMs: Long = BACKGROUND_FULL_REFRESH_INTERVAL_MS
    ) {
        if (instituteId.isBlank()) return
        backgroundRefreshScope.launch {
            refreshIfStale(db, instituteId, minIntervalMs)
        }
    }

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
            val startedAt = SystemClock.elapsedRealtime()
            CoreDataSyncCoordinator.refreshInstituteCache(db, instituteId)
            markAllScopesRefreshed(instituteId)
            Log.d(TAG, "full_refresh institute=${instituteId} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    suspend fun forceRefresh(db: AppDatabase, instituteId: String) {
        if (instituteId.isBlank()) return
        refreshMutex.withLock {
            lastRefreshAtMs[instituteId] = System.currentTimeMillis()
        }
        val startedAt = SystemClock.elapsedRealtime()
        CoreDataSyncCoordinator.refreshInstituteCache(db, instituteId)
        markAllScopesRefreshed(instituteId)
        Log.d(TAG, "forced_full_refresh institute=${instituteId} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    suspend fun refreshScopeIfStale(
        db: AppDatabase,
        instituteId: String,
        scope: InstituteRefreshScope,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
    ) {
        if (instituteId.isBlank()) return
        val key = instituteId to scope
        val shouldRefresh = refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastScopedRefreshAtMs[key] ?: 0L
            when {
                key in inFlightScopedRefreshes -> false
                now - last < minIntervalMs -> false
                else -> {
                    inFlightScopedRefreshes += key
                    true
                }
            }
        }
        if (!shouldRefresh) return

        val startedAt = SystemClock.elapsedRealtime()
        try {
            CoreDataSyncCoordinator.refreshScope(db, instituteId, scope)
            refreshMutex.withLock {
                lastScopedRefreshAtMs[key] = System.currentTimeMillis()
            }
            Log.d(
                TAG,
                "scoped_refresh scope=${scope} institute=${instituteId} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
        } finally {
            refreshMutex.withLock {
                inFlightScopedRefreshes -= key
            }
        }
    }

    /**
     * Safe after bootstrap: a full bootstrap marks these scopes fresh, so this avoids duplicate
     * reads while still warming a session that reaches Dashboard with an existing local cache.
     */
    suspend fun prefetchHighUseData(db: AppDatabase, instituteId: String) {
        refreshScopeIfStale(db, instituteId, InstituteRefreshScope.STUDENTS)
        refreshScopeIfStale(db, instituteId, InstituteRefreshScope.BATCHES)
        refreshScopeIfStale(db, instituteId, InstituteRefreshScope.STAFF)
    }

    private suspend fun markAllScopesRefreshed(instituteId: String) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            InstituteRefreshScope.entries.forEach { scope ->
                lastScopedRefreshAtMs[instituteId to scope] = now
            }
        }
    }
}

