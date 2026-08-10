package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.batchfee.edu.data.models.DeletionOutboxEntity

@Dao
interface SafeDeletionDao {
    @Upsert
    suspend fun upsertOperation(operation: DeletionOutboxEntity)

    @Query("SELECT * FROM deletion_outbox WHERE instituteId = :instituteId AND operationId = :operationId LIMIT 1")
    suspend fun getOperation(instituteId: String, operationId: String): DeletionOutboxEntity?

    @Query("SELECT * FROM deletion_outbox WHERE instituteId = :instituteId AND entityType = :entityType AND entityId = :entityId AND action = :action AND status = 'pending' ORDER BY createdAtMs ASC LIMIT 1")
    suspend fun getPendingForEntity(
        instituteId: String,
        entityType: String,
        entityId: String,
        action: String
    ): DeletionOutboxEntity?

    @Query("SELECT * FROM deletion_outbox WHERE instituteId = :instituteId AND entityType = :entityType AND entityId = :entityId AND status = 'pending' ORDER BY createdAtMs ASC LIMIT 1")
    suspend fun getAnyPendingForEntity(
        instituteId: String,
        entityType: String,
        entityId: String
    ): DeletionOutboxEntity?

    @Query("SELECT * FROM deletion_outbox WHERE status = 'pending' ORDER BY createdAtMs ASC")
    suspend fun getAllPending(): List<DeletionOutboxEntity>

    @Query("SELECT * FROM deletion_outbox WHERE instituteId = :instituteId AND status = 'pending' ORDER BY createdAtMs ASC")
    suspend fun getPending(instituteId: String): List<DeletionOutboxEntity>
}
