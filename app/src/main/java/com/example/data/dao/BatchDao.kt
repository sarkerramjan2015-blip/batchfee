package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.BatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDao {
    @Query("SELECT * FROM batches WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY name ASC")
    fun getBatchesByInstitute(instituteId: String): Flow<List<BatchEntity>>

    @Query("SELECT * FROM batches WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY name ASC")
    suspend fun getBatchesByInstituteOnce(instituteId: String): List<BatchEntity>

    @Query("SELECT * FROM batches WHERE id = :batchId AND instituteId = :instituteId LIMIT 1")
    fun getBatchById(batchId: String, instituteId: String): Flow<BatchEntity?>

    @Query("SELECT COUNT(*) FROM batches WHERE instituteId = :instituteId AND archivedAtMs IS NULL")
    fun countBatches(instituteId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: BatchEntity)

    @Update
    suspend fun updateBatch(batch: BatchEntity)

    @Query("UPDATE batches SET archivedAtMs = :archivedAtMs, updatedAtMs = :updatedAtMs WHERE id = :batchId AND instituteId = :instituteId")
    suspend fun archiveBatch(batchId: String, instituteId: String, archivedAtMs: Long, updatedAtMs: Long)

    @Query("DELETE FROM batches WHERE id = :batchId AND instituteId = :instituteId")
    suspend fun deleteBatch(batchId: String, instituteId: String)
}
