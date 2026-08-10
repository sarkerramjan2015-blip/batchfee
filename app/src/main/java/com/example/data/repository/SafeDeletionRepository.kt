package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.DeletionOutboxEntity
import com.batchfee.edu.data.models.StudentEntity
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

class SafeDeletionRepository(
    private val db: AppDatabase,
    private val gateway: SafeDeletionGateway = FirebaseSafeDeletionGateway()
) {
    suspend fun archiveStudent(student: StudentEntity, reason: String): SafeDeletionResult =
        submit("student", student.instituteId, student.id, "archive", reason)

    suspend fun restoreStudent(instituteId: String, studentId: String, reason: String): SafeDeletionResult =
        submit("student", instituteId, studentId, "restore", reason)

    suspend fun archiveBatch(batch: BatchEntity, reason: String): SafeDeletionResult =
        submit("batch", batch.instituteId, batch.id, "archive", reason)

    suspend fun restoreBatch(instituteId: String, batchId: String, reason: String): SafeDeletionResult =
        submit("batch", instituteId, batchId, "restore", reason)

    suspend fun archiveInstitute(instituteId: String, reason: String): SafeDeletionResult =
        submit("institute", instituteId, instituteId, "archive", reason)

    suspend fun restoreInstitute(instituteId: String, reason: String): SafeDeletionResult =
        submit("institute", instituteId, instituteId, "restore", reason)

    suspend fun replayAllPending() {
        replay(db.safeDeletionDao().getAllPending())
    }

    suspend fun replayPending(instituteId: String) {
        replay(db.safeDeletionDao().getPending(instituteId))
    }

    private suspend fun replay(operations: List<DeletionOutboxEntity>) {
        operations.forEach { pending ->
            try {
                execute(DeletionRequestCodec.decode(pending.requestJson), pending.createdAtMs)
            } catch (_: Exception) {
                // Exact operation remains pending for a later reconciliation attempt.
            }
        }
    }

    private suspend fun submit(
        entityType: String,
        instituteId: String,
        entityId: String,
        action: String,
        reason: String
    ): SafeDeletionResult {
        require(reason.trim().length >= 3) { "A deletion or recovery reason is required." }
        val blocking = db.safeDeletionDao()
            .getAnyPendingForEntity(instituteId, entityType, entityId)
        if (blocking != null && blocking.action != action) {
            execute(DeletionRequestCodec.decode(blocking.requestJson), blocking.createdAtMs)
        }
        val existing = db.safeDeletionDao()
            .getPendingForEntity(instituteId, entityType, entityId, action)
        val request = existing?.let { DeletionRequestCodec.decode(it.requestJson) } ?: mapOf(
            "operationId" to UUID.randomUUID().toString(),
            "instituteId" to instituteId,
            "entityType" to entityType,
            "entityId" to entityId,
            "action" to action,
            "reason" to reason.trim()
        )
        return execute(request, existing?.createdAtMs ?: System.currentTimeMillis())
    }

    private suspend fun execute(
        request: Map<String, Any?>,
        queuedAtMs: Long
    ): SafeDeletionResult {
        val operationId = request["operationId"] as? String ?: error("Missing deletion operation ID.")
        val instituteId = request["instituteId"] as? String ?: error("Missing institute ID.")
        val entityType = request["entityType"] as? String ?: error("Missing entity type.")
        val entityId = request["entityId"] as? String ?: error("Missing entity ID.")
        val action = request["action"] as? String ?: error("Missing deletion action.")
        val reason = request["reason"] as? String ?: error("Missing deletion reason.")
        val current = db.safeDeletionDao().getOperation(instituteId, operationId)
        val pending = DeletionOutboxEntity(
            operationId = operationId,
            instituteId = instituteId,
            entityType = entityType,
            entityId = entityId,
            action = action,
            reason = reason,
            requestJson = DeletionRequestCodec.encode(request),
            status = "pending",
            attempts = (current?.attempts ?: 0) + 1,
            createdAtMs = current?.createdAtMs ?: queuedAtMs,
            updatedAtMs = System.currentTimeMillis(),
            lastError = null
        )
        db.safeDeletionDao().upsertOperation(pending)

        return try {
            val result = gateway.commit(request)
            validate(request, result)
            db.withTransaction {
                applyCanonicalResult(result)
                db.safeDeletionDao().upsertOperation(
                    pending.copy(status = "completed", updatedAtMs = System.currentTimeMillis())
                )
            }
            result
        } catch (error: Exception) {
            val rejected = error is SafeDeletionRejectedException
            db.safeDeletionDao().upsertOperation(
                pending.copy(
                    status = if (rejected) "failed" else "pending",
                    updatedAtMs = System.currentTimeMillis(),
                    lastError = error.message?.take(500)
                )
            )
            if (rejected) throw error
            throw SafeDeletionPendingException(operationId, error)
        }
    }

    private suspend fun applyCanonicalResult(result: SafeDeletionResult) {
        when (result.entityType) {
            "student" -> {
                val student = db.studentDao().getStudentById(result.entityId, result.instituteId)
                    .firstOrNull() ?: return
                db.studentDao().updateStudent(
                    student.copy(
                        status = if (result.action == "archive") "archived" else result.status,
                        archivedAtMs = if (result.action == "archive") result.archivedAtMs else null,
                        isAppAccessEnabled = result.isAppAccessEnabled ?: false,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
            "batch" -> {
                val batch = db.batchDao().getBatchById(result.entityId, result.instituteId)
                    .firstOrNull() ?: return
                db.batchDao().updateBatch(
                    batch.copy(
                        status = if (result.action == "archive") "archived" else result.status,
                        archivedAtMs = if (result.action == "archive") result.archivedAtMs else null,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
            "institute" -> {
                val institute = db.instituteDao().getInstitute(result.instituteId) ?: return
                db.instituteDao().updateInstitute(
                    institute.copy(
                        subscriptionStatus = result.subscriptionStatus
                            ?: if (result.action == "archive") "deletion_pending" else "active"
                    )
                )
            }
        }
    }

    private fun validate(request: Map<String, Any?>, result: SafeDeletionResult) {
        check(result.operationId == request["operationId"] &&
            result.instituteId == request["instituteId"] &&
            result.entityType == request["entityType"] &&
            result.entityId == request["entityId"] &&
            result.action == request["action"]) {
            "Safe-deletion response does not match its queued request."
        }
        check(!result.hardDeleteAllowed) { "Backend attempted to authorize a destructive purge." }
        if (result.action == "archive") {
            check(result.status == "archived" && result.archivedAtMs != null &&
                result.retentionUntilMs != null && result.mediaCleanupState == "retained") {
                "Archive response does not contain a recoverable retained state."
            }
        } else {
            check(result.archivedAtMs == null && result.retentionUntilMs == null) {
                "Restore response still contains an archived state."
            }
        }
    }
}
