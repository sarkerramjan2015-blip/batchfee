package com.example.data.dao

import androidx.room.*
import com.example.data.models.ReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptEntity)
    
    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    fun getReceiptByPaymentId(instituteId: String, paymentId: String): Flow<ReceiptEntity?>

    @Query("SELECT * FROM receipts WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    suspend fun getReceiptByPaymentIdOnce(instituteId: String, paymentId: String): ReceiptEntity?
}
