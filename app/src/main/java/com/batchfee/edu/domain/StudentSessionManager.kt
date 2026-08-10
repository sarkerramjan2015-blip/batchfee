package com.batchfee.edu.domain

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Student session state is kept in memory and restored only from Firebase Auth's
 * verified token plus the live UID-linked Firestore student record. No student
 * identity or session is persisted in application SharedPreferences.
 */
object StudentSessionManager {
    private const val LEGACY_PREFS = "batchfee_student_session_prefs"

    private val _studentId = MutableStateFlow<String?>(null)
    val studentId: StateFlow<String?> = _studentId.asStateFlow()

    private val _instituteId = MutableStateFlow<String?>(null)
    val instituteId: StateFlow<String?> = _instituteId.asStateFlow()

    private val _instituteCode = MutableStateFlow<String?>(null)
    val instituteCode: StateFlow<String?> = _instituteCode.asStateFlow()

    private val _studentName = MutableStateFlow<String?>(null)
    val studentName: StateFlow<String?> = _studentName.asStateFlow()

    private val _studentCode = MutableStateFlow<String?>(null)
    val studentCode: StateFlow<String?> = _studentCode.asStateFlow()

    private val _sessionExpiresAtMs = MutableStateFlow(0L)
    val sessionExpiresAtMs: StateFlow<Long> = _sessionExpiresAtMs.asStateFlow()

    private val _restoredSession = MutableStateFlow(false)
    val restoredSession: StateFlow<Boolean> = _restoredSession.asStateFlow()

    private var firebaseUid: String? = null

    fun initialize(context: Context) {
        // Remove the old indefinitely-lived plaintext session during the upgrade.
        context.applicationContext.deleteSharedPreferences(LEGACY_PREFS)
        clearMemory()
    }

    fun login(
        firebaseUid: String,
        studentId: String,
        instituteId: String,
        instituteCode: String,
        studentName: String,
        studentCodeStr: String,
        expiresAtMs: Long
    ): Boolean = setSession(
        firebaseUid,
        studentId,
        instituteId,
        instituteCode,
        studentName,
        studentCodeStr,
        expiresAtMs,
        restored = false
    )

    private fun setSession(
        firebaseUid: String,
        studentId: String,
        instituteId: String,
        instituteCode: String,
        studentName: String,
        studentCodeStr: String,
        expiresAtMs: Long,
        restored: Boolean
    ): Boolean {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid != firebaseUid) {
            // Auth may have switched to an owner/staff while a restore was in flight.
            clearMemory()
            return false
        }
        if (expiresAtMs <= System.currentTimeMillis()) {
            FirebaseAuth.getInstance().signOut()
            clearMemory()
            return false
        }
        this.firebaseUid = firebaseUid
        _studentId.value = studentId
        _instituteId.value = instituteId
        _instituteCode.value = instituteCode
        _studentName.value = studentName
        _studentCode.value = studentCodeStr
        _sessionExpiresAtMs.value = expiresAtMs
        _restoredSession.value = restored
        return true
    }

    suspend fun restoreFromFirebase(): Boolean = validateFirebaseSession()

    suspend fun validateActiveSession(): Boolean = validateFirebaseSession()

    private suspend fun validateFirebaseSession(): Boolean = withContext(Dispatchers.IO) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: run {
            clearMemory()
            return@withContext false
        }

        var isStudentToken = false
        try {
            val token = user.getIdToken(false).await()
            val claims = token.claims
            isStudentToken = claims["student"] == true
            if (!isStudentToken) {
                // This is an owner/staff Firebase user. Never disturb that session.
                clearMemory()
                return@withContext false
            }

            val claimedStudentId = claims["studentId"] as? String ?: error("Missing student claim")
            val claimedInstituteId = claims["instituteId"] as? String ?: error("Missing institute claim")
            val expiry = (claims["studentSessionExpiresAt"] as? Number)?.toLong()
                ?: error("Missing student session expiry")
            if (expiry <= System.currentTimeMillis()) error("Student session expired")

            val firestore = FirebaseFirestore.getInstance()
            val studentDoc = firestore.collection("institutes").document(claimedInstituteId)
                .collection("students").document(claimedStudentId).get().await()
            val instituteDoc = firestore.collection("institutes").document(claimedInstituteId).get().await()
            val linked = studentDoc.exists() && instituteDoc.exists() &&
                studentDoc.getString("firebaseUid") == user.uid &&
                studentDoc.getBoolean("isAppAccessEnabled") == true &&
                studentDoc.getString("status") == "active" &&
                studentDoc.get("archivedAtMs") == null &&
                instituteDoc.getBoolean("isActive") != false
            if (!linked) error("Student identity link is inactive")

            return@withContext setSession(
                firebaseUid = user.uid,
                studentId = claimedStudentId,
                instituteId = claimedInstituteId,
                instituteCode = instituteDoc.getString("instituteCode") ?: "",
                studentName = studentDoc.getString("fullName") ?: "Student",
                studentCodeStr = studentDoc.getString("studentCode") ?: "",
                expiresAtMs = expiry,
                restored = true
            )
        } catch (_: Exception) {
            if (isStudentToken && auth.currentUser?.uid == user.uid) auth.signOut()
            clearMemory()
            false
        }
    }

    fun logout() {
        val auth = FirebaseAuth.getInstance()
        if (firebaseUid != null && auth.currentUser?.uid == firebaseUid) {
            auth.signOut()
        }
        clearMemory()
    }

    fun onFirebaseSignedOut() {
        clearMemory()
    }

    fun isLoggedIn(): Boolean =
        _studentId.value != null && _sessionExpiresAtMs.value > System.currentTimeMillis()

    private fun clearMemory() {
        firebaseUid = null
        _studentId.value = null
        _instituteId.value = null
        _instituteCode.value = null
        _studentName.value = null
        _studentCode.value = null
        _sessionExpiresAtMs.value = 0L
        _restoredSession.value = false
    }
}
