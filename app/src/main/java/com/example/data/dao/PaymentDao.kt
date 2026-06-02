package com.example.data.dao

import androidx.room.*
import com.example.data.models.PaymentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE instituteId = :instituteId ORDER BY paymentDateMs DESC")
    fun getRecentPayments(instituteId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE instituteId = :instituteId ORDER BY paymentDateMs DESC")
    suspend fun getAllPaymentsOnce(instituteId: String): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE instituteId = :instituteId AND feeId = :feeId ORDER BY paymentDateMs DESC")
    fun getPaymentsByFeeId(instituteId: String, feeId: String): Flow<List<PaymentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: PaymentEntity)
}
