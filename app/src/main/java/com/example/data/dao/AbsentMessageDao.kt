package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.AbsentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AbsentMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: AbsentMessageEntity)

    @Query("SELECT * FROM absent_messages WHERE instituteId = :instituteId AND studentId = :studentId AND attendanceDateMs = :dateMs")
    fun getMessageForStudentDate(instituteId: String, studentId: String, dateMs: Long): Flow<AbsentMessageEntity?>

    @Query("SELECT * FROM absent_messages WHERE instituteId = :instituteId AND batchId = :batchId AND attendanceDateMs = :dateMs")
    fun getMessagesForBatchDate(instituteId: String, batchId: String, dateMs: Long): Flow<List<AbsentMessageEntity>>

    @Query("SELECT COUNT(*) FROM absent_messages WHERE instituteId = :instituteId AND studentId = :studentId AND attendanceDateMs = :dateMs")
    suspend fun hasMessageForStudentDate(instituteId: String, studentId: String, dateMs: Long): Int

    @Query("DELETE FROM absent_messages WHERE instituteId = :instituteId AND batchId = :batchId")
    suspend fun deleteMessagesForBatch(instituteId: String, batchId: String)

    @Query("DELETE FROM absent_messages WHERE instituteId = :instituteId AND studentId = :studentId")
    suspend fun deleteMessagesForStudent(instituteId: String, studentId: String)
}
