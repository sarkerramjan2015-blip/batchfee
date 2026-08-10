package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.batchfee.edu.data.models.ExamEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {
    @Query("SELECT * FROM exams WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY examDateMs DESC")
    fun getExamsByInstitute(instituteId: String): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams WHERE id = :examId AND instituteId = :instituteId LIMIT 1")
    fun getExamById(examId: String, instituteId: String): Flow<ExamEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExam(exam: ExamEntity)

    @Update
    suspend fun updateExam(exam: ExamEntity)

    @Query("UPDATE exams SET archivedAtMs = :archivedAtMs, updatedAtMs = :archivedAtMs WHERE id = :examId AND instituteId = :instituteId")
    suspend fun archiveExam(instituteId: String, examId: String, archivedAtMs: Long)

}
