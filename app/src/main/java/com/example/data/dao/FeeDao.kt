package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.FeeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeeDao {
    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND cancelledAtMs IS NULL ORDER BY dueDateMs DESC")
    fun getAllFees(instituteId: String): Flow<List<FeeEntity>>

    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND cancelledAtMs IS NULL ORDER BY dueDateMs DESC")
    suspend fun getAllFeesOnce(instituteId: String): List<FeeEntity>
    
    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND dueAmount > 0 AND cancelledAtMs IS NULL ORDER BY dueDateMs ASC")
    fun getDueFees(instituteId: String): Flow<List<FeeEntity>>
    
    @Query("SELECT * FROM fees WHERE id = :feeId AND instituteId = :instituteId LIMIT 1")
    suspend fun getFeeById(feeId: String, instituteId: String): FeeEntity?

    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND businessKey = :businessKey LIMIT 1")
    suspend fun getFeeByBusinessKey(instituteId: String, businessKey: String): FeeEntity?

    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND studentId = :studentId AND cancelledAtMs IS NULL ORDER BY dueDateMs DESC")
    fun getFeesByStudent(instituteId: String, studentId: String): Flow<List<FeeEntity>>

    // ── Batch-wise fee queries ──────────────────────────────
    @Query("SELECT f.* FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active' ORDER BY f.dueDateMs DESC")
    fun getFeesByBatch(batchId: String, instituteId: String): Flow<List<FeeEntity>>

    @Query("SELECT f.* FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active' ORDER BY f.dueDateMs DESC")
    suspend fun getFeesByBatchOnce(batchId: String, instituteId: String): List<FeeEntity>

    @Query("SELECT SUM(f.paidAmount) FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active'")
    fun getTotalCollectedForBatch(batchId: String, instituteId: String): Flow<Double?>

    @Query("SELECT SUM(f.totalAmount) FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active'")
    fun getTotalExpectedForBatch(batchId: String, instituteId: String): Flow<Double?>

    @Upsert
    suspend fun insertFee(fee: FeeEntity)

    @Query("SELECT SUM(paidAmount) FROM fees WHERE instituteId = :instituteId AND cancelledAtMs IS NULL")
    fun getTotalCollected(instituteId: String): Flow<Double?>

    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND studentId = :studentId AND batchId = :batchId AND cancelledAtMs IS NULL")
    suspend fun getFeesByStudentOnce(instituteId: String, studentId: String, batchId: String): List<FeeEntity>

    @Query("SELECT id FROM fees WHERE instituteId = :instituteId AND batchId = :batchId AND cancelledAtMs IS NULL")
    suspend fun getFeeIdsForBatch(instituteId: String, batchId: String): List<String>

    @Query("SELECT id FROM fees WHERE instituteId = :instituteId AND studentId = :studentId")
    suspend fun getFeeIdsForStudent(instituteId: String, studentId: String): List<String>

}
