package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs WHERE instituteId = :instituteId ORDER BY createdAtMs DESC LIMIT 500")
    fun getAuditLogsByInstitute(instituteId: String): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE instituteId = :instituteId AND userId = :userId ORDER BY createdAtMs DESC LIMIT 100")
    fun getAuditLogsByUser(instituteId: String, userId: String): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLogEntity)
}
