package com.example.domain

import com.batchfee.edu.domain.InstituteContactNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstituteContactNumberTest {
    @Test
    fun normalizesCommonBangladeshFormats() {
        val expected = "+8801712345678"
        assertEquals(expected, InstituteContactNumber.normalizeBangladesh("01712345678"))
        assertEquals(expected, InstituteContactNumber.normalizeBangladesh("1712345678"))
        assertEquals(expected, InstituteContactNumber.normalizeBangladesh("+880 1712-345678"))
    }

    @Test
    fun rejectsInvalidBangladeshNumbers() {
        assertNull(InstituteContactNumber.normalizeBangladesh("01234567890"))
        assertNull(InstituteContactNumber.normalizeBangladesh("17123"))
        assertNull(InstituteContactNumber.normalizeBangladesh(""))
    }

    @Test
    fun legacyBlankFieldsFallbackWithoutOverwritingDistinctValues() {
        assertEquals(
            "+8801812345678",
            InstituteContactNumber.primary(null, "01812345678")
        )
        assertEquals(
            "+8801912345678",
            InstituteContactNumber.whatsapp("01712345678", "01912345678")
        )
    }
}
