package com.batchfee.edu

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun mainActivityClassNameIsStable() {
        assertEquals("com.batchfee.edu.MainActivity", MainActivity::class.java.name)
    }
}

