package com.batchfee.edu.domain

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.AuditLogSyncHelper
import com.batchfee.edu.data.models.AuditLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Appends security and high-value staff events to the existing audit trail.
 * There is deliberately no edit/delete API for normal app flows.
 */
object StaffAuditLogger {
    suspend fun record(
        db: AppDatabase,
        instituteId: String?,
        actorUserId: String?,
        action: String,
        module: String,
        description: String,
        oldValue: String? = null,
        newValue: String? = null
    ) = withContext(Dispatchers.IO) {
        val safeInstituteId = instituteId?.takeIf { it.isNotBlank() } ?: return@withContext
        val event = AuditLogEntity(
            id = UUID.randomUUID().toString(),
            instituteId = safeInstituteId,
            userId = actorUserId,
            action = action,
            module = module,
            description = description,
            oldValue = oldValue,
            newValue = newValue,
            createdAtMs = System.currentTimeMillis()
        )
        db.auditLogDao().insertAuditLog(event)
        runCatching { AuditLogSyncHelper.upsertAuditLog(event) }
    }
}
