package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.models.StudentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchStudentDao {
    @Query("SELECT s.* FROM students s INNER JOIN batch_students bs ON s.id = bs.studentId WHERE bs.batchId = :batchId AND bs.instituteId = :instituteId AND bs.status = 'active' AND s.archivedAtMs IS NULL AND (s.status IS NULL OR LOWER(TRIM(s.status)) NOT IN ('inactive', 'close', 'closed', 'archived'))")
    fun getStudentsForBatch(batchId: String, instituteId: String): Flow<List<StudentEntity>>

    @Query("SELECT s.* FROM students s INNER JOIN batch_students bs ON s.id = bs.studentId WHERE bs.batchId = :batchId AND bs.instituteId = :instituteId AND bs.status = 'active' AND s.archivedAtMs IS NULL AND (s.status IS NULL OR LOWER(TRIM(s.status)) NOT IN ('inactive', 'close', 'closed', 'archived'))")
    suspend fun getStudentsForBatchOnce(batchId: String, instituteId: String): List<StudentEntity>

    @Query("SELECT b.* FROM batches b INNER JOIN batch_students bs ON b.id = bs.batchId WHERE bs.studentId = :studentId AND bs.instituteId = :instituteId AND bs.status = 'active' AND b.archivedAtMs IS NULL ORDER BY b.name ASC")
    fun getBatchesForStudent(studentId: String, instituteId: String): Flow<List<BatchEntity>>

    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND status = 'active'")
    fun getActiveEnrollmentsForInstitute(instituteId: String): Flow<List<BatchStudentEntity>>

    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND studentId = :studentId AND status = 'active'")
    fun getActiveEnrollmentsForStudent(
        studentId: String,
        instituteId: String
    ): Flow<List<BatchStudentEntity>>

    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND studentId = :studentId AND status = 'active'")
    suspend fun getActiveEnrollmentsForStudentOnce(
        studentId: String,
        instituteId: String
    ): List<BatchStudentEntity>

    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND batchId = :batchId AND status = 'active'")
    suspend fun getActiveEnrollmentsForBatchOnce(
        batchId: String,
        instituteId: String
    ): List<BatchStudentEntity>

    // A removed enrollment still owns its completed-month arrears. Keep it in
    // finance calculations until those historic dues are reconciled.
    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND studentId = :studentId AND (status = 'active' OR (status = 'removed' AND leftAtMs IS NOT NULL))")
    suspend fun getBillingEnrollmentsForStudentOnce(
        studentId: String,
        instituteId: String
    ): List<BatchStudentEntity>

    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND studentId = :studentId AND (status = 'active' OR (status = 'removed' AND leftAtMs IS NOT NULL))")
    fun getBillingEnrollmentsForStudent(
        studentId: String,
        instituteId: String
    ): Flow<List<BatchStudentEntity>>

    @Query("SELECT * FROM batch_students WHERE instituteId = :instituteId AND (status = 'active' OR (status = 'removed' AND leftAtMs IS NOT NULL))")
    fun getBillingEnrollmentsForInstitute(instituteId: String): Flow<List<BatchStudentEntity>>

    @Query("SELECT COUNT(*) FROM batch_students WHERE batchId = :batchId AND studentId = :studentId AND instituteId = :instituteId AND status = 'active'")
    suspend fun isStudentInBatch(batchId: String, studentId: String, instituteId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enrollStudent(enrollment: BatchStudentEntity)

    @Query("DELETE FROM batch_students WHERE instituteId = :instituteId AND id = :enrollmentId")
    suspend fun deleteEnrollment(instituteId: String, enrollmentId: String)

    @Query("UPDATE batch_students SET status = 'removed', leftAtMs = :leftAtMs WHERE batchId = :batchId AND studentId = :studentId AND instituteId = :instituteId")
    suspend fun removeStudentFromBatch(batchId: String, studentId: String, instituteId: String, leftAtMs: Long)

}
