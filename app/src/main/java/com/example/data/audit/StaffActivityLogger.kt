package com.batchfee.edu.data.audit

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.AuditLogSyncHelper
import com.batchfee.edu.data.models.AuditLogEntity
import com.batchfee.edu.domain.SessionManager
import java.util.UUID

/**
 * Records a completed action performed by a staff member.
 *
 * Institute-owner actions are deliberately excluded: this feed is for the owner to
 * review staff work, not a noisy log of every action in the institute.
 */
object StaffActivityLogger {
    suspend fun logCompletedAction(
        db: AppDatabase,
        action: String,
        module: String,
        description: String
    ) {
        if (!SessionManager.isStaff()) return

        val instituteId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val log = AuditLogEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instituteId,
            userId = userId,
            action = action,
            module = module,
            description = description,
            oldValue = null,
            newValue = null,
            createdAtMs = System.currentTimeMillis()
        )

        // Preserve the history on this device immediately.  The action itself has
        // already succeeded; a logging failure must never make the action fail.
        db.auditLogDao().insertAuditLog(log)
        try {
            AuditLogSyncHelper.upsertAuditLog(log)
        } catch (_: Exception) {
            // Audit logging is intentionally best-effort and must not interrupt staff work.
        }
    }
}
