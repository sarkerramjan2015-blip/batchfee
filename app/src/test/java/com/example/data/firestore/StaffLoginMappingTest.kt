package com.batchfee.edu.data.firestore

import org.junit.Assert.*
import org.junit.Test

class StaffLoginMappingTest {
    @Test fun preservesTeacherCompensationOnFreshDevice() {
        val row = StaffSyncHelper.StaffFirestoreData(staffCode = "STF123", staffCategory = "teacher",
            salaryType = "per_hour", perHourRate = 750.0, perClassRate = 500.0,
            subjects = "ICT", assignedBatchIds = "b1", permissions = "view_batch")
            .toEntity("uid", "institute", 123)
        assertEquals("teacher", row.staffCategory)
        assertEquals("per_hour", row.salaryType)
        assertEquals(750.0, row.perHourRate, 0.0)
        assertEquals(500.0, row.perClassRate, 0.0)
        assertEquals("ICT", row.subjects)
        assertEquals("b1", row.assignedBatchIds)
        assertEquals("institute", row.instituteId)
    }
}
