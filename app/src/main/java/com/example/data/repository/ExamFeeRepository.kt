package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.ExamEntity
import com.batchfee.edu.data.models.FeeEntity
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

data class ExamFeeCreationResult(
    val exam: ExamEntity,
    val fees: List<FeeEntity>,
    val billedStudentCount: Int
)

/** The server may have completed the operation even when the device lost the response. */
class ExamFeeCreationPendingException(cause: Throwable? = null) : Exception(
    "Could not confirm the exam fee creation. Check your connection, refresh the exam list, and do not submit it again immediately.",
    cause
)

class ExamFeeCreationRejectedException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class ExamFeeRepository(private val db: AppDatabase) {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    suspend fun createExamWithFees(
        instituteId: String,
        batchId: String,
        examName: String,
        subject: String?,
        totalMarks: Double,
        passingMarks: Double,
        examDateMs: Long,
        examFeeAmount: Double,
        teacherName: String?,
        note: String?,
        examId: String = UUID.randomUUID().toString(),
        operationId: String = UUID.randomUUID().toString()
    ): ExamFeeCreationResult = withContext(Dispatchers.IO) {
        val response = try {
            functions.getHttpsCallable("createExamWithFees")
                .call(
                    mapOf(
                        "operationId" to operationId,
                        "examId" to examId,
                        "instituteId" to instituteId,
                        "batchId" to batchId,
                        "examName" to examName,
                        "subject" to subject,
                        "totalMarks" to totalMarks,
                        "passingMarks" to passingMarks,
                        "examDateMs" to examDateMs,
                        "examFeeAmount" to examFeeAmount,
                        "teacherName" to teacherName,
                        "note" to note
                    )
                )
                .await()
        } catch (error: FirebaseFunctionsException) {
            when (error.code) {
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.ALREADY_EXISTS,
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> throw ExamFeeCreationRejectedException(
                    error.message ?: "Exam fee creation was rejected.", error
                )
                else -> throw ExamFeeCreationPendingException(error)
            }
        } catch (error: Exception) {
            throw ExamFeeCreationPendingException(error)
        }

        @Suppress("UNCHECKED_CAST")
        val data = response.data as? Map<String, Any?>
            ?: throw ExamFeeCreationPendingException()
        val exam = data.map("exam")?.toExam() ?: throw ExamFeeCreationPendingException()
        val fees = data.maps("fees").map { it.toFee() }
        val result = ExamFeeCreationResult(
            exam = exam,
            fees = fees,
            billedStudentCount = (data["billedStudentCount"] as? Number)?.toInt() ?: fees.size
        )
        db.withTransaction {
            db.examDao().insertExam(result.exam)
            result.fees.forEach { db.feeDao().insertFee(it) }
        }
        result
    }
}

private fun Map<String, Any?>.toExam() = ExamEntity(
    id = string("id"),
    instituteId = string("instituteId"),
    batchId = string("batchId"),
    examName = string("examName"),
    subject = optionalString("subject"),
    examDateMs = long("examDateMs"),
    totalMarks = double("totalMarks"),
    passingMarks = double("passingMarks"),
    examFeeAmount = double("examFeeAmount"),
    teacherName = optionalString("teacherName"),
    note = optionalString("note"),
    status = string("status"),
    createdAtMs = long("createdAtMs"),
    updatedAtMs = long("updatedAtMs"),
    archivedAtMs = optionalLong("archivedAtMs")
)

private fun Map<String, Any?>.toFee() = FeeEntity(
    id = string("id"),
    instituteId = string("instituteId"),
    studentId = string("studentId"),
    batchId = optionalString("batchId"),
    feePeriod = string("feePeriod"),
    feeType = string("feeType"),
    dueDateMs = long("dueDateMs"),
    baseAmount = double("baseAmount"),
    discountAmount = double("discountAmount"),
    lateFeeAmount = double("lateFeeAmount"),
    totalAmount = double("totalAmount"),
    paidAmount = double("paidAmount"),
    dueAmount = double("dueAmount"),
    status = string("status"),
    note = optionalString("note"),
    createdAtMs = long("createdAtMs"),
    updatedAtMs = long("updatedAtMs"),
    cancelledAtMs = optionalLong("cancelledAtMs"),
    sourceId = optionalString("sourceId"),
    businessKey = optionalString("businessKey"),
    ledgerVersion = (this["ledgerVersion"] as? Number)?.toInt() ?: 0
)

private fun Map<String, Any?>.map(key: String): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?>
}

private fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { item ->
        @Suppress("UNCHECKED_CAST")
        item as? Map<String, Any?>
    }.orEmpty()

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: error("Missing $key in exam fee response.")

private fun Map<String, Any?>.optionalString(key: String): String? = this[key] as? String

private fun Map<String, Any?>.double(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: error("Missing $key in exam fee response.")

private fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: error("Missing $key in exam fee response.")

private fun Map<String, Any?>.optionalLong(key: String): Long? = (this[key] as? Number)?.toLong()
