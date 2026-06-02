package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun mainActivityClassNameIsStable() {
        assertEquals("com.example.MainActivity", MainActivity::class.java.name)
    }
}
