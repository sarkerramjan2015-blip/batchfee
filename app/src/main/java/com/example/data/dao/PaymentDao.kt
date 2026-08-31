package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.PaymentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE instituteId = :instituteId ORDER BY paymentDateMs DESC")
    fun getRecentPayments(instituteId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE instituteId = :instituteId ORDER BY paymentDateMs DESC")
    suspend fun getAllPaymentsOnce(instituteId: String): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE instituteId = :instituteId AND feeId = :feeId ORDER BY paymentDateMs DESC")
    fun getPaymentsByFeeId(instituteId: String, feeId: String): Flow<List<PaymentEntity>>

    @Upsert
    suspend fun insertPayment(payment: PaymentEntity)

    @Query("SELECT * FROM payments WHERE id = :id AND instituteId = :instituteId LIMIT 1")
    suspend fun getPaymentById(id: String, instituteId: String): PaymentEntity?

    @Query("SELECT * FROM payments WHERE instituteId = :instituteId AND operationId = :operationId LIMIT 1")
    suspend fun getPaymentByOperationId(instituteId: String, operationId: String): PaymentEntity?

    @Query("DELETE FROM payments WHERE instituteId = :instituteId AND id = :paymentId")
    suspend fun deletePaymentById(instituteId: String, paymentId: String)

    @Query("UPDATE payments SET status = :status, updatedAtMs = :updatedAtMs WHERE instituteId = :instituteId AND id = :paymentId")
    suspend fun updatePaymentStatus(
        instituteId: String,
        paymentId: String,
        status: String,
        updatedAtMs: Long
    )

}
