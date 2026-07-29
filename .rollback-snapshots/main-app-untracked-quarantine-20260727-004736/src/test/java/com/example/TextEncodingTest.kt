package com.batchfee.edu

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class TextEncodingTest {
    private data class Payload(val text: String)

    @Test
    fun validUnicodePunctuationAndBanglaRoundTripThroughJson() {
        val expected = "বাংলা • ৳ — ’"
        val payload = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(Payload::class.java)
            .fromJson("{\"text\":\"$expected\"}")

        assertEquals(expected, payload?.text)
    }

    @Test
    fun expectedMetadataAndShareTextUseUnicodeCharacters() {
        val metadata = "Batch Fee • Monthly • 1000"
        val monthSummary = "1 Month • Running Month"
        val result = "Result — Shajeda Akter Azmi"
        val shareFooter = "Thank you • ICT TOPPERS"

        assertEquals(2, metadata.count { it == '•' })
        assertEquals(1, monthSummary.count { it == '•' })
        assertEquals(0x2014, result.codePointAt(result.indexOf('—')))
        assertEquals(1, shareFooter.count { it == '•' })
    }
}
