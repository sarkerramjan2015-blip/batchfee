package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.batchfee.edu.data.models.FinancialOutboxEntity
import com.batchfee.edu.data.models.PaymentReversalEntity

@Dao
interface FinancialLedgerDao {
    @Upsert
    suspend fun upsertReversal(reversal: PaymentReversalEntity)

    @Query("SELECT * FROM payment_reversals WHERE instituteId = :instituteId")
    suspend fun getReversals(instituteId: String): List<PaymentReversalEntity>

    @Query("SELECT * FROM payment_reversals WHERE instituteId = :instituteId AND paymentId = :paymentId LIMIT 1")
    suspend fun getReversalForPayment(instituteId: String, paymentId: String): PaymentReversalEntity?

    @Query("DELETE FROM payment_reversals WHERE instituteId = :instituteId AND id = :reversalId")
    suspend fun deleteReversalById(instituteId: String, reversalId: String)

    @Upsert
    suspend fun upsertOutbox(operation: FinancialOutboxEntity)

    @Query("SELECT * FROM financial_outbox WHERE instituteId = :instituteId AND status = 'pending' ORDER BY createdAtMs ASC")
    suspend fun getPendingOperations(instituteId: String): List<FinancialOutboxEntity>

    @Query("SELECT * FROM financial_outbox WHERE instituteId = :instituteId AND operationId = :operationId LIMIT 1")
    suspend fun getOperation(instituteId: String, operationId: String): FinancialOutboxEntity?
}
