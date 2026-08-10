package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.ResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ResultDao {
    @Query("SELECT * FROM results WHERE examId = :examId AND instituteId = :instituteId ORDER BY marksObtained DESC")
    fun getResultsForExam(examId: String, instituteId: String): Flow<List<ResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateResult(result: ResultEntity)

    @Query("SELECT * FROM results WHERE instituteId = :instituteId AND examId = :examId AND studentId = :studentId LIMIT 1")
    suspend fun getResultForStudentOnce(instituteId: String, examId: String, studentId: String): ResultEntity?

}
