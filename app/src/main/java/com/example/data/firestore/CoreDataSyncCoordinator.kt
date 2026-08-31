package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.batchfee.edu.data.repository.SafeDeletionRepository
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

enum class InstituteRefreshScope {
    STUDENTS,
    BATCHES,
    ENROLLMENTS,
    STAFF,
    FINANCE,
    EXPENSES,
    ATTENDANCE
}

object CoreDataSyncCoordinator {

    suspend fun refreshInstituteCache(db: AppDatabase, instituteId: String) {
        if (instituteId.isBlank()) return
        val plan = currentSessionPlan(instituteId)
        if (!plan.syncInstitute) return

        try {
            if (plan.syncSubscriptionPlans) SubscriptionPlanSyncHelper.syncAllFromFirestore(db)

            // Firestore rules deny every operational collection after entitlement ends.
            // Confirm against the server before issuing any protected query; cached state
            // must never be used to authorise a refresh.
            if (!hasServerConfirmedEntitlement(instituteId)) return

            InstituteSyncHelper.syncInstituteFromFirestore(db, instituteId)
            if (plan.replayDeletions) SafeDeletionRepository(db).replayPending(instituteId)
            if (plan.syncStaff) StaffSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncStudents) StudentSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncBatches) BatchSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncEnrollments) BatchStudentSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.replayFinanceOperations) {
                FeeCollectionRepository(db).replayPendingOperations(instituteId)
            }
            if (plan.syncFinance) FinanceSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncAttendance || plan.syncStaffAttendance) {
                AttendanceSyncHelper.syncAllFromFirestore(
                    db = db,
                    instituteId = instituteId,
                    syncStudentAttendance = plan.syncAttendance,
                    syncStaffAttendance = plan.syncStaffAttendance
                )
            }
            if (plan.syncEnquiries) EnquirySyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncExams) ExamSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncExpenses) ExpenseSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncSalaries) SalarySyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncReminders) ReminderTemplateSyncHelper.syncAllFromFirestore(db, instituteId)
            if (plan.syncAuditLogs) AuditLogSyncHelper.syncAllFromFirestore(db, instituteId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FirebaseFailureReporter.report(
                error,
                operation = "full institute cache refresh",
                permissionDeniedIsExpected = true
            )
        }
    }

    /**
     * Screen-level refreshes stay narrow: Room renders first and only a permitted collection
     * relevant to the open screen is refreshed in the background.
     */
    suspend fun refreshScope(
        db: AppDatabase,
        instituteId: String,
        scope: InstituteRefreshScope
    ) {
        if (instituteId.isBlank()) return
        val plan = currentSessionPlan(instituteId)
        if (!CoreDataSyncPolicy.allowsScope(plan, scope)) return

        try {
            if (!hasServerConfirmedEntitlement(instituteId)) return
            when (scope) {
                InstituteRefreshScope.STUDENTS -> {
                    if (plan.replayDeletions) SafeDeletionRepository(db).replayPending(instituteId)
                    StudentSyncHelper.syncAllFromFirestore(db, instituteId)
                }

                InstituteRefreshScope.BATCHES -> {
                    if (plan.replayDeletions) SafeDeletionRepository(db).replayPending(instituteId)
                    BatchSyncHelper.syncAllFromFirestore(db, instituteId)
                }

                InstituteRefreshScope.ENROLLMENTS ->
                    BatchStudentSyncHelper.syncAllFromFirestore(db, instituteId)

                InstituteRefreshScope.STAFF ->
                    StaffSyncHelper.syncAllFromFirestore(db, instituteId)

                InstituteRefreshScope.FINANCE -> {
                    if (plan.replayFinanceOperations) {
                        FeeCollectionRepository(db).replayPendingOperations(instituteId)
                    }
                    FinanceSyncHelper.syncAllFromFirestore(db, instituteId)
                }

                InstituteRefreshScope.EXPENSES ->
                    ExpenseSyncHelper.syncAllFromFirestore(db, instituteId)

                InstituteRefreshScope.ATTENDANCE ->
                    AttendanceSyncHelper.syncAllFromFirestore(
                        db = db,
                        instituteId = instituteId,
                        syncStudentAttendance = plan.syncAttendance,
                        syncStaffAttendance = plan.syncStaffAttendance
                    )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FirebaseFailureReporter.report(
                error,
                operation = "${scope.name.lowercase()} cache refresh",
                permissionDeniedIsExpected = true
            )
        }
    }

    private fun currentSessionPlan(instituteId: String): CoreDataSyncPlan {
        if (SessionManager.currentInstituteId.value != instituteId) return CoreDataSyncPlan()
        return CoreDataSyncPolicy.forSession(
            role = SessionManager.currentUserRole.value,
            permissions = SessionManager.currentStaffPermissions.value
        )
    }

    private suspend fun hasServerConfirmedEntitlement(instituteId: String): Boolean {
        val snapshot = FirebaseFirestore.getInstance()
            .collection("institutes")
            .document(instituteId)
            .get(Source.SERVER)
            .await()
        val periodEnd = snapshot.getLong("currentPeriodEndMs")
        return snapshot.getBoolean("isActive") == true &&
            periodEnd != null &&
            periodEnd > System.currentTimeMillis() &&
            snapshot.getString("subscriptionStatus") in setOf("trial", "active")
    }
}
