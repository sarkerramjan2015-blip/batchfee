package com.batchfee.edu.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneInputFieldTest {
    @Test
    fun bangladeshNumbersKeepTheCorrectCountryCodeAndLocalPart() {
        assertEquals("+880 1518657869", formatPhoneForDisplay("+8801518657869"))
        assertEquals("+880 1518657869", formatPhoneForDisplay("01518657869"))
        assertEquals("+880 1518657869", formatPhoneForDisplay("+88001518657869"))
    }

    @Test
    fun whatsappNumbersAreConvertedToInternationalDigits() {
        assertEquals("8801518657869", normalizeWhatsAppNumber("+8801518657869"))
        assertEquals("8801518657869", normalizeWhatsAppNumber("01518657869"))
        assertEquals("8801518657869", normalizeWhatsAppNumber("1518657869"))
        assertEquals("919876543210", normalizeWhatsAppNumber("+919876543210"))
        assertEquals("447700900123", normalizeWhatsAppNumber("00447700900123"))
    }
}
