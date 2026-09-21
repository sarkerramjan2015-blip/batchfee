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

    private val monthNames = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

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
        val requested = months.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (instituteId.isBlank() || studentId.isBlank() || requested.isEmpty()) return emptyList()
        return computeAll(instituteId, studentId)
            .filter { it.feePeriod.trim().lowercase() in requested }
            .sortedWith(allocationOrder)
    }

    /** Every outstanding monthly period for the student, oldest first. */
    suspend fun dueMonths(
        instituteId: String,
        studentId: String
    ): List<MonthAllocation> = computeAll(instituteId, studentId)
        .sortedWith(allocationOrder)

    private val allocationOrder = compareBy<MonthAllocation>(
        { periodSortKey(it.feePeriod) },
        { it.feePeriod },
        { it.batchId },
    )

    private fun periodSortKey(value: String): Int {
        val covered = MonthlyDueCalculator.billingPeriodsCoveredBy(value).firstOrNull() ?: value
        val month = monthNames.indexOfFirst { covered.startsWith(it, ignoreCase = true) }
        val year = Regex("\\d{4}").find(covered)?.value?.toIntOrNull()
        return if (month >= 0 && year != null) year * 12 + month else Int.MAX_VALUE
    }

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
        val seenAllocationKeys = mutableSetOf<String>()
        for (enrollment in enrollments) {
            val batch = batches[enrollment.batchId] ?: continue
            if (batch.monthlyFeeAmount <= 0.0) continue
            val existingMonthly = existingFees.filter {
                it.batchId == batch.id &&
                    MonthlyDueCalculator.isMonthlyFeeType(it.feeType) &&
                    it.cancelledAtMs == null &&
                    !it.status.equals("cancelled", ignoreCase = true)
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

            // Saved fee rows are deliberately excluded by
            // computeMonthlyOutstandingItems. Add their outstanding amounts
            // explicitly before adding virtual months, otherwise a real fee
            // disappears from Online Payment as soon as it is persisted.
            for (fee in existingMonthly) {
                if (!MonthlyDueCalculator.isMonthlyInstallmentDue(fee.feeType, fee.feePeriod)) continue
                if (!MonthlyDueCalculator.isMonthlyFeeWithinEnrollmentWindow(
                        feePeriod = fee.feePeriod,
                        studentAdmissionDateMs = admissionDateMs,
                        enrollmentJoinedAtMs = enrollment.joinedAtMs,
                        firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                        billingEndedAtMs = enrollment.leftAtMs,
                    )
                ) continue
                val feeDue = (fee.totalAmount - fee.paidAmount).coerceAtLeast(0.0)
                if (feeDue <= 0.0 || !seenAllocationKeys.add("fee:${fee.id}")) continue
                result += MonthAllocation(
                    feeId = fee.id,
                    batchId = batch.id,
                    feePeriod = fee.feePeriod,
                    feeType = fee.feeType,
                    dueDateMs = fee.dueDateMs,
                    baseAmount = fee.baseAmount,
                    discountAmount = fee.discountAmount,
                    lateFeeAmount = fee.lateFeeAmount,
                    dueAmount = feeDue,
                )
            }

            for (item in items) {
                if (item.outstanding <= 0.0) continue
                val month = item.period
                if (!seenAllocationKeys.add("virtual:${batch.id}:$month")) continue
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
        return result
    }
}
