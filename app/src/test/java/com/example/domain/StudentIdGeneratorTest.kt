package com.example.domain

import com.batchfee.edu.domain.StudentIdGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentIdGeneratorTest {
    @Test
    fun generatedIdsUseShortReadableFormat() {
        val ids = List(100) { StudentIdGenerator.generate() }

        assertTrue(ids.all { it.matches(Regex("ST-[0-9]{6}")) })
        assertTrue(ids.all(StudentIdGenerator::isValid))
    }

    @Test
    fun manualIdsAreNormalizedAndValidated() {
        assertTrue(StudentIdGenerator.isValid(" st-1025 "))
        assertTrue(StudentIdGenerator.isValid("STD-1214"))
        assertFalse(StudentIdGenerator.isValid("ST 1025"))
        assertFalse(StudentIdGenerator.isValid("ST-@1025"))
        assertFalse(StudentIdGenerator.isValid("A"))
    }
}
