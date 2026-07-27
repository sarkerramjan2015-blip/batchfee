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

    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND studentId = :studentId AND cancelledAtMs IS NULL ORDER BY dueDateMs DESC")
    fun getFeesByStudent(instituteId: String, studentId: String): Flow<List<FeeEntity>>

    // ── Batch-wise fee queries ──────────────────────────────
    @Query("SELECT f.* FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active' ORDER BY f.dueDateMs DESC")
    fun getFeesByBatch(batchId: String, instituteId: String): Flow<List<FeeEntity>>

    @Query("SELECT SUM(f.paidAmount) FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active'")
    fun getTotalCollectedForBatch(batchId: String, instituteId: String): Flow<Double?>

    @Query("SELECT SUM(f.totalAmount) FROM fees f INNER JOIN batch_students bs ON f.studentId = bs.studentId WHERE bs.batchId = :batchId AND f.instituteId = :instituteId AND f.cancelledAtMs IS NULL AND bs.status = 'active'")
    fun getTotalExpectedForBatch(batchId: String, instituteId: String): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFee(fee: FeeEntity)

    @Update
    suspend fun updateFee(fee: FeeEntity)

    @Query("UPDATE fees SET paidAmount = paidAmount + :amount, dueAmount = dueAmount - :amount, status = CASE WHEN dueAmount - :amount <= 0 THEN 'paid' ELSE 'partially_paid' END, updatedAtMs = :now WHERE id = :feeId AND instituteId = :instituteId AND dueAmount >= :amount AND cancelledAtMs IS NULL")
    suspend fun atomicallyPayFee(feeId: String, instituteId: String, amount: Double, now: Long): Int

    @Query("UPDATE fees SET baseAmount = :baseAmount, discountAmount = :discountAmount, totalAmount = :totalAmount, feePeriod = :feePeriod, paidAmount = paidAmount + :collectedAmount, dueAmount = :totalAmount - paidAmount - :collectedAmount, status = CASE WHEN :totalAmount - paidAmount - :collectedAmount <= 0.001 THEN 'paid' ELSE 'partially_paid' END, updatedAtMs = :now WHERE id = :feeId AND instituteId = :instituteId AND cancelledAtMs IS NULL AND :totalAmount - paidAmount >= :collectedAmount")
    suspend fun atomicallyUpdateAndPayFee(feeId: String, instituteId: String, baseAmount: Double, discountAmount: Double, totalAmount: Double, feePeriod: String, collectedAmount: Double, now: Long): Int
    
    @Query("SELECT SUM(paidAmount) FROM fees WHERE instituteId = :instituteId AND cancelledAtMs IS NULL")
    fun getTotalCollected(instituteId: String): Flow<Double?>

    @Query("UPDATE fees SET batchId = :newBatchId, updatedAtMs = :now WHERE studentId = :studentId AND batchId = :oldBatchId AND instituteId = :instituteId AND cancelledAtMs IS NULL")
    suspend fun updateFeeBatchIdForStudent(studentId: String, oldBatchId: String, newBatchId: String, instituteId: String, now: Long)

    @Query("SELECT * FROM fees WHERE instituteId = :instituteId AND studentId = :studentId AND batchId = :batchId AND cancelledAtMs IS NULL")
    suspend fun getFeesByStudentOnce(instituteId: String, studentId: String, batchId: String): List<FeeEntity>

    @Query("SELECT id FROM fees WHERE instituteId = :instituteId AND batchId = :batchId AND cancelledAtMs IS NULL")
    suspend fun getFeeIdsForBatch(instituteId: String, batchId: String): List<String>

    @Query("DELETE FROM fees WHERE instituteId = :instituteId AND batchId = :batchId")
    suspend fun deleteFeesForBatch(instituteId: String, batchId: String)
}
