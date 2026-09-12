package com.batchfee.edu.domain

import com.batchfee.edu.data.models.BatchStudentEntity

object EnrollmentDatePolicy {
    fun followsAdmission(enrollment: BatchStudentEntity, previousAdmissionDateMs: Long): Boolean =
        enrollment.admissionDateLinked ?: (
            enrollment.firstMonthFeePeriod.isNullOrBlank() ||
                (previousAdmissionDateMs > 0L && enrollment.joinedAtMs == previousAdmissionDateMs)
            )
}
