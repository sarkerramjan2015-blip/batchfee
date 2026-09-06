package com.batchfee.edu.data.firestore

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.models.AbsentMessageEntity
import com.batchfee.edu.data.models.AttendanceEntity
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.models.EnquiryEntity
import com.batchfee.edu.data.models.ExamEntity
import com.batchfee.edu.data.models.ExpenseEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.AuditLogEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.PaymentReversalEntity
import com.batchfee.edu.data.models.ReceiptEntity
import com.batchfee.edu.data.models.ReminderTemplateEntity
import com.batchfee.edu.data.models.ResultEntity
import com.batchfee.edu.data.models.SalaryEntity
import com.batchfee.edu.data.models.StaffAttendanceEntity
import com.batchfee.edu.data.models.SubscriptionPlanEntity
import com.batchfee.edu.data.models.TeachingSessionEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val firestore by lazy { FirebaseFirestore.getInstance() }

private fun instituteCollection(instituteId: String, name: String) =
    firestore.collection("institutes").document(instituteId).collection(name)

private fun recordException(e: Exception) {
    FirebaseFailureReporter.report(
        e,
        operation = "operational data sync",
        permissionDeniedIsExpected = true
    )
}

/**
 * PERMISSION_DENIED means the institute's access genuinely ended (subscription
 * lapsed, staff/student deactivated, etc.). Re-throwing it turns an expected
 * state into an uncaught crash, so it is swallowed here while every other error
 * keeps propagating to its caller.
 */
private fun rethrowUnlessAccessDenied(e: Exception) {
    if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return
    throw e
}

private fun Number?.asLong(): Long? = this?.toLong()
private fun Number?.asDouble(): Double? = this?.toDouble()
private fun Any?.asBooleanCompat(): Boolean? = this as? Boolean

object BatchStudentSyncHelper {
    private const val COLLECTION = "batch_students"

    suspend fun upsertEnrollment(enrollment: BatchStudentEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(enrollment.instituteId, COLLECTION).document(enrollment.id)
                .set(
                    mapOf(
                        "instituteId" to enrollment.instituteId,
                        "batchId" to enrollment.batchId,
                        "studentId" to enrollment.studentId,
                        "joinedAtMs" to enrollment.joinedAtMs,
                        "status" to enrollment.status,
                        "leftAtMs" to enrollment.leftAtMs,
                        "firstMonthFeePeriod" to enrollment.firstMonthFeePeriod,
                        "firstMonthFeeAmount" to enrollment.firstMonthFeeAmount,
                        "customMonthlyFeeAmount" to enrollment.customMonthlyFeeAmount,
                        "customFeeReason" to enrollment.customFeeReason,
                        "customFeeEffectiveFromPeriod" to enrollment.customFeeEffectiveFromPeriod,
                        "customFeePolicySyncedAtMs" to enrollment.customFeePolicySyncedAtMs
                    )
                ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun markRemoved(
        instituteId: String,
        batchId: String,
        studentId: String,
        leftAtMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val snapshot = instituteCollection(instituteId, COLLECTION)
                .whereEqualTo("batchId", batchId)
                .whereEqualTo("studentId", studentId)
                .get()
                .await()
            snapshot.documents.forEach { doc ->
                doc.reference.update(
                    mapOf(
                        "status" to "removed",
                        "leftAtMs" to leftAtMs
                    )
                ).await()
            }
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).forEachDocumentPage { documents ->
                db.withTransaction {
                    documents.mapNotNull { it.toEnrollment(instituteId) }
                        .forEach { db.batchStudentDao().enrollStudent(it) }
                }
            }
        } catch (e: Exception) {
            recordException(e)
        }
    }

    suspend fun applyRealtimeChanges(
        db: AppDatabase,
        instituteId: String,
        changes: List<DocumentChange>
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            changes.forEach { change ->
                when (change.type) {
                    DocumentChange.Type.ADDED,
                    DocumentChange.Type.MODIFIED ->
                        change.document.toEnrollment(instituteId)?.let {
                            db.batchStudentDao().enrollStudent(it)
                        }

                    DocumentChange.Type.REMOVED ->
                        db.batchStudentDao().deleteEnrollment(instituteId, change.document.id)
                }
            }
        }
    }

    private fun DocumentSnapshot.toEnrollment(instituteId: String): BatchStudentEntity? {
        val batchId = getString("batchId") ?: return null
        val studentId = getString("studentId") ?: return null
        return BatchStudentEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            batchId = batchId,
            studentId = studentId,
            joinedAtMs = (get("joinedAtMs") as? Number).asLong() ?: 0L,
            status = getString("status") ?: "active",
            leftAtMs = (get("leftAtMs") as? Number).asLong(),
            firstMonthFeePeriod = getString("firstMonthFeePeriod"),
            firstMonthFeeAmount = (get("firstMonthFeeAmount") as? Number).asDouble(),
            customMonthlyFeeAmount = (get("customMonthlyFeeAmount") as? Number).asDouble(),
            customFeeReason = getString("customFeeReason"),
            customFeeEffectiveFromPeriod = getString("customFeeEffectiveFromPeriod"),
            customFeePolicySyncedAtMs = (get("customFeePolicySyncedAtMs") as? Number).asLong()
        )
    }
}

object FinanceSyncHelper {
    private const val FEES = "fees"
    private const val PAYMENTS = "payments"
    private const val RECEIPTS = "receipts"

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            val feeSnapshot = instituteCollection(instituteId, FEES).collectDocumentPages()
            val paymentSnapshot = instituteCollection(instituteId, PAYMENTS).collectDocumentPages()
            val receiptSnapshot = instituteCollection(instituteId, RECEIPTS).collectDocumentPages()
            val reversalSnapshot = instituteCollection(instituteId, "payment_reversals").collectDocumentPages()
            val feeDocuments = feeSnapshot.documents
            val paymentDocuments = paymentSnapshot.documents
            val receiptDocuments = receiptSnapshot.documents
            val reversalDocuments = reversalSnapshot.documents
            val hasAuthoritativeFinanceSnapshot = listOf(
                feeSnapshot,
                paymentSnapshot,
                receiptSnapshot,
                reversalSnapshot
            ).all { it.isServerAuthoritative }

            val fees = feeDocuments.mapNotNull { doc ->
                val studentId = doc.getString("studentId") ?: return@mapNotNull null
                FeeEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    studentId = studentId,
                    batchId = doc.getString("batchId"),
                    feePeriod = doc.getString("feePeriod") ?: "",
                    feeType = doc.getString("feeType") ?: "monthly_fee",
                    dueDateMs = (doc.get("dueDateMs") as? Number).asLong() ?: 0L,
                    baseAmount = (doc.get("baseAmount") as? Number).asDouble() ?: 0.0,
                    discountAmount = (doc.get("discountAmount") as? Number).asDouble() ?: 0.0,
                    lateFeeAmount = (doc.get("lateFeeAmount") as? Number).asDouble() ?: 0.0,
                    totalAmount = (doc.get("totalAmount") as? Number).asDouble() ?: 0.0,
                    paidAmount = (doc.get("paidAmount") as? Number).asDouble() ?: 0.0,
                    dueAmount = (doc.get("dueAmount") as? Number).asDouble() ?: 0.0,
                    status = doc.getString("status") ?: "unpaid",
                    note = doc.getString("note"),
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    cancelledAtMs = (doc.get("cancelledAtMs") as? Number).asLong(),
                    sourceId = doc.getString("sourceId"),
                    businessKey = doc.getString("businessKey"),
                    ledgerVersion = (doc.get("ledgerVersion") as? Number)?.toInt() ?: 0
                )
            }

            val reversals = reversalDocuments.mapNotNull { doc ->
                val paymentId = doc.getString("paymentId") ?: return@mapNotNull null
                val feeId = doc.getString("feeId") ?: return@mapNotNull null
                val studentId = doc.getString("studentId") ?: return@mapNotNull null
                PaymentReversalEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    paymentId = paymentId,
                    feeId = feeId,
                    studentId = studentId,
                    amount = (doc.get("amount") as? Number).asDouble() ?: 0.0,
                    receiptNumber = doc.getString("receiptNumber") ?: "",
                    reason = doc.getString("reason") ?: "Legacy reversal",
                    reversedByUserId = doc.getString("reversedByUserId") ?: "",
                    reversedAtMs = (doc.get("reversedAtMs") as? Number).asLong() ?: 0L,
                    operationId = doc.getString("operationId") ?: doc.id,
                    ledgerVersion = (doc.get("ledgerVersion") as? Number)?.toInt() ?: 1
                )
            }
            val reversedPaymentIds = reversals.map { it.paymentId }.toSet()

            val payments = paymentDocuments.mapNotNull { doc ->
                val feeId = doc.getString("feeId") ?: return@mapNotNull null
                val studentId = doc.getString("studentId") ?: return@mapNotNull null
                PaymentEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    feeId = feeId,
                    studentId = studentId,
                    amount = (doc.get("amount") as? Number).asDouble() ?: 0.0,
                    paymentMethod = doc.getString("paymentMethod") ?: "",
                    transactionId = doc.getString("transactionId"),
                    receiptNumber = doc.getString("receiptNumber") ?: "",
                    paymentDateMs = (doc.get("paymentDateMs") as? Number).asLong() ?: 0L,
                    collectedByUserId = doc.getString("collectedByUserId") ?: "",
                    status = if (doc.id in reversedPaymentIds) "reversed" else doc.getString("status") ?: "completed",
                    note = doc.getString("note"),
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    operationId = doc.getString("operationId"),
                    ledgerVersion = (doc.get("ledgerVersion") as? Number)?.toInt() ?: 0
                )
            }

            val receipts = receiptDocuments.mapNotNull { doc ->
                val paymentId = doc.getString("paymentId") ?: return@mapNotNull null
                val feeId = doc.getString("feeId") ?: return@mapNotNull null
                val studentId = doc.getString("studentId") ?: return@mapNotNull null
                ReceiptEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    paymentId = paymentId,
                    feeId = feeId,
                    studentId = studentId,
                    receiptNumber = doc.getString("receiptNumber") ?: "",
                    receiptDateMs = (doc.get("receiptDateMs") as? Number).asLong() ?: 0L,
                    totalAmount = (doc.get("totalAmount") as? Number).asDouble() ?: 0.0,
                    paidAmount = (doc.get("paidAmount") as? Number).asDouble() ?: 0.0,
                    dueAmount = (doc.get("dueAmount") as? Number).asDouble() ?: 0.0,
                    paymentMethod = doc.getString("paymentMethod") ?: "",
                    receiptText = doc.getString("receiptText"),
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    operationId = doc.getString("operationId"),
                    ledgerVersion = (doc.get("ledgerVersion") as? Number)?.toInt() ?: 0
                )
            }

            val feesById = fees.associateBy { it.id }
            val paymentsById = payments.associateBy { it.id }
            val receiptsByPaymentId = receipts.groupBy { it.paymentId }
            val canonicalBusinessKeys = fees.filter { it.ledgerVersion >= 1 }
                .mapNotNull { fee -> fee.businessKey?.let { fee.instituteId to it } }
            check(canonicalBusinessKeys.size == canonicalBusinessKeys.distinct().size) {
                "Financial reconciliation: duplicate canonical fee business key."
            }
            val canonicalPaymentOperations = payments.filter { it.ledgerVersion >= 1 }
                .mapNotNull { payment -> payment.operationId?.let { payment.instituteId to it } }
            check(canonicalPaymentOperations.size == canonicalPaymentOperations.distinct().size) {
                "Financial reconciliation: duplicate canonical payment operation."
            }
            val canonicalReceiptOperations = receipts.filter { it.ledgerVersion >= 1 }
                .mapNotNull { receipt -> receipt.operationId?.let { receipt.instituteId to it } }
            check(canonicalReceiptOperations.size == canonicalReceiptOperations.distinct().size) {
                "Financial reconciliation: duplicate canonical receipt operation."
            }
            payments.filter { it.ledgerVersion >= 1 }.forEach { payment ->
                val fee = feesById[payment.feeId]
                val matchingReceipts = receiptsByPaymentId[payment.id].orEmpty()
                    .filter { it.ledgerVersion >= 1 }
                check(fee != null && fee.ledgerVersion >= 1 && fee.studentId == payment.studentId) {
                    "Financial reconciliation: canonical payment has no canonical fee."
                }
                check(matchingReceipts.size == 1 &&
                    matchingReceipts.single().feeId == payment.feeId &&
                    matchingReceipts.single().studentId == payment.studentId &&
                    matchingReceipts.single().receiptNumber == payment.receiptNumber) {
                    "Financial reconciliation: canonical payment/receipt mismatch."
                }
            }
            receipts.filter { it.ledgerVersion >= 1 }.forEach { receipt ->
                check((paymentsById[receipt.paymentId]?.ledgerVersion ?: 0) >= 1) {
                    "Financial reconciliation: canonical receipt has no canonical payment."
                }
            }
            reversals.filter { it.ledgerVersion >= 1 }.forEach { reversal ->
                val payment = paymentsById[reversal.paymentId]
                val fee = feesById[reversal.feeId]
                check(payment != null && fee != null && fee.ledgerVersion >= 1 &&
                    payment.feeId == reversal.feeId && payment.studentId == reversal.studentId) {
                    "Financial reconciliation: canonical reversal is orphaned."
                }
            }
            val effectivePaymentsByFee = payments.filter { it.status == "completed" }.groupBy { it.feeId }
            fees.filter { it.ledgerVersion >= 1 }.forEach { fee ->
                val ledgerPaid = effectivePaymentsByFee[fee.id].orEmpty().sumOf { it.amount }
                check(kotlin.math.abs(ledgerPaid - fee.paidAmount) <= 0.001) {
                    "Financial reconciliation: canonical fee aggregate mismatch."
                }
            }
            receipts.filter { it.ledgerVersion >= 1 }
                .groupBy { it.receiptNumber }
                .forEach { (_, groupedReceipts) ->
                    check(groupedReceipts.map { it.studentId }.distinct().size == 1) {
                        "Financial reconciliation: receipt number spans multiple students."
                    }
                }

            db.withTransaction {
                // Firestore is the canonical ledger. A normal upsert-only sync leaves a
                // payment visible in Room after it was deleted in the cloud, causing the
                // next delete attempt to fail with "Payment not found". Only prune after
                // every finance query is server-confirmed; cached/offline snapshots must
                // never erase local history.
                if (hasAuthoritativeFinanceSnapshot) {
                    val remotePaymentIds = payments.mapTo(hashSetOf()) { it.id }
                    db.paymentDao().getAllPaymentsOnce(instituteId)
                        .filterNot { it.id in remotePaymentIds }
                        .forEach { db.paymentDao().deletePaymentById(instituteId, it.id) }

                    val remoteReceiptIds = receipts.mapTo(hashSetOf()) { it.id }
                    db.receiptDao().getAllReceiptsOnce(instituteId)
                        .filterNot { it.id in remoteReceiptIds }
                        .forEach { db.receiptDao().deleteReceiptById(instituteId, it.id) }

                    val remoteReversalIds = reversals.mapTo(hashSetOf()) { it.id }
                    db.financialLedgerDao().getReversals(instituteId)
                        .filterNot { it.id in remoteReversalIds }
                        .forEach { db.financialLedgerDao().deleteReversalById(instituteId, it.id) }
                }
                fees.forEach { fee ->
                    fee.businessKey?.let { businessKey ->
                        val existing = db.feeDao().getFeeByBusinessKey(fee.instituteId, businessKey)
                        check(existing == null || existing.id == fee.id) {
                            "Financial reconciliation: fee business key collision."
                        }
                    }
                    db.feeDao().insertFee(fee)
                }
                payments.forEach { payment ->
                    payment.operationId?.let { operationId ->
                        val existing = db.paymentDao()
                            .getPaymentByOperationId(payment.instituteId, operationId)
                        check(existing == null || existing.id == payment.id) {
                            "Financial reconciliation: payment operation collision."
                        }
                    }
                    db.paymentDao().insertPayment(payment)
                }
                receipts.forEach { receipt ->
                    receipt.operationId?.let { operationId ->
                        val existing = db.receiptDao()
                            .getReceiptByOperationId(receipt.instituteId, operationId)
                        check(existing == null || existing.id == receipt.id) {
                            "Financial reconciliation: receipt operation collision."
                        }
                    }
                    db.receiptDao().insertReceipt(receipt)
                }
                reversals.forEach { reversal ->
                    val existing = db.financialLedgerDao()
                        .getReversalForPayment(reversal.instituteId, reversal.paymentId)
                    check(existing == null || existing.id == reversal.id) {
                        "Financial reconciliation: payment reversal collision."
                    }
                    db.financialLedgerDao().upsertReversal(reversal)
                }
            }

            val completedPaymentIds = payments.filter { it.status == "completed" }.map { it.id }.toSet()
            val receiptPaymentIds = receipts.map { it.paymentId }.toSet()
            val missingReceipts = completedPaymentIds - receiptPaymentIds
            if (missingReceipts.isNotEmpty()) {
                recordException(IllegalStateException(
                    "Financial reconciliation: ${missingReceipts.size} completed payment(s) have no receipt."
                ))
            }
            val paymentsByFee = payments.filter { it.status == "completed" }.groupBy { it.feeId }
            val mismatchedFees = fees.count { fee ->
                val ledgerPaid = paymentsByFee[fee.id].orEmpty().sumOf { it.amount }
                kotlin.math.abs(ledgerPaid - fee.paidAmount) > 0.001
            }
            if (mismatchedFees > 0) {
                recordException(IllegalStateException(
                    "Financial reconciliation: $mismatchedFees fee aggregate(s) mismatch immutable payments."
                ))
            }
        } catch (e: Exception) {
            recordException(e)
        }
    }

    suspend fun applyRealtimeChanges(
        db: AppDatabase,
        instituteId: String,
        collection: String,
        changes: List<DocumentChange>
    ) = FinanceRealtimeChangeApplier.apply(
        db = db,
        instituteId = instituteId,
        collection = collection,
        changes = changes
    )

}

object AttendanceSyncHelper {
    private const val ATTENDANCE = "attendance"
    private const val ABSENT_MESSAGES = "absent_messages"
    private const val STAFF_ATTENDANCE = "staff_attendance"

    suspend fun upsertAttendance(record: AttendanceEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(record.instituteId, ATTENDANCE).document(record.id).set(
                mapOf(
                    "instituteId" to record.instituteId,
                    "batchId" to record.batchId,
                    "studentId" to record.studentId,
                    "attendanceDateMs" to record.attendanceDateMs,
                    "status" to record.status,
                    "note" to record.note,
                    "markedByUserId" to record.markedByUserId,
                    "createdAtMs" to record.createdAtMs,
                    "updatedAtMs" to record.updatedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun deleteAttendance(
        instituteId: String,
        studentId: String,
        batchId: String,
        attendanceDateMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val snapshot = instituteCollection(instituteId, ATTENDANCE)
                .whereEqualTo("studentId", studentId)
                .whereEqualTo("batchId", batchId)
                .whereEqualTo("attendanceDateMs", attendanceDateMs)
                .get().await()
            snapshot.documents.forEach { it.reference.delete().await() }
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun deleteStaffAttendance(
        instituteId: String,
        attendanceId: String
    ) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, STAFF_ATTENDANCE).document(attendanceId).delete().await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun upsertAbsentMessage(message: AbsentMessageEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(message.instituteId, ABSENT_MESSAGES).document(message.id).set(
                mapOf(
                    "instituteId" to message.instituteId,
                    "batchId" to message.batchId,
                    "studentId" to message.studentId,
                    "attendanceDateMs" to message.attendanceDateMs,
                    "messageType" to message.messageType,
                    "messageText" to message.messageText,
                    "sentByUserId" to message.sentByUserId,
                    "status" to message.status,
                    "createdAtMs" to message.createdAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun upsertStaffAttendance(record: StaffAttendanceEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(record.instituteId, STAFF_ATTENDANCE).document(record.id).set(
                mapOf(
                    "instituteId" to record.instituteId,
                    "staffId" to record.staffId,
                    "attendanceDateMs" to record.attendanceDateMs,
                    "status" to record.status,
                    "note" to record.note,
                    "markedByUserId" to record.markedByUserId,
                    "createdAtMs" to record.createdAtMs,
                    "updatedAtMs" to record.updatedAtMs,
                    "entryTimeMs" to record.entryTimeMs,
                    "exitTimeMs" to record.exitTimeMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(
        db: AppDatabase,
        instituteId: String,
        syncStudentAttendance: Boolean = true,
        syncStaffAttendance: Boolean = true
    ) = withContext(Dispatchers.IO) {
        try {
            if (syncStudentAttendance) {
                instituteCollection(instituteId, ATTENDANCE).forEachDocumentPage { documents ->
                    db.withTransaction {
                        documents.mapNotNull { doc ->
                            val batchId = doc.getString("batchId") ?: return@mapNotNull null
                            val studentId = doc.getString("studentId") ?: return@mapNotNull null
                            AttendanceEntity(
                                id = doc.id,
                                instituteId = doc.getString("instituteId") ?: instituteId,
                                batchId = batchId,
                                studentId = studentId,
                                attendanceDateMs = (doc.get("attendanceDateMs") as? Number).asLong() ?: 0L,
                                status = doc.getString("status") ?: "present",
                                note = doc.getString("note"),
                                markedByUserId = doc.getString("markedByUserId") ?: "",
                                createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                                updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                            )
                        }.forEach { db.attendanceDao().insertOrUpdateAttendance(it) }
                    }
                }

                instituteCollection(instituteId, ABSENT_MESSAGES).forEachDocumentPage { documents ->
                    db.withTransaction {
                        documents.mapNotNull { doc ->
                            val batchId = doc.getString("batchId") ?: return@mapNotNull null
                            val studentId = doc.getString("studentId") ?: return@mapNotNull null
                            AbsentMessageEntity(
                                id = doc.id,
                                instituteId = doc.getString("instituteId") ?: instituteId,
                                batchId = batchId,
                                studentId = studentId,
                                attendanceDateMs = (doc.get("attendanceDateMs") as? Number).asLong() ?: 0L,
                                messageType = doc.getString("messageType") ?: "sms",
                                messageText = doc.getString("messageText") ?: "",
                                sentByUserId = doc.getString("sentByUserId") ?: "",
                                status = doc.getString("status") ?: "sent",
                                createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                            )
                        }.forEach { db.absentMessageDao().insertMessage(it) }
                    }
                }
            }

            if (syncStaffAttendance) {
                instituteCollection(instituteId, STAFF_ATTENDANCE).forEachDocumentPage { documents ->
                    db.withTransaction {
                        documents.mapNotNull { doc ->
                            val staffId = doc.getString("staffId") ?: return@mapNotNull null
                            StaffAttendanceEntity(
                                id = doc.id,
                                instituteId = doc.getString("instituteId") ?: instituteId,
                                staffId = staffId,
                                attendanceDateMs = (doc.get("attendanceDateMs") as? Number).asLong() ?: 0L,
                                status = doc.getString("status") ?: "present",
                                note = doc.getString("note"),
                                markedByUserId = doc.getString("markedByUserId") ?: "",
                                createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                                updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                                entryTimeMs = (doc.get("entryTimeMs") as? Number)?.toLong(),
                                exitTimeMs = (doc.get("exitTimeMs") as? Number)?.toLong()
                            )
                        }.forEach { db.staffAttendanceDao().insertOrUpdateAttendance(it) }
                    }
                }
            }
        } catch (e: Exception) {
            recordException(e)
        }
    }

}

private object FinanceRealtimeChangeApplier {

    /**
     * Realtime listeners already contain the changed documents. Apply those changes directly
     * instead of issuing four new full-collection reads for every ledger update.
     */
    suspend fun apply(
        db: AppDatabase,
        instituteId: String,
        collection: String,
        changes: List<DocumentChange>
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            changes.forEach { change ->
                when (collection) {
                    "fees" -> applyFeeChange(db, instituteId, change)
                    "payments" -> applyPaymentChange(db, instituteId, change)
                    "receipts" -> applyReceiptChange(db, instituteId, change)
                    "payment_reversals" -> applyReversalChange(db, instituteId, change)
                }
            }
        }
    }

    private suspend fun applyFeeChange(
        db: AppDatabase,
        instituteId: String,
        change: DocumentChange
    ) {
        when (change.type) {
            DocumentChange.Type.ADDED,
            DocumentChange.Type.MODIFIED ->
                change.document.toFee(instituteId)?.let { db.feeDao().insertFee(it) }

            DocumentChange.Type.REMOVED ->
                db.feeDao().deleteFeeById(instituteId, change.document.id)
        }
    }

    private suspend fun applyPaymentChange(
        db: AppDatabase,
        instituteId: String,
        change: DocumentChange
    ) {
        when (change.type) {
            DocumentChange.Type.ADDED,
            DocumentChange.Type.MODIFIED -> change.document.toPayment(instituteId)?.let { payment ->
                val reversal = db.financialLedgerDao()
                    .getReversalForPayment(instituteId, payment.id)
                db.paymentDao().insertPayment(
                    if (reversal == null) payment else payment.copy(status = "reversed")
                )
            }

            DocumentChange.Type.REMOVED ->
                db.paymentDao().deletePaymentById(instituteId, change.document.id)
        }
    }

    private suspend fun applyReceiptChange(
        db: AppDatabase,
        instituteId: String,
        change: DocumentChange
    ) {
        when (change.type) {
            DocumentChange.Type.ADDED,
            DocumentChange.Type.MODIFIED ->
                change.document.toReceipt(instituteId)?.let { db.receiptDao().insertReceipt(it) }

            DocumentChange.Type.REMOVED ->
                db.receiptDao().deleteReceiptById(instituteId, change.document.id)
        }
    }

    private suspend fun applyReversalChange(
        db: AppDatabase,
        instituteId: String,
        change: DocumentChange
    ) {
        val reversal = change.document.toReversal(instituteId)
        when (change.type) {
            DocumentChange.Type.ADDED,
            DocumentChange.Type.MODIFIED -> if (reversal != null) {
                db.financialLedgerDao().upsertReversal(reversal)
                db.paymentDao().updatePaymentStatus(
                    instituteId,
                    reversal.paymentId,
                    "reversed",
                    reversal.reversedAtMs
                )
            }

            DocumentChange.Type.REMOVED -> {
                db.financialLedgerDao().deleteReversalById(instituteId, change.document.id)
                if (reversal != null) {
                    db.paymentDao().updatePaymentStatus(
                        instituteId,
                        reversal.paymentId,
                        "completed",
                        System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun DocumentSnapshot.toFee(instituteId: String): FeeEntity? {
        val studentId = getString("studentId") ?: return null
        return FeeEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            studentId = studentId,
            batchId = getString("batchId"),
            feePeriod = getString("feePeriod") ?: "",
            feeType = getString("feeType") ?: "monthly_fee",
            dueDateMs = (get("dueDateMs") as? Number).asLong() ?: 0L,
            baseAmount = (get("baseAmount") as? Number).asDouble() ?: 0.0,
            discountAmount = (get("discountAmount") as? Number).asDouble() ?: 0.0,
            lateFeeAmount = (get("lateFeeAmount") as? Number).asDouble() ?: 0.0,
            totalAmount = (get("totalAmount") as? Number).asDouble() ?: 0.0,
            paidAmount = (get("paidAmount") as? Number).asDouble() ?: 0.0,
            dueAmount = (get("dueAmount") as? Number).asDouble() ?: 0.0,
            status = getString("status") ?: "unpaid",
            note = getString("note"),
            createdAtMs = (get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            cancelledAtMs = (get("cancelledAtMs") as? Number).asLong(),
            sourceId = getString("sourceId"),
            businessKey = getString("businessKey"),
            ledgerVersion = (get("ledgerVersion") as? Number)?.toInt() ?: 0
        )
    }

    private fun DocumentSnapshot.toPayment(instituteId: String): PaymentEntity? {
        val feeId = getString("feeId") ?: return null
        val studentId = getString("studentId") ?: return null
        return PaymentEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            feeId = feeId,
            studentId = studentId,
            amount = (get("amount") as? Number).asDouble() ?: 0.0,
            paymentMethod = getString("paymentMethod") ?: "",
            transactionId = getString("transactionId"),
            receiptNumber = getString("receiptNumber") ?: "",
            paymentDateMs = (get("paymentDateMs") as? Number).asLong() ?: 0L,
            collectedByUserId = getString("collectedByUserId") ?: "",
            status = getString("status") ?: "completed",
            note = getString("note"),
            createdAtMs = (get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            operationId = getString("operationId"),
            ledgerVersion = (get("ledgerVersion") as? Number)?.toInt() ?: 0
        )
    }

    private fun DocumentSnapshot.toReceipt(instituteId: String): ReceiptEntity? {
        val paymentId = getString("paymentId") ?: return null
        val feeId = getString("feeId") ?: return null
        val studentId = getString("studentId") ?: return null
        return ReceiptEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            paymentId = paymentId,
            feeId = feeId,
            studentId = studentId,
            receiptNumber = getString("receiptNumber") ?: "",
            receiptDateMs = (get("receiptDateMs") as? Number).asLong() ?: 0L,
            totalAmount = (get("totalAmount") as? Number).asDouble() ?: 0.0,
            paidAmount = (get("paidAmount") as? Number).asDouble() ?: 0.0,
            dueAmount = (get("dueAmount") as? Number).asDouble() ?: 0.0,
            paymentMethod = getString("paymentMethod") ?: "",
            receiptText = getString("receiptText"),
            createdAtMs = (get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            operationId = getString("operationId"),
            ledgerVersion = (get("ledgerVersion") as? Number)?.toInt() ?: 0
        )
    }

    private fun DocumentSnapshot.toReversal(instituteId: String): PaymentReversalEntity? {
        val paymentId = getString("paymentId") ?: return null
        val feeId = getString("feeId") ?: return null
        val studentId = getString("studentId") ?: return null
        return PaymentReversalEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            paymentId = paymentId,
            feeId = feeId,
            studentId = studentId,
            amount = (get("amount") as? Number).asDouble() ?: 0.0,
            receiptNumber = getString("receiptNumber") ?: "",
            reason = getString("reason") ?: "Legacy reversal",
            reversedByUserId = getString("reversedByUserId") ?: "",
            reversedAtMs = (get("reversedAtMs") as? Number).asLong() ?: 0L,
            operationId = getString("operationId") ?: id,
            ledgerVersion = (get("ledgerVersion") as? Number)?.toInt() ?: 1
        )
    }
}

object EnquirySyncHelper {
    private const val COLLECTION = "enquiries"

    suspend fun upsertEnquiry(enquiry: EnquiryEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(enquiry.instituteId, COLLECTION).document(enquiry.id).set(
                mapOf(
                    "instituteId" to enquiry.instituteId,
                    "name" to enquiry.name,
                    "phone" to enquiry.phone,
                    "address" to enquiry.address,
                    "subjectName" to enquiry.subjectName,
                    "enquiryDateMs" to enquiry.enquiryDateMs,
                    "status" to enquiry.status,
                    "followUpDateMs" to enquiry.followUpDateMs,
                    "note" to enquiry.note,
                    "createdAtMs" to enquiry.createdAtMs,
                    "updatedAtMs" to enquiry.updatedAtMs,
                    "archivedAtMs" to enquiry.archivedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun deleteEnquiry(enquiryId: String, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).document(enquiryId).delete().await()
        } catch (e: Exception) {
            recordException(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                val phone = doc.getString("phone") ?: return@mapNotNull null
                val subjectName = doc.getString("subjectName") ?: return@mapNotNull null
                EnquiryEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    name = name,
                    phone = phone,
                    address = doc.getString("address"),
                    subjectName = subjectName,
                    enquiryDateMs = (doc.get("enquiryDateMs") as? Number).asLong() ?: 0L,
                    status = doc.getString("status") ?: "active",
                    followUpDateMs = (doc.get("followUpDateMs") as? Number).asLong(),
                    note = doc.getString("note"),
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    archivedAtMs = (doc.get("archivedAtMs") as? Number).asLong()
                )
            }.forEach { db.enquiryDao().insertEnquiry(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

object ExamSyncHelper {
    private const val EXAMS = "exams"
    private const val RESULTS = "results"

    suspend fun upsertExam(exam: ExamEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(exam.instituteId, EXAMS).document(exam.id).set(
                mapOf(
                    "instituteId" to exam.instituteId,
                    "batchId" to exam.batchId,
                    "examName" to exam.examName,
                    "subject" to exam.subject,
                    "examDateMs" to exam.examDateMs,
                    "totalMarks" to exam.totalMarks,
                    "passingMarks" to exam.passingMarks,
                    "examFeeAmount" to exam.examFeeAmount,
                    "teacherName" to exam.teacherName,
                    "note" to exam.note,
                    "status" to exam.status,
                    "createdAtMs" to exam.createdAtMs,
                    "updatedAtMs" to exam.updatedAtMs,
                    "archivedAtMs" to exam.archivedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun upsertResult(result: ResultEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(result.instituteId, RESULTS).document(result.id).set(
                mapOf(
                    "instituteId" to result.instituteId,
                    "examId" to result.examId,
                    "batchId" to result.batchId,
                    "studentId" to result.studentId,
                    "marksObtained" to result.marksObtained,
                    "grade" to result.grade,
                    "position" to result.position,
                    "remarks" to result.remarks,
                    "published" to result.published,
                    "createdAtMs" to result.createdAtMs,
                    "updatedAtMs" to result.updatedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, EXAMS).get().await().documents.mapNotNull { doc ->
                val batchId = doc.getString("batchId") ?: return@mapNotNull null
                val examName = doc.getString("examName") ?: return@mapNotNull null
                ExamEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    batchId = batchId,
                    examName = examName,
                    subject = doc.getString("subject"),
                    examDateMs = (doc.get("examDateMs") as? Number).asLong() ?: 0L,
                    totalMarks = (doc.get("totalMarks") as? Number).asDouble() ?: 0.0,
                    passingMarks = (doc.get("passingMarks") as? Number).asDouble() ?: 0.0,
                    examFeeAmount = (doc.get("examFeeAmount") as? Number).asDouble() ?: 0.0,
                    teacherName = doc.getString("teacherName"),
                    note = doc.getString("note"),
                    status = doc.getString("status") ?: "scheduled",
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    archivedAtMs = (doc.get("archivedAtMs") as? Number).asLong()
                )
            }.forEach { db.examDao().insertExam(it) }

            instituteCollection(instituteId, RESULTS).get().await().documents.mapNotNull { doc ->
                val examId = doc.getString("examId") ?: return@mapNotNull null
                val batchId = doc.getString("batchId") ?: return@mapNotNull null
                val studentId = doc.getString("studentId") ?: return@mapNotNull null
                ResultEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    examId = examId,
                    batchId = batchId,
                    studentId = studentId,
                    marksObtained = (doc.get("marksObtained") as? Number).asDouble() ?: 0.0,
                    grade = doc.getString("grade"),
                    position = (doc.get("position") as? Number).asLong()?.toInt(),
                    remarks = doc.getString("remarks"),
                    published = doc.get("published").asBooleanCompat() ?: false,
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                )
            }.forEach { db.resultDao().insertOrUpdateResult(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

object ExpenseSyncHelper {
    private const val COLLECTION = "expenses"

    suspend fun upsertExpense(expense: ExpenseEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(expense.instituteId, COLLECTION).document(expense.id).set(
                mapOf(
                    "instituteId" to expense.instituteId,
                    "category" to expense.category,
                    "title" to expense.title,
                    "amount" to expense.amount,
                    "expenseDateMs" to expense.expenseDateMs,
                    "paymentMethod" to expense.paymentMethod,
                    "description" to expense.description,
                    "attachmentUri" to expense.attachmentUri,
                    "createdByUserId" to expense.createdByUserId,
                    "createdAtMs" to expense.createdAtMs,
                    "updatedAtMs" to expense.updatedAtMs,
                    "archivedAtMs" to expense.archivedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents.mapNotNull { doc ->
                val category = doc.getString("category") ?: return@mapNotNull null
                val title = doc.getString("title") ?: return@mapNotNull null
                val createdByUserId = doc.getString("createdByUserId") ?: return@mapNotNull null
                ExpenseEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    category = category,
                    title = title,
                    amount = (doc.get("amount") as? Number).asDouble() ?: 0.0,
                    expenseDateMs = (doc.get("expenseDateMs") as? Number).asLong() ?: 0L,
                    paymentMethod = doc.getString("paymentMethod"),
                    description = doc.getString("description"),
                    attachmentUri = doc.getString("attachmentUri"),
                    createdByUserId = createdByUserId,
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    archivedAtMs = (doc.get("archivedAtMs") as? Number).asLong()
                )
            }.forEach { db.expenseDao().insertExpense(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }

    suspend fun applyRealtimeChanges(
        db: AppDatabase,
        instituteId: String,
        changes: List<DocumentChange>
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            changes.forEach { change ->
                when (change.type) {
                    DocumentChange.Type.ADDED,
                    DocumentChange.Type.MODIFIED ->
                        change.document.toExpense(instituteId)?.let {
                            db.expenseDao().insertExpense(it)
                        }

                    DocumentChange.Type.REMOVED ->
                        db.expenseDao().deleteExpense(instituteId, change.document.id)
                }
            }
        }
    }

    private fun DocumentSnapshot.toExpense(instituteId: String): ExpenseEntity? {
        val category = getString("category") ?: return null
        val title = getString("title") ?: return null
        val createdByUserId = getString("createdByUserId") ?: return null
        return ExpenseEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            category = category,
            title = title,
            amount = (get("amount") as? Number).asDouble() ?: 0.0,
            expenseDateMs = (get("expenseDateMs") as? Number).asLong() ?: 0L,
            paymentMethod = getString("paymentMethod"),
            description = getString("description"),
            attachmentUri = getString("attachmentUri"),
            createdByUserId = createdByUserId,
            createdAtMs = (get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            archivedAtMs = (get("archivedAtMs") as? Number).asLong()
        )
    }
}

object SalarySyncHelper {
    private const val COLLECTION = "salaries"

    internal fun salaryFields(salary: SalaryEntity) = mapOf(
        "instituteId" to salary.instituteId,
        "staffId" to salary.staffId,
        "salaryMonth" to salary.salaryMonth,
        "basicSalary" to salary.basicSalary,
        "bonusAmount" to salary.bonusAmount,
        "deductionAmount" to salary.deductionAmount,
        "advanceAmount" to salary.advanceAmount,
        "netSalary" to salary.netSalary,
        "paidAmount" to salary.paidAmount,
        "paymentMethod" to salary.paymentMethod,
        "paymentDateMs" to salary.paymentDateMs,
        "status" to salary.status,
        "salarySlipNumber" to salary.salarySlipNumber,
        "note" to salary.note,
        "createdAtMs" to salary.createdAtMs,
        "updatedAtMs" to salary.updatedAtMs,
        "cancelledAtMs" to salary.cancelledAtMs,
        "calculationType" to salary.calculationType,
        "calculationSessionIds" to salary.calculationSessionIds
    )

    internal fun expenseFields(expense: ExpenseEntity) = mapOf(
        "instituteId" to expense.instituteId,
        "category" to expense.category,
        "title" to expense.title,
        "amount" to expense.amount,
        "expenseDateMs" to expense.expenseDateMs,
        "paymentMethod" to expense.paymentMethod,
        "description" to expense.description,
        "attachmentUri" to expense.attachmentUri,
        "createdByUserId" to expense.createdByUserId,
        "createdAtMs" to expense.createdAtMs,
        "updatedAtMs" to expense.updatedAtMs,
        "archivedAtMs" to expense.archivedAtMs
    )

    suspend fun upsertSalary(salary: SalaryEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(salary.instituteId, COLLECTION).document(salary.id)
                .set(salaryFields(salary)).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    /**
     * A salary payment changes two financial records. Commit both cloud writes
     * together so an expense can never be recorded without its salary state
     * (or the other way around).
     */
    suspend fun upsertSalaryWithExpense(salary: SalaryEntity, expense: ExpenseEntity): Unit =
        withContext(Dispatchers.IO) {
            try {
                firestore.runBatch { batch ->
                    batch.set(instituteCollection(salary.instituteId, COLLECTION).document(salary.id), salaryFields(salary))
                    batch.set(instituteCollection(expense.instituteId, "expenses").document(expense.id), expenseFields(expense))
                }.await()
            } catch (e: Exception) {
                recordException(e)
                rethrowUnlessAccessDenied(e)
            }
        }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents
                .mapNotNull { it.toSalary(instituteId) }
                .forEach { db.salaryDao().insertSalary(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }

    suspend fun applyRealtimeChanges(
        db: AppDatabase,
        instituteId: String,
        changes: List<DocumentChange>
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            changes.forEach { change ->
                if (change.type != DocumentChange.Type.REMOVED) {
                    change.document.toSalary(instituteId)?.let { db.salaryDao().insertSalary(it) }
                }
            }
        }
    }

    private fun DocumentSnapshot.toSalary(instituteId: String): SalaryEntity? {
        val staffId = getString("staffId") ?: return null
        val status = getString("status") ?: "unpaid"
        val netSalary = (get("netSalary") as? Number).asDouble() ?: 0.0
        val paidAmount = (get("paidAmount") as? Number).asDouble()
            ?: if (status.equals("paid", ignoreCase = true)) netSalary else 0.0
        return SalaryEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            staffId = staffId,
            salaryMonth = getString("salaryMonth") ?: "",
            basicSalary = (get("basicSalary") as? Number).asDouble() ?: 0.0,
            bonusAmount = (get("bonusAmount") as? Number).asDouble() ?: 0.0,
            deductionAmount = (get("deductionAmount") as? Number).asDouble() ?: 0.0,
            advanceAmount = (get("advanceAmount") as? Number).asDouble() ?: 0.0,
            netSalary = netSalary,
            paidAmount = paidAmount.coerceIn(0.0, netSalary),
            paymentMethod = getString("paymentMethod"),
            paymentDateMs = (get("paymentDateMs") as? Number).asLong(),
            status = status,
            salarySlipNumber = getString("salarySlipNumber") ?: "",
            note = getString("note"),
            createdAtMs = (get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            cancelledAtMs = (get("cancelledAtMs") as? Number).asLong(),
            calculationType = getString("calculationType") ?: "monthly",
            calculationSessionIds = getString("calculationSessionIds")?.takeIf { it.isNotBlank() }
        )
    }
}

/** Cloud/Room bridge for teacher class completions and their salary locks. */
object TeachingSessionSyncHelper {
    private const val COLLECTION = "teaching_sessions"

    internal fun sessionFields(session: TeachingSessionEntity) = mapOf(
        "instituteId" to session.instituteId,
        "staffId" to session.staffId,
        "batchId" to session.batchId,
        "sessionKey" to session.sessionKey,
        "subject" to session.subject,
        "sessionDateMs" to session.sessionDateMs,
        "durationMinutes" to session.durationMinutes,
        "salaryTypeSnapshot" to session.salaryTypeSnapshot,
        "rateSnapshot" to session.rateSnapshot,
        "calculatedAmount" to session.calculatedAmount,
        "salaryId" to session.salaryId,
        "note" to session.note,
        "createdByUserId" to session.createdByUserId,
        "createdAtMs" to session.createdAtMs,
        "updatedAtMs" to session.updatedAtMs,
        "deletedAtMs" to session.deletedAtMs
    )

    suspend fun upsertSession(session: TeachingSessionEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(session.instituteId, COLLECTION).document(session.id)
                .set(sessionFields(session)).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    /**
     * Confirms a completed class exactly once, even if another device has an
     * outdated Room cache. The authoritative check is made inside a Firestore
     * transaction before any class data is written.
     */
    suspend fun createSessionIfAvailable(session: TeachingSessionEntity) = withContext(Dispatchers.IO) {
        try {
            val reference = instituteCollection(session.instituteId, COLLECTION).document(session.id)
            firestore.runTransaction { transaction ->
                val existing = transaction.get(reference)
                if (existing.exists() && existing.get("deletedAtMs") == null) {
                    val lockedSalaryId = existing.getString("salaryId")?.takeIf { it.isNotBlank() }
                    throw IllegalStateException(
                        if (lockedSalaryId == null) {
                            "This scheduled class is already completed."
                        } else {
                            "This class is already included in a salary record."
                        }
                    )
                }
                transaction.set(reference, sessionFields(session))
            }.await()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    /**
     * Salary, expense and every included class are committed atomically. The
     * transaction checks the cloud record first, so a stale Room cache on a
     * second device cannot create a second salary for the same staff/month or
     * pay a class that another salary has already locked.
     */
    suspend fun upsertSalaryWithExpenseAndSessions(
        salary: SalaryEntity,
        expense: ExpenseEntity,
        sessions: List<TeachingSessionEntity>,
    ) = withContext(Dispatchers.IO) {
        try {
            val salaryReference = instituteCollection(salary.instituteId, "salaries").document(salary.id)
            val cancellingExistingSalary = salary.cancelledAtMs != null

            // Versions before the deterministic salary id may already have
            // created a salary with a random document id. Check those legacy
            // records first, while the transaction below protects all new
            // concurrent requests through the deterministic document id.
            if (!cancellingExistingSalary) {
                val legacySalary = instituteCollection(salary.instituteId, "salaries")
                    .whereEqualTo("staffId", salary.staffId)
                    .get()
                    .await()
                    .documents
                    .firstOrNull { document ->
                        document.id != salary.id &&
                            document.getString("salaryMonth") == salary.salaryMonth &&
                            document.get("cancelledAtMs") == null
                    }
                if (legacySalary != null) {
                    throw IllegalStateException("Salary already exists for this staff and month.")
                }
            }

            firestore.runTransaction { transaction ->
                val existingSalary = transaction.get(salaryReference)
                if (!cancellingExistingSalary && existingSalary.exists() &&
                    existingSalary.get("cancelledAtMs") == null
                ) {
                    throw IllegalStateException("Salary already exists for this staff and month.")
                }

                // Firestore transactions require every read to happen before
                // the first write. Read and validate every selected class
                // first, then lock all of them below.
                val sessionReferences = sessions.map { session ->
                    session to instituteCollection(session.instituteId, COLLECTION).document(session.id)
                }
                val existingSessions = sessionReferences.map { (session, reference) ->
                    session to transaction.get(reference)
                }
                existingSessions.forEach { (session, existingSession) ->
                    if (!existingSession.exists()) {
                        throw IllegalStateException("A completed class is missing. Refresh and try again.")
                    }
                    if (!cancellingExistingSalary && existingSession.get("deletedAtMs") != null) {
                        throw IllegalStateException("A completed class was removed. Refresh and try again.")
                    }
                    val existingSalaryId = existingSession.getString("salaryId")?.takeIf { it.isNotBlank() }
                    val validLock = if (cancellingExistingSalary) {
                        existingSalaryId == salary.id
                    } else {
                        existingSalaryId == null
                    }
                    if (!validLock) {
                        throw IllegalStateException(
                            if (cancellingExistingSalary) {
                                "A class is linked to another salary. Refresh and try again."
                            } else {
                                "A completed class is already included in another salary."
                            }
                        )
                    }
                }

                transaction.set(salaryReference, SalarySyncHelper.salaryFields(salary))
                transaction.set(
                    instituteCollection(expense.instituteId, "expenses").document(expense.id),
                    SalarySyncHelper.expenseFields(expense)
                )
                sessionReferences.forEach { (session, reference) ->
                    transaction.set(reference, sessionFields(session))
                }
            }.await()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents
                .mapNotNull { it.toTeachingSession(instituteId) }
                .forEach { db.teachingSessionDao().insertSession(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }

    suspend fun applyRealtimeChanges(
        db: AppDatabase,
        instituteId: String,
        changes: List<DocumentChange>
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            changes.forEach { change ->
                when (change.type) {
                    DocumentChange.Type.ADDED,
                    DocumentChange.Type.MODIFIED -> change.document.toTeachingSession(instituteId)?.let {
                        db.teachingSessionDao().insertSession(it)
                    }
                    DocumentChange.Type.REMOVED -> db.teachingSessionDao().deleteSession(instituteId, change.document.id)
                }
            }
        }
    }

    private fun DocumentSnapshot.toTeachingSession(instituteId: String): TeachingSessionEntity? {
        val staffId = getString("staffId") ?: return null
        val batchId = getString("batchId") ?: return null
        val sessionKey = getString("sessionKey") ?: return null
        return TeachingSessionEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            staffId = staffId,
            batchId = batchId,
            sessionKey = sessionKey,
            subject = getString("subject")?.takeIf { it.isNotBlank() },
            sessionDateMs = (get("sessionDateMs") as? Number).asLong() ?: 0L,
            durationMinutes = ((get("durationMinutes") as? Number).asLong() ?: 0L).toInt(),
            salaryTypeSnapshot = getString("salaryTypeSnapshot") ?: "monthly",
            rateSnapshot = (get("rateSnapshot") as? Number).asDouble() ?: 0.0,
            calculatedAmount = (get("calculatedAmount") as? Number).asDouble() ?: 0.0,
            salaryId = getString("salaryId")?.takeIf { it.isNotBlank() },
            note = getString("note")?.takeIf { it.isNotBlank() },
            createdByUserId = getString("createdByUserId") ?: "system",
            createdAtMs = (get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
            deletedAtMs = (get("deletedAtMs") as? Number).asLong()
        )
    }
}

object ReminderTemplateSyncHelper {
    private const val COLLECTION = "reminder_templates"

    suspend fun upsertTemplate(template: ReminderTemplateEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(template.instituteId, COLLECTION).document(template.id).set(
                mapOf(
                    "instituteId" to template.instituteId,
                    "title" to template.title,
                    "type" to template.type,
                    "messageTemplate" to template.messageTemplate,
                    "isDefault" to template.isDefault,
                    "createdAtMs" to template.createdAtMs,
                    "updatedAtMs" to template.updatedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents.mapNotNull { doc ->
                val title = doc.getString("title") ?: return@mapNotNull null
                val type = doc.getString("type") ?: return@mapNotNull null
                val messageTemplate = doc.getString("messageTemplate") ?: return@mapNotNull null
                ReminderTemplateEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    title = title,
                    type = type,
                    messageTemplate = messageTemplate,
                    isDefault = doc.get("isDefault").asBooleanCompat() ?: false,
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                )
            }.forEach { db.reminderTemplateDao().insertTemplate(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

object AuditLogSyncHelper {
    private const val COLLECTION = "audit_logs"

    suspend fun upsertAuditLog(log: AuditLogEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(log.instituteId, COLLECTION).document(log.id).set(
                mapOf(
                    "instituteId" to log.instituteId,
                    "userId" to log.userId,
                    "action" to log.action,
                    "module" to log.module,
                    "description" to log.description,
                    "oldValue" to log.oldValue,
                    "newValue" to log.newValue,
                    "createdAtMs" to log.createdAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).forEachDocumentPage { documents ->
                db.withTransaction {
                    documents.mapNotNull { doc ->
                        val action = doc.getString("action") ?: return@mapNotNull null
                        val module = doc.getString("module") ?: return@mapNotNull null
                        val description = doc.getString("description") ?: return@mapNotNull null
                        AuditLogEntity(
                            id = doc.id,
                            instituteId = doc.getString("instituteId") ?: instituteId,
                            userId = doc.getString("userId"),
                            action = action,
                            module = module,
                            description = description,
                            oldValue = doc.getString("oldValue"),
                            newValue = doc.getString("newValue"),
                            createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                        )
                    }.forEach { db.auditLogDao().insertAuditLog(it) }
                }
            }
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

object SubscriptionPlanSyncHelper {
    private const val COLLECTION = "subscription_plans"

    suspend fun upsertPlans(plans: List<SubscriptionPlanEntity>) = withContext(Dispatchers.IO) {
        try {
            plans.forEach { plan ->
                firestore.collection(COLLECTION).document(plan.id).set(
                    mapOf(
                        "name" to plan.name,
                        "description" to plan.description,
                        "priceBdt" to plan.priceBdt,
                        "priceInr" to plan.priceInr,
                        "maxStudents" to plan.maxStudents,
                        "maxBatches" to plan.maxBatches,
                        "maxUsers" to plan.maxUsers,
                        "maxBranches" to plan.maxBranches,
                        "tag" to plan.tag,
                        "tierLevel" to plan.tierLevel
                    )
                ).await()
            }
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun deletePlan(planId: String) = withContext(Dispatchers.IO) {
        try {
            firestore.collection(COLLECTION).document(planId).delete().await()
        } catch (e: Exception) {
            recordException(e)
            rethrowUnlessAccessDenied(e)
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase) = withContext(Dispatchers.IO) {
        try {
            val plans = firestore.collection(COLLECTION).get().await().documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                val description = doc.getString("description") ?: return@mapNotNull null
                SubscriptionPlanEntity(
                    id = doc.id,
                    name = name,
                    description = description,
                    priceBdt = (doc.get("priceBdt") as? Number).asDouble() ?: 0.0,
                    priceInr = (doc.get("priceInr") as? Number).asDouble() ?: 0.0,
                    maxStudents = (doc.get("maxStudents") as? Number).asLong()?.toInt() ?: 0,
                    maxBatches = (doc.get("maxBatches") as? Number).asLong()?.toInt() ?: 0,
                    maxUsers = (doc.get("maxUsers") as? Number).asLong()?.toInt() ?: 0,
                    maxBranches = (doc.get("maxBranches") as? Number).asLong()?.toInt() ?: 0,
                    tag = doc.getString("tag") ?: "",
                    tierLevel = (doc.get("tierLevel") as? Number).asLong()?.toInt() ?: 0
                )
            }
            // Remote plan records can be an older, partial catalogue. Merge
            // them into Room instead of clearing the published local plans;
            // otherwise a background sync can make the owner pricing screen
            // lose Basic–Scale immediately after it was seeded.
            if (plans.isNotEmpty()) {
                db.subscriptionPlanDao().insertPlans(plans)
            }
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

