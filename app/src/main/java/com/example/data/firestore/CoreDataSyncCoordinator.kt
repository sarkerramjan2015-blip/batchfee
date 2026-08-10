package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.batchfee.edu.data.repository.SafeDeletionRepository
import com.google.firebase.crashlytics.FirebaseCrashlytics

enum class InstituteRefreshScope {
    STUDENTS,
    BATCHES,
    STAFF,
    FINANCE,
    ATTENDANCE
}

object CoreDataSyncCoordinator {

    suspend fun refreshInstituteCache(db: AppDatabase, instituteId: String) {
        try {
            SubscriptionPlanSyncHelper.syncAllFromFirestore(db)
            if (instituteId.isBlank()) return
            InstituteSyncHelper.syncInstituteFromFirestore(db, instituteId)
            SafeDeletionRepository(db).replayPending(instituteId)
            StaffSyncHelper.syncAllFromFirestore(db, instituteId)
            StudentSyncHelper.syncAllFromFirestore(db, instituteId)
            BatchSyncHelper.syncAllFromFirestore(db, instituteId)
            BatchStudentSyncHelper.syncAllFromFirestore(db, instituteId)
            FeeCollectionRepository(db).replayPendingOperations(instituteId)
            FinanceSyncHelper.syncAllFromFirestore(db, instituteId)
            AttendanceSyncHelper.syncAllFromFirestore(db, instituteId)
            EnquirySyncHelper.syncAllFromFirestore(db, instituteId)
            ExamSyncHelper.syncAllFromFirestore(db, instituteId)
            ExpenseSyncHelper.syncAllFromFirestore(db, instituteId)
            SalarySyncHelper.syncAllFromFirestore(db, instituteId)
            ReminderTemplateSyncHelper.syncAllFromFirestore(db, instituteId)
            AuditLogSyncHelper.syncAllFromFirestore(db, instituteId)
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /**
     * Screen-level refreshes stay narrow: Room renders first and only the collection relevant to
     * the open screen is refreshed in the background.
     */
    suspend fun refreshScope(
        db: AppDatabase,
        instituteId: String,
        scope: InstituteRefreshScope
    ) {
        if (instituteId.isBlank()) return
        try {
            when (scope) {
                InstituteRefreshScope.STUDENTS -> {
                    SafeDeletionRepository(db).replayPending(instituteId)
                    StudentSyncHelper.syncAllFromFirestore(db, instituteId)
                }
                InstituteRefreshScope.BATCHES -> {
                    SafeDeletionRepository(db).replayPending(instituteId)
                    BatchSyncHelper.syncAllFromFirestore(db, instituteId)
                }
                InstituteRefreshScope.STAFF ->
                    StaffSyncHelper.syncAllFromFirestore(db, instituteId)
                InstituteRefreshScope.FINANCE -> {
                    FeeCollectionRepository(db).replayPendingOperations(instituteId)
                    FinanceSyncHelper.syncAllFromFirestore(db, instituteId)
                }
                InstituteRefreshScope.ATTENDANCE ->
                    AttendanceSyncHelper.syncAllFromFirestore(db, instituteId)
            }
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
}

