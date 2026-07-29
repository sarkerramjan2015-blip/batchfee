package com.batchfee.edu.data.firestore

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
                        "leftAtMs" to enrollment.leftAtMs
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
                    leftAtMs = (doc.get("leftAtMs") as? Number).asLong()
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

    suspend fun upsertFee(fee: FeeEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(fee.instituteId, FEES).document(fee.id).set(
                mapOf(
                    "instituteId" to fee.instituteId,
                    "studentId" to fee.studentId,
                    "batchId" to fee.batchId,
                    "feePeriod" to fee.feePeriod,
                    "feeType" to fee.feeType,
                    "dueDateMs" to fee.dueDateMs,
                    "baseAmount" to fee.baseAmount,
                    "discountAmount" to fee.discountAmount,
                    "lateFeeAmount" to fee.lateFeeAmount,
                    "totalAmount" to fee.totalAmount,
                    "paidAmount" to fee.paidAmount,
                    "dueAmount" to fee.dueAmount,
                    "status" to fee.status,
                    "note" to fee.note,
                    "createdAtMs" to fee.createdAtMs,
                    "updatedAtMs" to fee.updatedAtMs,
                    "cancelledAtMs" to fee.cancelledAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun upsertPayment(payment: PaymentEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(payment.instituteId, PAYMENTS).document(payment.id).set(
                mapOf(
                    "instituteId" to payment.instituteId,
                    "feeId" to payment.feeId,
                    "studentId" to payment.studentId,
                    "amount" to payment.amount,
                    "paymentMethod" to payment.paymentMethod,
                    "transactionId" to payment.transactionId,
                    "receiptNumber" to payment.receiptNumber,
                    "paymentDateMs" to payment.paymentDateMs,
                    "collectedByUserId" to payment.collectedByUserId,
                    "status" to payment.status,
                    "note" to payment.note,
                    "createdAtMs" to payment.createdAtMs,
                    "updatedAtMs" to payment.updatedAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun upsertReceipt(receipt: ReceiptEntity) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(receipt.instituteId, RECEIPTS).document(receipt.id).set(
                mapOf(
                    "instituteId" to receipt.instituteId,
                    "paymentId" to receipt.paymentId,
                    "feeId" to receipt.feeId,
                    "studentId" to receipt.studentId,
                    "receiptNumber" to receipt.receiptNumber,
                    "receiptDateMs" to receipt.receiptDateMs,
                    "totalAmount" to receipt.totalAmount,
                    "paidAmount" to receipt.paidAmount,
                    "dueAmount" to receipt.dueAmount,
                    "paymentMethod" to receipt.paymentMethod,
                    "receiptText" to receipt.receiptText,
                    "createdAtMs" to receipt.createdAtMs
                )
            ).await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun deletePayment(paymentId: String, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, PAYMENTS).document(paymentId).delete().await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun deleteReceipt(receiptId: String, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, RECEIPTS).document(receiptId).delete().await()
        } catch (e: Exception) {
            recordException(e)
            throw e
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            instituteCollection(instituteId, FEES).get().await().documents.mapNotNull { doc ->
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
                    cancelledAtMs = (doc.get("cancelledAtMs") as? Number).asLong()
                )
            }.forEach { db.feeDao().insertFee(it) }

            instituteCollection(instituteId, PAYMENTS).get().await().documents.mapNotNull { doc ->
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
                    status = doc.getString("status") ?: "completed",
                    note = doc.getString("note"),
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis(),
                    updatedAtMs = (doc.get("updatedAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                )
            }.forEach { db.paymentDao().insertPayment(it) }

            instituteCollection(instituteId, RECEIPTS).get().await().documents.mapNotNull { doc ->
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
                    createdAtMs = (doc.get("createdAtMs") as? Number).asLong() ?: System.currentTimeMillis()
                )
            }.forEach { db.receiptDao().insertReceipt(it) }
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
            db.subscriptionPlanDao().replaceAll(plans)
        } catch (e: Exception) {
            recordException(e)
        }
    }
}

