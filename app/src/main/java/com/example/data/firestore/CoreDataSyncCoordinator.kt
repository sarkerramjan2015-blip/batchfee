package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.google.firebase.crashlytics.FirebaseCrashlytics

object CoreDataSyncCoordinator {

    suspend fun refreshInstituteCache(db: AppDatabase, instituteId: String) {
        try {
            SubscriptionPlanSyncHelper.syncAllFromFirestore(db)
            if (instituteId.isBlank()) return
            InstituteSyncHelper.syncInstituteFromFirestore(db, instituteId)
            StaffSyncHelper.syncAllFromFirestore(db, instituteId)
            StudentSyncHelper.syncAllFromFirestore(db, instituteId)
            BatchSyncHelper.syncAllFromFirestore(db, instituteId)
            BatchStudentSyncHelper.syncAllFromFirestore(db, instituteId)
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
}

