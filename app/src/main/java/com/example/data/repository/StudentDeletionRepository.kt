package com.batchfee.edu.data.repository

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StudentEntity

/**
 * Student removal is a recoverable backend-owned archive operation. It never deletes
 * financial or operational history from Room, Firestore, Auth, or media storage.
 */
class StudentDeletionRepository(db: AppDatabase) {
    private val safeDeletionRepository = SafeDeletionRepository(db)

    suspend fun archive(student: StudentEntity, reason: String = "Student archived by an authorised user") =
        safeDeletionRepository.archiveStudent(student, reason)

    suspend fun restore(
        instituteId: String,
        studentId: String,
        reason: String = "Student restored by an authorised user"
    ) = safeDeletionRepository.restoreStudent(instituteId, studentId, reason)
}
