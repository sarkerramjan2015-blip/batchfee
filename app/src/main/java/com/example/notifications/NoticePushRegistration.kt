package com.batchfee.edu.notifications

import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.repository.NoticeCenterRepository
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Registers the current device only after a tenant account has authenticated.
 * The FCM token is sent to a trusted callable; it is never written by the
 * Android app directly to Firestore.
 */
object NoticePushRegistration {
    suspend fun registerCurrentTenantDevice() {
        val role = SessionManager.currentUserRole.value
        val instituteId = SessionManager.currentInstituteId.value
        if (SessionManager.currentUserId.value == null || instituteId.isNullOrBlank() ||
            role !in setOf("InstituteOwner", "InstituteAdmin", "Staff")) return

        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) NoticeCenterRepository().registerPushToken(token)
        }.onFailure { error ->
            // Push delivery is supplementary; an owner can still read notices
            // in-app if a device has no Play services or notifications disabled.
            FirebaseFailureReporter.report(error, operation = "register notice push token", permissionDeniedIsExpected = true)
        }
    }
}
