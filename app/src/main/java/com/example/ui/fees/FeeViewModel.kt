package com.example.ui.fees

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.FeeEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class FeeViewModel(private val db: AppDatabase) : ViewModel() {
    private val _feeList = MutableStateFlow<List<FeeEntity>>(emptyList())
    val feeList = _feeList.asStateFlow()

    private val _dueFeeList = MutableStateFlow<List<FeeEntity>>(emptyList())
    val dueFeeList = _dueFeeList.asStateFlow()
    
    private val _totalCollected = MutableStateFlow(0.0)
    val totalCollected = _totalCollected.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            launch {
                db.feeDao().getAllFees(instId).collect { _feeList.value = it }
            }
            launch {
                db.feeDao().getDueFees(instId).collect { _dueFeeList.value = it }
            }
            launch {
                db.feeDao().getTotalCollected(instId).collect { _totalCollected.value = it ?: 0.0 }
            }
        }
    }

    fun createFee(studentId: String, batchId: String?, feePeriod: String, feeType: String, dueDateMs: Long, baseAmount: Double, discount: Double, lateFee: Double) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val total = baseAmount - discount + lateFee
        if (total < 0) return
        val fee = FeeEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instId,
            studentId = studentId,
            batchId = batchId,
            feePeriod = feePeriod,
            feeType = feeType,
            dueDateMs = dueDateMs,
            baseAmount = baseAmount,
            discountAmount = discount,
            lateFeeAmount = lateFee,
            totalAmount = total,
            paidAmount = 0.0,
            dueAmount = total,
            status = "unpaid",
            note = null,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            cancelledAtMs = null
        )
        viewModelScope.launch {
            db.feeDao().insertFee(fee)
        }
    }

    fun collectPayment(feeId: String, amount: Double, paymentMethod: String, note: String?, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        if (amount <= 0.0) {
            onError("Amount must be greater than zero.")
            return
        }
        viewModelScope.launch {
            val fee = db.feeDao().getFeeById(feeId, instId)
            if (fee == null) {
                onError("Fee not found.")
                return@launch
            }
            if (fee.cancelledAtMs != null) {
                onError("Fee is cancelled.")
                return@launch
            }
            if (amount > fee.dueAmount) {
                onError("Payment amount exceeds due amount.")
                return@launch
            }

            val paymentId = UUID.randomUUID().toString()
            val receiptNumber = "REC-${System.currentTimeMillis()}"
            val payment = com.example.data.models.PaymentEntity(
                id = paymentId,
                instituteId = instId,
                feeId = feeId,
                studentId = fee.studentId,
                amount = amount,
                paymentMethod = paymentMethod,
                transactionId = null,
                receiptNumber = receiptNumber,
                paymentDateMs = System.currentTimeMillis(),
                collectedByUserId = userId,
                status = "completed",
                note = note,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            db.paymentDao().insertPayment(payment)

            val newPaid = fee.paidAmount + amount
            val newDue = fee.totalAmount - newPaid
            val newStatus = if (newDue <= 0.0) "paid" else "partially_paid"

            val updatedFee = fee.copy(
                paidAmount = newPaid,
                dueAmount = newDue,
                status = newStatus,
                updatedAtMs = System.currentTimeMillis()
            )
            db.feeDao().updateFee(updatedFee)

            val receipt = com.example.data.models.ReceiptEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                paymentId = paymentId,
                feeId = fee.id,
                studentId = fee.studentId,
                receiptNumber = receiptNumber,
                receiptDateMs = System.currentTimeMillis(),
                totalAmount = fee.totalAmount,
                paidAmount = newPaid,
                dueAmount = newDue,
                paymentMethod = paymentMethod,
                receiptText = "Payment of $amount received.",
                createdAtMs = System.currentTimeMillis()
            )
            db.receiptDao().insertReceipt(receipt)

            onSuccess(paymentId)
        }
    }

    fun updateFeeAndCollectPayment(
        feeId: String,
        newBaseAmount: Double,
        discountPercent: Double,
        collectedAmount: Double,
        paymentMethod: String,
        feePeriod: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        if (collectedAmount <= 0.0) {
            onError("Amount must be greater than zero.")
            return
        }
        viewModelScope.launch {
            val fee = db.feeDao().getFeeById(feeId, instId)
            if (fee == null) { onError("Fee not found."); return@launch }
            if (fee.cancelledAtMs != null) { onError("Fee is cancelled."); return@launch }

            val discountAmount = newBaseAmount * discountPercent / 100.0
            val totalAmount = newBaseAmount - discountAmount
            val alreadyPaid = fee.paidAmount
            val dueAfterDiscount = totalAmount - alreadyPaid

            if (collectedAmount > dueAfterDiscount + 0.001) {
                onError("Payment amount exceeds due amount.")
                return@launch
            }

            val newPaid = alreadyPaid + collectedAmount
            val newDue = totalAmount - newPaid
            val newStatus = if (newDue <= 0.001) "paid" else "partially_paid"

            val updatedFee = fee.copy(
                baseAmount = newBaseAmount,
                discountAmount = discountAmount,
                totalAmount = totalAmount,
                paidAmount = newPaid,
                dueAmount = newDue.coerceAtLeast(0.0),
                status = newStatus,
                feePeriod = feePeriod,
                updatedAtMs = System.currentTimeMillis()
            )
            db.feeDao().updateFee(updatedFee)

            val paymentId = UUID.randomUUID().toString()
            val receiptNumber = "REC-${System.currentTimeMillis()}"
            val payment = com.example.data.models.PaymentEntity(
                id = paymentId,
                instituteId = instId,
                feeId = feeId,
                studentId = fee.studentId,
                amount = collectedAmount,
                paymentMethod = paymentMethod,
                transactionId = null,
                receiptNumber = receiptNumber,
                paymentDateMs = System.currentTimeMillis(),
                collectedByUserId = userId,
                status = "completed",
                note = null,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            db.paymentDao().insertPayment(payment)

            val receipt = com.example.data.models.ReceiptEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                paymentId = paymentId,
                feeId = fee.id,
                studentId = fee.studentId,
                receiptNumber = receiptNumber,
                receiptDateMs = System.currentTimeMillis(),
                totalAmount = totalAmount,
                paidAmount = newPaid,
                dueAmount = newDue.coerceAtLeast(0.0),
                paymentMethod = paymentMethod,
                receiptText = buildString {
                    append("Receipt: $receiptNumber\n")
                    append("Period: $feePeriod\n")
                    append("Base: BDT $newBaseAmount\n")
                    append("Discount (${discountPercent.toInt()}%): BDT ${"%.2f".format(discountAmount)}\n")
                    append("Payable: BDT ${"%.2f".format(totalAmount)}\n")
                    append("Paid: BDT ${"%.2f".format(newPaid)}\n")
                    append("Due: BDT ${"%.2f".format(newDue.coerceAtLeast(0.0))}\n")
                    append("Collected Now: BDT ${"%.2f".format(collectedAmount)}")
                },
                createdAtMs = System.currentTimeMillis()
            )
            db.receiptDao().insertReceipt(receipt)

            onSuccess(paymentId)
        }
    }
}

class FeeViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FeeViewModel::class.java)) return FeeViewModel(db) as T
        throw IllegalArgumentException()
    }
}
