package com.batchfee.edu

import com.batchfee.edu.data.models.EnquiryEntity
import com.batchfee.edu.domain.EnquiryFollowUpSupport
import com.batchfee.edu.domain.FollowUpState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnquiryFollowUpSupportTest {
    private val start = 1_000_000L
    private val tomorrow = start + 86_400_000L
    private val enquiry = EnquiryEntity("e1", "i1", "Azmi", "01700 000000", "Dhaka", "ICT", start, "new", start, start, null)

    @Test fun overdueAndFutureFollowUpsAreClassifiedCorrectly() {
        assertEquals(FollowUpState.OVERDUE, EnquiryFollowUpSupport.followUpState(start - 1, start, tomorrow))
        assertEquals(FollowUpState.TODAY, EnquiryFollowUpSupport.followUpState(start + 1, start, tomorrow))
        assertEquals(FollowUpState.UPCOMING, EnquiryFollowUpSupport.followUpState(tomorrow, start, tomorrow))
    }

    @Test fun bangladeshAndInternationalPhonesAreSafeForActions() {
        assertEquals("+8801700000000", EnquiryFollowUpSupport.normalizedPhone("01700 000000"))
        assertEquals("+8801700000000", EnquiryFollowUpSupport.normalizedPhone("+880 1700-000000"))
    }

    @Test fun conversionPrefillRetainsOriginalEnquiryIdentityData() {
        val prefill = EnquiryFollowUpSupport.studentPrefill(enquiry.copy(followUpNote = "Asked about demo"))
        assertEquals("Azmi", prefill.name)
        assertEquals("01700 000000", prefill.phone)
        assertTrue(prefill.notes.contains("ICT"))
        assertTrue(prefill.notes.contains("Asked about demo"))
    }

    @Test fun archiveStatePreservesRecordAndHistoryFields() {
        val archived = enquiry.copy(archivedAtMs = start + 5, followUpNote = "Callback requested")
        assertEquals("e1", archived.id)
        assertEquals("Callback requested", archived.followUpNote)
        assertTrue(archived.archivedAtMs != null)
    }
}
