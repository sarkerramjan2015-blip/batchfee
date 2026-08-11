package com.example.domain

import com.batchfee.edu.domain.StudentIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentIdGeneratorTest {
    @Test
    fun generatedIdsUseGlobalLoginFormatAndDoNotRepeat() {
        val ids = List(10_000) { StudentIdGenerator.generate() }

        assertTrue(ids.all { it.matches(Regex("BF-(?:[A-F0-9]{4}-){4}[A-F0-9]{4}")) })
        assertEquals(ids.size, ids.toSet().size)
    }
}
