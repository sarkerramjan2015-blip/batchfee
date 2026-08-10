package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.WorkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {
    @Query("SELECT * FROM works WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getActiveWorks(instituteId: String): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works WHERE instituteId = :instituteId AND batchId = :batchId AND archivedAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getWorksByBatch(instituteId: String, batchId: String): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works WHERE id = :id AND instituteId = :instituteId")
    suspend fun getWorkById(id: String, instituteId: String): WorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: WorkEntity)

    @Query("UPDATE works SET archivedAtMs = :archivedAtMs WHERE id = :id AND instituteId = :instituteId")
    suspend fun archiveWork(id: String, instituteId: String, archivedAtMs: Long)
}
