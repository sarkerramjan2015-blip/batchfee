package com.batchfee.edu.data.firestore

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
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
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val firestore by lazy { FirebaseFirestore.getInstance() }

private fun instituteCollection(instituteId: String, name: String) =
    firestore.collection("institutes").document(instituteId).collection(name)

private fun recordException(e: Exception) {
    FirebaseCrashlytics.getInstance().recordException(e)
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
            throw e
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
            throw e
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            val snapshot = instituteCollection(instituteId, COLLECTION).get().await()
            snapshot.documents.mapNotNull { doc ->
                val batchId = doc.getString("batchId") ?: return@mapNotNull null
                val studentId = doc.getString("studentId") ?: return@mapNotNull null
                BatchStudentEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    batchId = batchId,
                    studentId = studentId,
                    joinedAtMs = (doc.get("joinedAtMs") as? Number).asLong() ?: 0L,
                    status = doc.getString("status") ?: "active",
                    leftAtMs = (doc.get("leftAtMs") as? Number).asLong(),
                    firstMonthFeePeriod = doc.getString("firstMonthFeePeriod"),
                    firstMonthFeeAmount = (doc.get("firstMonthFeeAmount") as? Number).asDouble(),
                    customMonthlyFeeAmount = (doc.get("customMonthlyFeeAmount") as? Number).asDouble(),
                    customFeeReason = doc.getString("customFeeReason"),
                    customFeeEffectiveFromPeriod = doc.getString("customFeeEffectiveFromPeriod"),
                    customFeePolicySyncedAtMs = (doc.get("customFeePolicySyncedAtMs") as? Number).asLong()
                )
            }.forEach { db.batchStudentDao().enrollStudent(it) }
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

object FinanceSyncHelper {
    private const val FEES = "fees"
    private const val PAYMENTS = "payments"
    private const val RECEIPTS = "receipts"

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            val feeDocuments = instituteCollection(instituteId, FEES).get().await().documents
            val paymentDocuments = instituteCollection(instituteId, PAYMENTS).get().await().documents
            val receiptDocuments = instituteCollection(instituteId, RECEIPTS).get().await().documents
            val reversalDocuments = instituteCollection(instituteId, "payment_reversals").get().await().documents

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
            throw e
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
            throw e
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
            throw e
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
                    "updatedAtMs" to record.updatedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, ATTENDANCE).get().await().documents.mapNotNull { doc ->
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

            instituteCollection(instituteId, ABSENT_MESSAGES).get().await().documents.mapNotNull { doc ->
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

            instituteCollection(instituteId, STAFF_ATTENDANCE).get().await().documents.mapNotNull { doc ->
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
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                )
            }.forEach { db.staffAttendanceDao().insertOrUpdateAttendance(it) }
        } catch (e: Exception) {
            recordException(e)
        }
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
            throw e
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
            throw e
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
            throw e
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
            throw e
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
}

object SalarySyncHelper {
    private const val COLLECTION = "salaries"

    suspend fun upsertSalary(salary: SalaryEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(salary.instituteId, COLLECTION).document(salary.id).set(
                mapOf(
                    "instituteId" to salary.instituteId,
                    "staffId" to salary.staffId,
                    "salaryMonth" to salary.salaryMonth,
                    "basicSalary" to salary.basicSalary,
                    "bonusAmount" to salary.bonusAmount,
                    "deductionAmount" to salary.deductionAmount,
                    "advanceAmount" to salary.advanceAmount,
                    "netSalary" to salary.netSalary,
                    "paymentMethod" to salary.paymentMethod,
                    "paymentDateMs" to salary.paymentDateMs,
                    "status" to salary.status,
                    "salarySlipNumber" to salary.salarySlipNumber,
                    "note" to salary.note,
                    "createdAtMs" to salary.createdAtMs,
                    "updatedAtMs" to salary.updatedAtMs,
                    "cancelledAtMs" to salary.cancelledAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents.mapNotNull { doc ->
                val staffId = doc.getString("staffId") ?: return@mapNotNull null
                SalaryEntity(
                    id = doc.id,
                    instituteId = doc.getString("instituteId") ?: instituteId,
                    staffId = staffId,
                    salaryMonth = doc.getString("salaryMonth") ?: "",
                    basicSalary = (doc.get("basicSalary") as? Number).asDouble() ?: 0.0,
                    bonusAmount = (doc.get("bonusAmount") as? Number).asDouble() ?: 0.0,
                    deductionAmount = (doc.get("deductionAmount") as? Number).asDouble() ?: 0.0,
                    advanceAmount = (doc.get("advanceAmount") as? Number).asDouble() ?: 0.0,
                    netSalary = (doc.get("netSalary") as? Number).asDouble() ?: 0.0,
                    paymentMethod = doc.getString("paymentMethod"),
                    paymentDateMs = (doc.get("paymentDateMs") as? Number).asLong(),
                    status = doc.getString("status") ?: "unpaid",
                    salarySlipNumber = doc.getString("salarySlipNumber") ?: "",
                    note = doc.getString("note"),
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    cancelledAtMs = (doc.get("cancelledAtMs") as? Number).asLong()
                )
            }.forEach { db.salaryDao().insertSalary(it) }
        } catch (e: Exception) {
            recordException(e)
        }
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
            throw e
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
            throw e
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, COLLECTION).get().await().documents.mapNotNull { doc ->
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
            throw e
        }
    }

    suspend fun deletePlan(planId: String) = withContext(Dispatchers.IO) {
        try {
            firestore.collection(COLLECTION).document(planId).delete().await()
        } catch (e: Exception) {
            recordException(e)
            throw e
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

