package com.batchfee.edu.data.firestore

import android.os.SystemClock
import android.util.Log
import com.batchfee.edu.data.database.AppDatabase
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the small set of owner-facing, high-use data fresh while an institute
 * session is active. Firestore listeners only signal a change; Room remains
 * the UI source of truth, so a network delay never blocks a Compose screen.
 */
object InstituteRealtimeSyncManager {
    private const val TAG = "BatchFeeRealtime"
    private const val DEBOUNCE_MS = 350L

    private enum class RefreshTarget {
        INSTITUTE,
        STUDENTS,
        BATCH_STRUCTURE,
        STAFF,
        FINANCE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val registrations = mutableListOf<ListenerRegistration>()
    private val pendingRefreshes = mutableMapOf<RefreshTarget, Job>()

    private var activeInstituteId: String? = null
    private var activeDatabase: AppDatabase? = null

    /** Safe to call repeatedly for the same signed-in institute. */
    fun start(db: AppDatabase, instituteId: String) {
        if (instituteId.isBlank()) return
        scope.launch {
            mutex.withLock {
                if (activeInstituteId == instituteId && activeDatabase === db) return@withLock

                stopLocked()
                activeInstituteId = instituteId
                activeDatabase = db

                val institute = FirebaseFirestore.getInstance()
                    .collection("institutes")
                    .document(instituteId)

                registrations += institute.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        FirebaseCrashlytics.getInstance().recordException(error)
                        return@addSnapshotListener
                    }
                    // Room and Firestore disk cache already cover this state. Waiting for the
                    // server-confirmed event avoids an identical full sync at every startup.
                    if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
                    queueRefresh(instituteId, RefreshTarget.INSTITUTE)
                }
                listenToCollection(instituteId, "students", RefreshTarget.STUDENTS)
                listenToCollection(instituteId, "batches", RefreshTarget.BATCH_STRUCTURE)
                listenToCollection(instituteId, "batch_students", RefreshTarget.BATCH_STRUCTURE)
                listenToCollection(instituteId, "staffs", RefreshTarget.STAFF)

                // One coalesced finance refresh keeps dashboard totals and receipts aligned
                // without creating a listener for every screen in the app.
                listOf("fees", "payments", "receipts", "payment_reversals", "expenses").forEach { collection ->
                    listenToCollection(instituteId, collection, RefreshTarget.FINANCE)
                }
                Log.d(TAG, "started institute=$instituteId")
            }
        }
    }

    /** Removes Firestore listeners and pending work at logout or institute switch. */
    fun stop(instituteId: String? = null) {
        scope.launch {
            mutex.withLock {
                if (instituteId == null || activeInstituteId == instituteId) stopLocked()
            }
        }
    }

    private fun listenToCollection(
        instituteId: String,
        collection: String,
        target: RefreshTarget
    ) {
        val registration = FirebaseFirestore.getInstance()
            .collection("institutes")
            .document(instituteId)
            .collection(collection)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    return@addSnapshotListener
                }
                if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
                queueRefresh(instituteId, target)
            }
        registrations += registration
    }

    private fun queueRefresh(instituteId: String, target: RefreshTarget) {
        scope.launch {
            mutex.withLock {
                if (activeInstituteId != instituteId || activeDatabase == null) return@withLock
                pendingRefreshes.remove(target)?.cancel()
                pendingRefreshes[target] = scope.launch {
                    delay(DEBOUNCE_MS)
                    val db = mutex.withLock {
                        if (activeInstituteId == instituteId) activeDatabase else null
                    } ?: return@launch

                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        when (target) {
                            RefreshTarget.INSTITUTE ->
                                InstituteSyncHelper.syncInstituteFromFirestore(db, instituteId)

                            RefreshTarget.STUDENTS ->
                                InstituteCacheRefreshManager.refreshScopeIfStale(
                                    db, instituteId, InstituteRefreshScope.STUDENTS, minIntervalMs = 0L
                                )

                            RefreshTarget.BATCH_STRUCTURE -> {
                                InstituteCacheRefreshManager.refreshScopeIfStale(
                                    db, instituteId, InstituteRefreshScope.BATCHES, minIntervalMs = 0L
                                )
                                InstituteCacheRefreshManager.refreshScopeIfStale(
                                    db, instituteId, InstituteRefreshScope.ENROLLMENTS, minIntervalMs = 0L
                                )
                            }

                            RefreshTarget.STAFF ->
                                InstituteCacheRefreshManager.refreshScopeIfStale(
                                    db, instituteId, InstituteRefreshScope.STAFF, minIntervalMs = 0L
                                )

                            RefreshTarget.FINANCE -> {
                                InstituteCacheRefreshManager.refreshScopeIfStale(
                                    db, instituteId, InstituteRefreshScope.FINANCE, minIntervalMs = 0L
                                )
                                InstituteCacheRefreshManager.refreshScopeIfStale(
                                    db, instituteId, InstituteRefreshScope.EXPENSES, minIntervalMs = 0L
                                )
                            }
                        }
                        Log.d(
                            TAG,
                            "refreshed target=$target institute=$instituteId " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                    } catch (error: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(error)
                    }
                }
            }
        }
    }

    private fun stopLocked() {
        registrations.forEach { it.remove() }
        registrations.clear()
        pendingRefreshes.values.forEach { it.cancel() }
        pendingRefreshes.clear()
        activeInstituteId = null
        activeDatabase = null
        Log.d(TAG, "stopped")
    }
}
