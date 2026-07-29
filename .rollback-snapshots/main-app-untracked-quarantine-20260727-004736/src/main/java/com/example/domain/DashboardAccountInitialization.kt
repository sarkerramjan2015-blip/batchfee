package com.batchfee.edu.domain

import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.UserEntity

/**
 * Separates a confirmed Firebase session from the account identity required
 * to safely render the institute dashboard.
 */
sealed interface DashboardAccountInitializationState {
    data object Initializing : DashboardAccountInitializationState

    data class Ready(
        val userId: String,
        val instituteId: String,
        val role: String
    ) : DashboardAccountInitializationState

    data class Error(val message: String) : DashboardAccountInitializationState
}

object DashboardAccountInitialization {
    const val ACCOUNT_UNAVAILABLE_MESSAGE =
        "We couldn't load this account. Check your connection and try again."

    fun resolve(
        sessionState: SessionState,
        userId: String?,
        instituteId: String?,
        role: String?,
        localUser: UserEntity?,
        localInstitute: InstituteEntity?,
        bootstrapAttemptFinished: Boolean
    ): DashboardAccountInitializationState {
        if (sessionState !is SessionState.Authenticated) {
            return DashboardAccountInitializationState.Initializing
        }
        if (userId.isNullOrBlank() || instituteId.isNullOrBlank() || role.isNullOrBlank()) {
            return DashboardAccountInitializationState.Error(ACCOUNT_UNAVAILABLE_MESSAGE)
        }

        if (localUser != null &&
            (localUser.id != userId || localUser.instituteId != instituteId || localUser.role != role)
        ) {
            return DashboardAccountInitializationState.Error(ACCOUNT_UNAVAILABLE_MESSAGE)
        }
        if (localInstitute != null && localInstitute.id != instituteId) {
            return DashboardAccountInitializationState.Error(ACCOUNT_UNAVAILABLE_MESSAGE)
        }

        if (localUser != null && localInstitute != null) {
            return DashboardAccountInitializationState.Ready(userId, instituteId, role)
        }

        return if (bootstrapAttemptFinished) {
            DashboardAccountInitializationState.Error(ACCOUNT_UNAVAILABLE_MESSAGE)
        } else {
            DashboardAccountInitializationState.Initializing
        }
    }
}
