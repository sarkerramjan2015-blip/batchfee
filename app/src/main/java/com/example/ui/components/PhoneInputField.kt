package com.batchfee.edu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val AccentRed     = Color(0xFFEF4444)

data class CountryCode(val code: String, val flag: String, val name: String)

val COUNTRY_CODES = listOf(
    CountryCode("+880", "🇧🇩", "Bangladesh"),
    CountryCode("+91", "🇮🇳", "India"),
    CountryCode("+1", "🇺🇸", "USA / Canada"),
    CountryCode("+44", "🇬🇧", "UK"),
    CountryCode("+61", "🇦🇺", "Australia"),
    CountryCode("+971", "🇦🇪", "UAE"),
    CountryCode("+966", "🇸🇦", "Saudi Arabia"),
    CountryCode("+974", "🇶🇦", "Qatar"),
    CountryCode("+965", "🇰🇼", "Kuwait"),
    CountryCode("+968", "🇴🇲", "Oman"),
    CountryCode("+973", "🇧🇭", "Bahrain"),
    CountryCode("+60", "🇲🇾", "Malaysia"),
    CountryCode("+65", "🇸🇬", "Singapore"),
    CountryCode("+92", "🇵🇰", "Pakistan"),
    CountryCode("+94", "🇱🇰", "Sri Lanka"),
    CountryCode("+977", "🇳🇵", "Nepal"),
    CountryCode("+81", "🇯🇵", "Japan"),
    CountryCode("+82", "🇰🇷", "South Korea"),
    CountryCode("+86", "🇨🇳", "China"),
    CountryCode("+49", "🇩🇪", "Germany"),
    CountryCode("+33", "🇫🇷", "France"),
    CountryCode("+39", "🇮🇹", "Italy"),
    CountryCode("+34", "🇪🇸", "Spain"),
    CountryCode("+7", "🇷🇺", "Russia"),
    CountryCode("+55", "🇧🇷", "Brazil"),
)

@Composable
fun PhoneInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Phone Number",
    isError: Boolean = false,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    // Parse existing value: "+8801712345678" → code="+880", local="1712345678"
    val parsed = remember(value) { parsePhoneNumber(value) }
    var showPicker by remember { mutableStateOf(false) }
    var selectedCode by remember(value) { mutableStateOf(parsed.first) }
    var localNumber by remember(value) { mutableStateOf(parsed.second) }

    fun emit() {
        val clean = normalizeLocalNumber(selectedCode, localNumber)
        onValueChange(if (clean.isEmpty()) "" else "$selectedCode$clean")
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Country code dropdown
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBgAlt)
                    .border(1.dp, if (isError) AccentRed else BorderSub, RoundedCornerShape(12.dp))
                    .clickable { showPicker = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedCode, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }

            // Local number field
            OutlinedTextField(
                value = localNumber,
                onValueChange = {
                    val digits = it.filter { c -> c.isDigit() || c == '+' || c == '-' || c == ' ' }
                    if (digits.length <= 15) {
                        localNumber = digits
                        emit()
                    }
                },
                isError = isError,
                supportingText = supportingText?.let { { Text(it, color = AccentRed, fontSize = 11.sp) } },
                modifier = Modifier.weight(1f),
                singleLine = singleLine,
                textStyle = TextStyle(color = TextWhite, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = BorderSub,
                    errorBorderColor = AccentRed,
                    focusedContainerColor = CardBgAlt,
                    unfocusedContainerColor = CardBgAlt,
                    errorContainerColor = CardBgAlt,
                    cursorColor = ElectricBlue
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }

    if (showPicker) {
        Dialog(onDismissRequest = { showPicker = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBgAlt,
                modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.65f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select Country Code", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(state = rememberLazyListState()) {
                        items(COUNTRY_CODES) { cc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCode = cc.code
                                        showPicker = false
                                        emit()
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cc.flag, fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(cc.name, color = TextWhite, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(cc.code, color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            if (cc != COUNTRY_CODES.last()) {
                                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parsePhoneNumber(full: String): Pair<String, String> {
    if (full.isBlank()) return Pair("+880", "")
    val compact = full.trim().replace(Regex("[\\s()-]"), "")
    val matchedCountryCode = COUNTRY_CODES
        .map { it.code }
        .sortedByDescending { it.length }
        .firstOrNull { compact.startsWith(it) }

    return if (matchedCountryCode != null) {
        Pair(matchedCountryCode, normalizeLocalNumber(matchedCountryCode, compact.removePrefix(matchedCountryCode)))
    } else {
        // Backward compatibility: older local numbers are Bangladesh numbers.
        Pair("+880", normalizeLocalNumber("+880", compact))
    }
}

private fun normalizeLocalNumber(countryCode: String, rawNumber: String): String {
    var digits = rawNumber.filter(Char::isDigit)
    if (countryCode == "+880") {
        // Bangladesh local numbers are often entered as 01XXXXXXXXX, while
        // the country code already supplies the leading 0 internationally.
        if (digits.startsWith("880")) digits = digits.removePrefix("880")
        if (digits.startsWith("0")) digits = digits.removePrefix("0")
    }
    return digits
}

fun formatPhoneForDisplay(raw: String?): String {
    if (raw.isNullOrBlank()) return "N/A"
    val parsed = parsePhoneNumber(raw)
    return "${parsed.first} ${parsed.second}"
}

fun buildWhatsAppUrl(phone: String?, message: String): String {
    val encoded = java.net.URLEncoder.encode(message, "UTF-8")
    val cleanNumber = normalizeWhatsAppNumber(phone)
    return if (cleanNumber != null) "https://wa.me/$cleanNumber?text=$encoded"
           else "https://wa.me/?text=$encoded"
}

/** Returns digits in the international format required by WhatsApp's wa.me links. */
fun normalizeWhatsAppNumber(phone: String?): String? {
    val digits = phone?.filter(Char::isDigit).orEmpty()
    if (digits.isBlank()) return null
    return when {
        digits.startsWith("00") -> digits.removePrefix("00")
        digits.startsWith("880") -> digits
        digits.startsWith("0") -> "880${digits.removePrefix("0")}" // Bangladesh local format
        digits.matches(Regex("1[3-9][0-9]{8}")) -> "880$digits" // Bangladesh local format without 0
        else -> digits
    }
}

