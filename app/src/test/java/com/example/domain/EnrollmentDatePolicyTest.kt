package com.batchfee.edu.domain

import com.batchfee.edu.data.models.BatchStudentEntity
import org.junit.Assert.*
import org.junit.Test

class EnrollmentDatePolicyTest {
    private fun enrollment(linked: Boolean? = null, joined: Long = 200, frozen: String? = "Sep 2026") =
        BatchStudentEntity("e", "i", "b", "s", joined, "active", null,
            firstMonthFeePeriod = frozen, admissionDateLinked = linked)

    @Test fun explicitPolicyWinsOverMatchingDates() {
        assertFalse(EnrollmentDatePolicy.followsAdmission(enrollment(false, 100), 100))
        assertTrue(EnrollmentDatePolicy.followsAdmission(enrollment(true), 100))
    }

    @Test fun legacyMonthMatchDoesNotBackdateIndependentEnrollment() {
        assertFalse(EnrollmentDatePolicy.followsAdmission(enrollment(), 100))
        assertTrue(EnrollmentDatePolicy.followsAdmission(enrollment(joined = 100), 100))
        assertTrue(EnrollmentDatePolicy.followsAdmission(enrollment(frozen = null), 100))
    }
}
