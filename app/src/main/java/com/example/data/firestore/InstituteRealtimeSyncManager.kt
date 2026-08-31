package com.batchfee.edu.data.firestore

import android.os.SystemClock
import android.util.Log
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps high-use institute data fresh while respecting the same subscription
 * and staff-permission boundaries as Firestore rules. Room remains the UI
 * source of truth, so an unavailable or denied listener never blocks a screen.
 */
object InstituteRealtimeSyncManager {
    private const val TAG = "BatchFeeRealtime"
    private const val DEBOUNCE_MS = 350L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val applyMutex = Mutex()
    private val commandVersion = AtomicLong(0L)
    private var instituteRegistration: ListenerRegistration? = null
    private val operationalRegistrations = mutableListOf<ListenerRegistration>()
    private var pendingInstituteRefresh: Job? = null

    private var activeInstituteId: String? = null
    private var activeDatabase: AppDatabase? = null
    private var activePlan = RealtimeListenerPlan()
    private var activeGeneration = 0L
    private var operationalListenersStarted = false

    /** Safe to call repeatedly when session role or staff permissions change. */
    fun start(
        db: AppDatabase,
        instituteId: String,
        role: String?,
        permissions: Set<String>
    ) {
        if (instituteId.isBlank()) return
        val plan = RealtimeListenerPolicy.forSession(role, permissions)
        if (!plan.listenInstitute) {
            stop(instituteId)
            return
        }
        val generation = commandVersion.incrementAndGet()

        scope.launch {
            mutex.withLock {
                if (generation != commandVersion.get()) return@withLock
                if (
                    activeInstituteId == instituteId &&
                    activeDatabase === db &&
                    activePlan == plan
                ) return@withLock

                stopLocked()
                activeInstituteId = instituteId
                activeDatabase = db
                activePlan = plan
                activeGeneration = generation

                val institute = FirebaseFirestore.getInstance()
                    .collection("institutes")
                    .document(instituteId)

                instituteRegistration = institute.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (activeGeneration != generation) return@addSnapshotListener
                    if (error != null) {
                        FirebaseFailureReporter.report(
                            error,
                            operation = "realtime institute listener",
                            permissionDeniedIsExpected = true
                        )
                        return@addSnapshotListener
                    }
                    // Never use a stale cached entitlement to open protected listeners.
                    if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
                    queueInstituteRefresh(instituteId, generation)
                    scope.launch {
                        mutex.withLock {
                            if (
                                activeInstituteId != instituteId ||
                                activeGeneration != generation
                            ) return@withLock
                            if (snapshot.hasActiveSubscription()) {
                                startOperationalListenersLocked(instituteId, generation)
                            } else {
                                stopOperationalListenersLocked()
                            }
                        }
                    }
                }
                Log.d(TAG, "waiting for entitlement institute=$instituteId")
            }
        }
    }

    /** Removes Firestore listeners and pending work at logout or institute switch. */
    fun stop(instituteId: String? = null) {
        val generation = commandVersion.incrementAndGet()
        scope.launch {
            mutex.withLock {
                if (generation != commandVersion.get()) return@withLock
                if (instituteId == null || activeInstituteId == instituteId) stopLocked()
            }
        }
    }

    private fun startOperationalListenersLocked(instituteId: String, generation: Long) {
        if (operationalListenersStarted) return
        operationalListenersStarted = true

        if (activePlan.listenStudents) {
            listenToCollection(instituteId, "students", generation)
        }
        if (activePlan.listenBatchStructure) {
            listenToCollection(instituteId, "batches", generation)
            listenToCollection(instituteId, "batch_students", generation)
        }
        if (activePlan.listenStaff) {
            listenToCollection(instituteId, "staffs", generation)
        }
        if (activePlan.listenFinance) {
            listOf("fees", "payments", "receipts", "payment_reversals").forEach { collection ->
                listenToCollection(instituteId, collection, generation)
            }
        }
        if (activePlan.listenExpenses) {
            listenToCollection(instituteId, "expenses", generation)
        }
        Log.d(TAG, "started protected listeners institute=$instituteId plan=$activePlan")
    }

    private fun listenToCollection(
        instituteId: String,
        collection: String,
        generation: Long
    ) {
        val registration = FirebaseFirestore.getInstance()
            .collection("institutes")
            .document(instituteId)
            .collection(collection)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (activeGeneration != generation) return@addSnapshotListener
                if (error != null) {
                    FirebaseFailureReporter.report(
                        error,
                        operation = "realtime $collection listener",
                        permissionDeniedIsExpected = true
                    )
                    return@addSnapshotListener
                }
                if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
                val changes = snapshot.getDocumentChanges(MetadataChanges.INCLUDE).toList()
                if (changes.isEmpty()) return@addSnapshotListener
                scope.launch {
                    applyMutex.withLock {
                        val db = mutex.withLock {
                            if (
                                activeInstituteId == instituteId &&
                                activeGeneration == generation
                            ) activeDatabase else null
                        } ?: return@withLock

                        val startedAt = SystemClock.elapsedRealtime()
                        try {
                            when (collection) {
                                "students" -> StudentSyncHelper.applyRealtimeChanges(
                                    db, instituteId, changes
                                )
                                "batches" -> BatchSyncHelper.applyRealtimeChanges(
                                    db, instituteId, changes
                                )
                                "batch_students" -> BatchStudentSyncHelper.applyRealtimeChanges(
                                    db, instituteId, changes
                                )
                                "staffs" -> StaffSyncHelper.applyRealtimeChanges(
                                    db, instituteId, changes
                                )
                                "fees", "payments", "receipts", "payment_reversals" ->
                                    FinanceSyncHelper.applyRealtimeChanges(
                                        db, instituteId, collection, changes
                                    )
                                "expenses" -> ExpenseSyncHelper.applyRealtimeChanges(
                                    db, instituteId, changes
                                )
                            }
                            Log.d(
                                TAG,
                                "applied ${changes.size} $collection change(s) " +
                                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            FirebaseFailureReporter.report(
                                error,
                                operation = "apply realtime $collection changes",
                                permissionDeniedIsExpected = true
                            )
                        }
                    }
                }
            }
        operationalRegistrations += registration
    }

    private fun queueInstituteRefresh(
        instituteId: String,
        generation: Long
    ) {
        scope.launch {
            mutex.withLock {
                if (
                    activeInstituteId != instituteId ||
                    activeDatabase == null ||
                    activeGeneration != generation
                ) return@withLock
                pendingInstituteRefresh?.cancel()
                pendingInstituteRefresh = scope.launch {
                    delay(DEBOUNCE_MS)
                    val db = mutex.withLock {
                        if (
                            activeInstituteId == instituteId &&
                            activeGeneration == generation
                        ) activeDatabase else null
                    } ?: return@launch

                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        InstituteSyncHelper.syncInstituteFromFirestore(db, instituteId)
                        Log.d(
                            TAG,
                            "refreshed institute=$instituteId " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        FirebaseFailureReporter.report(
                            error,
                            operation = "realtime institute refresh",
                            permissionDeniedIsExpected = true
                        )
                    }
                }
            }
        }
    }

    private fun DocumentSnapshot.hasActiveSubscription(): Boolean {
        val status = getString("subscriptionStatus")
        val periodEnd = getLong("currentPeriodEndMs")
        return getBoolean("isActive") == true &&
            periodEnd != null &&
            periodEnd > System.currentTimeMillis() &&
            status in setOf("trial", "active")
    }

    private fun stopOperationalListenersLocked() {
        operationalRegistrations.forEach { it.remove() }
        operationalRegistrations.clear()
        operationalListenersStarted = false
    }

    private fun stopLocked() {
        instituteRegistration?.remove()
        instituteRegistration = null
        stopOperationalListenersLocked()
        pendingInstituteRefresh?.cancel()
        pendingInstituteRefresh = null
        activeInstituteId = null
        activeDatabase = null
        activePlan = RealtimeListenerPlan()
        activeGeneration = 0L
        Log.d(TAG, "stopped")
    }
}
