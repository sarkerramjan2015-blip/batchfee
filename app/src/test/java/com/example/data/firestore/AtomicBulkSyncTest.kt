package com.batchfee.edu.data.firestore

import org.junit.Test

class AtomicBulkSyncTest {
    @Test fun failedCloudCommitDoesNotModifyLocalData() = kotlinx.coroutines.runBlocking {
        var mirrored = false
        try {
            AtomicBulkSync.commitThenMirror({ throw IllegalStateException("permission denied") }, { mirrored = true })
            org.junit.Assert.fail("Expected commit failure")
        } catch (_: IllegalStateException) {}
        org.junit.Assert.assertFalse(mirrored)
    }
    @Test fun localMirrorRunsOnlyAfterAcknowledgement() = kotlinx.coroutines.runBlocking {
        val stages = mutableListOf<String>()
        AtomicBulkSync.commitThenMirror({ stages += "cloud" }, { stages += "room" })
        org.junit.Assert.assertEquals(listOf("cloud", "room"), stages)
    }
    @Test fun accepts400AttendanceWrites() {
        AtomicBulkSync.validate(List(400) { "tenant" }, List(400) { "student-$it" })
    }
    @Test fun accepts399ResultsPlusExam() {
        AtomicBulkSync.validate(List(399) { "tenant" }, List(399) { "student-$it" }, 1)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOverflowBeforeWriting() {
        AtomicBulkSync.validate(List(400) { "tenant" }, List(400) { "student-$it" }, 1)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMixedTenants() {
        AtomicBulkSync.validate(listOf("a", "b"), listOf("one", "two"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateBusinessKeys() {
        AtomicBulkSync.validate(listOf("a", "a"), listOf("same", "same"))
    }
}
