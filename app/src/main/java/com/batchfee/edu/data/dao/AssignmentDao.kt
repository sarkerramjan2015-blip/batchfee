package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.AssignmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments WHERE instituteId = :instId AND archivedAtMs IS NULL AND status = 'published' ORDER BY createdAtMs DESC")
    fun getPublished(instId: String): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE instituteId = :instId AND archivedAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getAll(instId: String): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE id = :id AND instituteId = :instId")
    suspend fun getById(id: String, instId: String): AssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: AssignmentEntity)

    @Query("UPDATE assignments SET archivedAtMs = :ts WHERE id = :id AND instituteId = :instId")
    suspend fun archive(id: String, instId: String, ts: Long)
}
