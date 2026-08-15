package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.HomeworkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeworkDao {
    @Query("SELECT * FROM homework WHERE instituteId = :instId AND archivedAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getActive(instId: String): Flow<List<HomeworkEntity>>

    @Query("SELECT * FROM homework WHERE id = :id AND instituteId = :instId")
    suspend fun getById(id: String, instId: String): HomeworkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HomeworkEntity)

    @Query("UPDATE homework SET archivedAtMs = :ts WHERE id = :id AND instituteId = :instId")
    suspend fun archive(id: String, instId: String, ts: Long)

    @Query("DELETE FROM homework_submissions WHERE instituteId = :instId AND homeworkId = :homeworkId")
    suspend fun deleteSubmissionsForHomework(instId: String, homeworkId: String)

    @Query("DELETE FROM homework WHERE id = :id AND instituteId = :instId")
    suspend fun deletePermanently(id: String, instId: String)
}
