package com.batchfee.edu

import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.UserEntity
import com.batchfee.edu.domain.DashboardAccountInitialization
import com.batchfee.edu.domain.DashboardAccountInitializationState
import com.batchfee.edu.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAccountInitializationTest {
    @Test
    fun authenticatedAccountStaysInitializingUntilItsScopedIdentityIsAvailable() {
        val state = resolve(localUser = null, localInstitute = null)

        assertEquals(DashboardAccountInitializationState.Initializing, state)
    }

    @Test
    fun matchingAuthenticatedUserAndInstituteMakeDashboardReady() {
        val state = resolve(localUser = user(), localInstitute = institute())

        assertEquals(
            DashboardAccountInitializationState.Ready("user-a", "institute-a", "InstituteOwner"),
            state
        )
    }

    @Test
    fun aPreviousUsersCachedIdentityCannotMakeAnotherUserReady() {
        val state = resolve(
            localUser = user(id = "user-b", instituteId = "institute-b"),
            localInstitute = institute(id = "institute-b")
        )

        assertError(state)
    }

    @Test
    fun aCachedInstituteForAnotherAccountCannotMakeDashboardReady() {
        val state = resolve(localUser = user(), localInstitute = institute(id = "institute-b"))

        assertError(state)
    }

    @Test
    fun correctlyScopedCachedIdentityCanRestoreWithoutWaitingForNetwork() {
        val state = resolve(
            localUser = user(),
            localInstitute = institute(),
            bootstrapAttemptFinished = false
        )

        assertTrue(state is DashboardAccountInitializationState.Ready)
    }

    @Test
    fun completedBootstrapWithoutAccountMappingShowsRetryableErrorNotDemoIdentity() {
        val state = resolve(
            localUser = null,
            localInstitute = null,
            bootstrapAttemptFinished = true
        )

        assertError(state)
    }

    @Test
    fun expiredSessionDoesNotBecomeDashboardReady() {
        val state = DashboardAccountInitialization.resolve(
            sessionState = SessionState.SessionExpired,
            userId = null,
            instituteId = null,
            role = null,
            localUser = user(),
            localInstitute = institute(),
            bootstrapAttemptFinished = true
        )

        assertEquals(DashboardAccountInitializationState.Initializing, state)
    }

    private fun resolve(
        localUser: UserEntity?,
        localInstitute: InstituteEntity?,
        bootstrapAttemptFinished: Boolean = false
    ) = DashboardAccountInitialization.resolve(
        sessionState = SessionState.Authenticated,
        userId = "user-a",
        instituteId = "institute-a",
        role = "InstituteOwner",
        localUser = localUser,
        localInstitute = localInstitute,
        bootstrapAttemptFinished = bootstrapAttemptFinished
    )

    private fun assertError(state: DashboardAccountInitializationState) {
        assertEquals(
            DashboardAccountInitialization.ACCOUNT_UNAVAILABLE_MESSAGE,
            (state as DashboardAccountInitializationState.Error).message
        )
    }

    private fun user(
        id: String = "user-a",
        instituteId: String = "institute-a"
    ) = UserEntity(
        id = id,
        instituteId = instituteId,
        name = "Noman Sir",
        email = "owner@example.com",
        passwordHash = "hash",
        role = "InstituteOwner",
        createdAtMs = 1L
    )

    private fun institute(id: String = "institute-a") = InstituteEntity(
        id = id,
        name = "ICT TOPPERS",
        currentPlanId = "plan_free_trial",
        subscriptionStatus = "trial",
        trialStartDateMs = 1L,
        trialEndDateMs = 2L,
        currentPeriodEndMs = 2L,
        createdAtMs = 1L
    )
}
