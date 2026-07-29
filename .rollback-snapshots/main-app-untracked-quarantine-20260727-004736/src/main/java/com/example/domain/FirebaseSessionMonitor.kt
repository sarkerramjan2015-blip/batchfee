package com.batchfee.edu.domain

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.StaffSyncHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one place that turns confirmed Firebase authentication invalidation into
 * the app's session-expired state. Firebase continues to own token refresh.
 */
class FirebaseSessionMonitor(
    private val auth: FirebaseAuth,
    private val scope: CoroutineScope,
    private val db: AppDatabase
) {
    private val validationInProgress = AtomicBoolean(false)
    private val restorationInProgress = AtomicBoolean(false)

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        if (SessionManager.isLoggedIn() && firebaseAuth.currentUser == null) {
            expireSession()
        }
    }

    private val idTokenListener = FirebaseAuth.IdTokenListener { firebaseAuth ->
        if (SessionManager.isLoggedIn() && firebaseAuth.currentUser == null) {
            expireSession()
        }
    }

    fun start() {
        auth.addAuthStateListener(authStateListener)
        auth.addIdTokenListener(idTokenListener)
        if (SessionManager.isLoggedIn() && auth.currentUser == null) {
            expireSession()
        }
    }

    fun stop() {
        auth.removeAuthStateListener(authStateListener)
        auth.removeIdTokenListener(idTokenListener)
    }

    fun validateOnResume() {
        if (!SessionManager.isLoggedIn() || !validationInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                val user = auth.currentUser
                if (user == null) {
                    expireSession()
                    return@launch
                }

                // Firebase refreshes an expired ID token when required. Do not
                // force refresh or store token material in this application.
                user.getIdToken(false).await()
                user.reload().await()
                if (auth.currentUser == null || isCurrentStaffInactive()) expireSession()
            } catch (error: Throwable) {
                if (SessionErrorClassifier.classify(error) == SessionFailureKind.SESSION_INVALID) {
                    expireSession()
                } else {
                    // Network and permission/server failures are not logout events.
                    FirebaseCrashlytics.getInstance().recordException(error)
                }
            } finally {
                validationInProgress.set(false)
            }
        }
    }

    /**
     * Firebase persists its current user across process restarts. Restore only
     * the locally stored account metadata after Firebase validation succeeds.
     * A network failure may use the scoped local account; it is not expiry.
     */
    fun restoreSession() {
        if (!restorationInProgress.compareAndSet(false, true)) return
        SessionManager.beginRestoringSession()

        scope.launch {
            val firebaseUser = auth.currentUser
            if (firebaseUser == null) {
                SessionManager.logout()
                restorationInProgress.set(false)
                return@launch
            }

            try {
                firebaseUser.getIdToken(false).await()
                firebaseUser.reload().await()
                if (auth.currentUser?.uid != firebaseUser.uid) {
                    expireSession()
                } else if (!restoreLocalSession(firebaseUser.uid)) {
                    // A valid Firebase user without account metadata must use
                    // the normal login flow; it is not a fake authenticated UI.
                    SessionManager.logout()
                }
            } catch (error: Throwable) {
                if (SessionErrorClassifier.classify(error) == SessionFailureKind.SESSION_INVALID) {
                    expireSession()
                } else if (!restoreLocalSession(firebaseUser.uid)) {
                    // Do not claim an unknown account is authenticated while
                    // offline or while a server error is unresolved.
                    SessionManager.logout()
                    FirebaseCrashlytics.getInstance().recordException(error)
                }
            } finally {
                restorationInProgress.set(false)
            }
        }
    }

    fun logout() {
        val userId = SessionManager.currentUserId.value
        val instituteId = SessionManager.currentInstituteId.value
        if (userId != null) {
            scope.launch {
                StaffAuditLogger.record(
                    db = db,
                    instituteId = instituteId,
                    actorUserId = userId,
                    action = "logout",
                    module = "security",
                    description = "Signed out of the app"
                )
            }
        }
        SessionManager.logout()
        auth.signOut()
    }

    fun expireSession() {
        val userId = SessionManager.currentUserId.value
        val instituteId = SessionManager.currentInstituteId.value
        if (SessionManager.expireSession()) {
            if (userId != null) {
                scope.launch {
                    StaffAuditLogger.record(
                        db = db,
                        instituteId = instituteId,
                        actorUserId = userId,
                        action = "session_expired",
                        module = "security",
                        description = "Session expired or account access was revoked"
                    )
                }
            }
            auth.signOut()
        }
    }

    /**
     * Firebase credentials can remain valid after an owner archives a staff
     * profile.  A confirmed inactive local or Firestore profile is an app
     * access revocation, not a permission-denied response.
     */
    private suspend fun isCurrentStaffInactive(): Boolean {
        if (SessionManager.currentUserRole.value != "Staff") return false
        val userId = SessionManager.currentUserId.value ?: return false
        val instituteId = SessionManager.currentInstituteId.value ?: return false
        val localStaff = withContext(Dispatchers.IO) {
            db.staffDao().getStaffByIdOnce(userId, instituteId)
        }
        if (localStaff != null && (localStaff.archivedAtMs != null || localStaff.status != "active")) return true

        val remoteStaff = StaffSyncHelper.fetchStaffFromFirestore(instituteId, userId)
        return remoteStaff != null && (remoteStaff.status != "active" || remoteStaff.archivedAtMs != null)
    }

    private suspend fun restoreLocalSession(userId: String): Boolean = withContext(Dispatchers.IO) {
        if (auth.currentUser?.uid != userId) return@withContext false
        val user = db.userDao().getUserById(userId) ?: return@withContext false
        val instituteId = user.instituteId
        if (user.role != "SuperAdmin" && instituteId.isNullOrBlank()) return@withContext false
        val resolvedInstituteId = instituteId ?: return@withContext false

        val staffPermissions = if (user.role == "Staff") {
            val staff = db.staffDao().getStaffByIdOnce(userId, instituteId.orEmpty())
                ?: return@withContext false
            if (staff.archivedAtMs != null || staff.status != "active") return@withContext false
            // A locally cached profile is allowed for offline restoration,
            // but a confirmed remote deactivation wins as soon as it is
            // available. Fetch failures are intentionally not logout events.
            val remoteStaff = StaffSyncHelper.fetchStaffFromFirestore(resolvedInstituteId, userId)
            if (remoteStaff != null && (remoteStaff.archivedAtMs != null || remoteStaff.status != "active")) {
                return@withContext false
            }
            staff.permissions
        } else {
            null
        }

        if (auth.currentUser?.uid != userId) return@withContext false
        SessionManager.login(userId, instituteId, user.role, staffPermissions)
        true
    }
}
