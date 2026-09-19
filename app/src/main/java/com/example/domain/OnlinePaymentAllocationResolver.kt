package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Resolves the current per-month due allocations for an online payment request.
 * Mirrors the student fee screen's billing rules (MonthlyDueCalculator) but is
 * used by the owner review screen to build server-validated collection allocations.
 */
object OnlinePaymentAllocationResolver {

    data class MonthAllocation(
        val feeId: String?,
        val batchId: String,
        val feePeriod: String,
        val feeType: String,
        val dueDateMs: Long,
        val baseAmount: Double,
        val discountAmount: Double,
        val lateFeeAmount: Double,
        val dueAmount: Double
    )

    suspend fun resolveMonths(
        instituteId: String,
        studentId: String,
        months: List<String>
    ): List<MonthAllocation> {
        val requested = months.map { it.trim() }.filter { it.isNotBlank() }
        if (instituteId.isBlank() || studentId.isBlank() || requested.isEmpty()) return emptyList()
        val all = computeAll(instituteId, studentId)
        return requested.mapNotNull { month ->
            all.firstOrNull { it.feePeriod.equals(month, ignoreCase = true) }
        }
    }

    /** Every outstanding monthly period for the student, oldest first. */
    suspend fun dueMonths(
        instituteId: String,
        studentId: String
    ): List<MonthAllocation> = computeAll(instituteId, studentId)
        .sortedBy { it.feePeriod }

    private suspend fun computeAll(
        instituteId: String,
        studentId: String
    ): List<MonthAllocation> {
        val cloud = FirebaseFirestore.getInstance()
        val instituteRef = cloud.collection("institutes").document(instituteId)
        val studentSnap = instituteRef.collection("students").document(studentId).get(Source.SERVER).await()
        val admissionDateMs = (studentSnap.get("admissionDateMs") as? Number)?.toLong() ?: 0L
        val enrollmentSnap = instituteRef.collection("batch_students")
            .whereEqualTo("studentId", studentId).get(Source.SERVER).await()
        val batchSnap = instituteRef.collection("batches").get(Source.SERVER).await()
        val feeSnap = instituteRef.collection("fees")
            .whereEqualTo("studentId", studentId).get(Source.SERVER).await()

        val batches = batchSnap.documents.mapNotNull { doc ->
            val id = doc.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to StudentBillingBatch(
                id = id,
                name = doc.getString("name") ?: "Batch",
                monthlyFeeAmount = doc.getDouble("monthlyFeeAmount") ?: 0.0,
                admissionFeeAmount = doc.getDouble("admissionFeeAmount") ?: 0.0,
                billingMode = doc.getString("billingMode") ?: "monthly",
                courseFeeAmount = doc.getDouble("courseFeeAmount") ?: 0.0,
            )
        }.toMap()

        val enrollments = enrollmentSnap.documents.mapNotNull { doc ->
            doc.getString("batchId")?.takeIf { it.isNotBlank() }?.let { batchId ->
                StudentBillingEnrollment(
                    batchId = batchId,
                    status = doc.getString("status") ?: "active",
                    joinedAtMs = (doc.get("joinedAtMs") as? Number)?.toLong() ?: 0L,
                    leftAtMs = (doc.get("leftAtMs") as? Number)?.toLong(),
                    firstMonthFeePeriod = doc.getString("firstMonthFeePeriod"),
                    firstMonthFeeAmount = (doc.get("firstMonthFeeAmount") as? Number)?.toDouble(),
                    customMonthlyFeeAmount = (doc.get("customMonthlyFeeAmount") as? Number)?.toDouble(),
                    customFeeEffectiveFromPeriod = doc.getString("customFeeEffectiveFromPeriod"),
                    customFeePolicyTimeline = doc.getString("customFeePolicyTimeline"),
                )
            }
        }

        val existingFees = feeSnap.documents.map { doc ->
            FeeEntity(
                id = doc.id,
                instituteId = instituteId,
                studentId = studentId,
                batchId = doc.getString("batchId"),
                feePeriod = doc.getString("feePeriod").orEmpty(),
                feeType = doc.getString("feeType") ?: "monthly_fee",
                dueDateMs = (doc.get("dueDateMs") as? Number)?.toLong() ?: 0L,
                baseAmount = doc.getDouble("baseAmount") ?: 0.0,
                discountAmount = doc.getDouble("discountAmount") ?: 0.0,
                lateFeeAmount = doc.getDouble("lateFeeAmount") ?: 0.0,
                totalAmount = doc.getDouble("totalAmount") ?: 0.0,
                paidAmount = doc.getDouble("paidAmount") ?: 0.0,
                dueAmount = doc.getDouble("dueAmount") ?: 0.0,
                status = doc.getString("status") ?: "unpaid",
                note = doc.getString("note"),
                createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: 0L,
                updatedAtMs = (doc.get("updatedAtMs") as? Number)?.toLong() ?: 0L,
                cancelledAtMs = (doc.get("cancelledAtMs") as? Number)?.toLong(),
            )
        }

        val result = mutableListOf<MonthAllocation>()
        val seen = mutableSetOf<String>()
        for (enrollment in enrollments) {
            val batch = batches[enrollment.batchId] ?: continue
            if (batch.monthlyFeeAmount <= 0.0) continue
            val existingMonthly = existingFees.filter {
                it.batchId == batch.id && MonthlyDueCalculator.isMonthlyFeeType(it.feeType)
            }
            val billingStartMs = MonthlyDueCalculator.effectiveBillingStartMs(
                admissionDateMs,
                enrollment.joinedAtMs,
                enrollment.firstMonthFeePeriod,
            )
            val items = MonthlyDueCalculator.computeMonthlyOutstandingItems(
                admissionDateMs = billingStartMs,
                monthlyFeeAmount = batch.monthlyFeeAmount,
                batchId = batch.id,
                batchName = batch.name,
                existingMonthlyFees = existingMonthly,
                firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                firstMonthFeeAmount = enrollment.firstMonthFeeAmount,
                customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                customFeeEffectiveFromPeriod = enrollment.customFeeEffectiveFromPeriod,
                customFeePolicyTimeline = enrollment.customFeePolicyTimeline,
                billingEndedAtMs = enrollment.leftAtMs,
            )
            for (item in items) {
                if (item.outstanding <= 0.0) continue
                val month = item.period
                if (!seen.add(month)) continue
                val existingFee = existingFees.firstOrNull {
                    it.batchId == batch.id &&
                        it.feePeriod.equals(month, ignoreCase = true) &&
                        it.cancelledAtMs == null
                }
                if (existingFee != null) {
                    val feeDue = (existingFee.totalAmount - existingFee.paidAmount).coerceAtLeast(0.0)
                    if (feeDue <= 0.0) continue
                    result += MonthAllocation(
                        feeId = existingFee.id,
                        batchId = batch.id,
                        feePeriod = month,
                        feeType = existingFee.feeType,
                        dueDateMs = existingFee.dueDateMs,
                        baseAmount = existingFee.baseAmount,
                        discountAmount = existingFee.discountAmount,
                        lateFeeAmount = existingFee.lateFeeAmount,
                        dueAmount = feeDue,
                    )
                } else {
                    result += MonthAllocation(
                        feeId = null,
                        batchId = batch.id,
                        feePeriod = month,
                        feeType = "monthly_fee",
                        dueDateMs = System.currentTimeMillis(),
                        baseAmount = item.monthlyFeeAmount,
                        discountAmount = 0.0,
                        lateFeeAmount = 0.0,
                        dueAmount = item.outstanding,
                    )
                }
            }
        }
        return result
    }
}
