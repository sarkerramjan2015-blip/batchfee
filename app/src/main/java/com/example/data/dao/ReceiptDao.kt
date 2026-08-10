package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.ReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Upsert
    suspend fun insertReceipt(receipt: ReceiptEntity)
    
    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    fun getReceiptByPaymentId(instituteId: String, paymentId: String): Flow<ReceiptEntity?>

    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    suspend fun getReceiptByPaymentIdOnce(instituteId: String, paymentId: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND operationId = :operationId LIMIT 1")
    suspend fun getReceiptByOperationId(instituteId: String, operationId: String): ReceiptEntity?

    @Query("DELETE FROM receipts WHERE instituteId = :instituteId AND id = :receiptId")
    suspend fun deleteReceiptById(instituteId: String, receiptId: String)

}
