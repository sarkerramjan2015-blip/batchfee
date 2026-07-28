package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.ReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptEntity)
    
    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    fun getReceiptByPaymentId(instituteId: String, paymentId: String): Flow<ReceiptEntity?>

    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    suspend fun getReceiptByPaymentIdOnce(instituteId: String, paymentId: String): ReceiptEntity?

    @Query("DELETE FROM receipts WHERE instituteId = :instituteId AND feeId IN (:feeIds)")
    suspend fun deleteReceiptsByFeeIds(instituteId: String, feeIds: List<String>)

    @Query("DELETE FROM receipts WHERE paymentId = :paymentId AND instituteId = :instituteId")
    suspend fun deleteReceiptByPaymentId(paymentId: String, instituteId: String)
}
