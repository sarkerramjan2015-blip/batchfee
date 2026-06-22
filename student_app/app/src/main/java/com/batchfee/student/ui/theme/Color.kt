package com.batchfee.student.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════
//  BRAND — Deep Indigo / Navy
// ═══════════════════════════════════════════════
val PrimaryDefault = Color(0xFF1E1B4B)      // Deep Navy — primary navigation, accents
val PrimaryLight = Color(0xFF3730A3)         // Slightly lighter for hover/active
val PrimaryContainer = Color(0xFFEEF2FF)     // Ultra-light indigo bg
val OnPrimaryContainer = Color(0xFF312E81)

// ═══════════════════════════════════════════════
//  SURFACE — Ultra-clean off-white (#F8FAFC)
// ═══════════════════════════════════════════════
val AppBackground = Color(0xFFF8FAFC)
val SurfaceCard = Color(0xFFFFFFFF)           // Pure white cards
val SurfaceCardAlt = Color(0xFFF1F5F9)        // Slight slate for subtle distinction
val BorderSubtle = Color(0xFFE2E8F0)          // Very light borders

// ═══════════════════════════════════════════════
//  TEXT — Maximum readability
// ═══════════════════════════════════════════════
val TextHeading = Color(0xFF0F172A)           // Near-black for headings
val TextBody = Color(0xFF334155)              // Slate-700 for body
val TextSecondary = Color(0xFF64748B)         // Slate-500 for labels
val TextPlaceholder = Color(0xFF94A3B8)       // Slate-400 for placeholders
val TextOnPrimary = Color(0xFFFFFFFF)

// ═══════════════════════════════════════════════
//  FINANCIAL STATUS CARDS — as specified
// ═══════════════════════════════════════════════
// DUE — #FEE2E2 bg, #DC2626 text/icon (Crimson Red)
val DueBg = Color(0xFFFEE2E2)
val DueText = Color(0xFFDC2626)
val DueIcon = Color(0xFFDC2626)

// PAID — #DCFCE7 bg, #16A34A text/icon (Deep Emerald)
val PaidBg = Color(0xFFDCFCE7)
val PaidText = Color(0xFF16A34A)
val PaidIcon = Color(0xFF16A34A)

// ATTENDANCE — #E0F2FE bg, #2563EB text/icon (Royal Blue)
val AttBg = Color(0xFFE0F2FE)
val AttText = Color(0xFF2563EB)
val AttIcon = Color(0xFF2563EB)

// ═══════════════════════════════════════════════
//  FEATURE CARD COLORS — muted pastel backgrounds
// ═══════════════════════════════════════════════
val CardFees = Color(0xFFEEF2FF)             // Indigo tint
val CardAttendance = Color(0xFFEFF6FF)       // Blue tint
val CardExams = Color(0xFFFFFBEB)            // Warm amber tint
val CardResults = Color(0xFFF5F3FF)          // Purple tint
val CardHomework = Color(0xFFF0FDF4)         // Green tint
val CardNotices = Color(0xFFFFF1F2)          // Rose tint
val CardProfile = Color(0xFFF0FDFA)          // Teal tint
val CardRoutine = Color(0xFFF8FAFC)          // Slate tint

val CardIconFees = Color(0xFF4F46E5)
val CardIconAttendance = Color(0xFF2563EB)
val CardIconExams = Color(0xFFD97706)
val CardIconResults = Color(0xFF7C3AED)
val CardIconHomework = Color(0xFF059669)
val CardIconNotices = Color(0xFFDC2626)
val CardIconProfile = Color(0xFF0D9488)
val CardIconRoutine = Color(0xFF4F46E5)

// ═══════════════════════════════════════════════
//  STATUS / SEMANTIC
// ═══════════════════════════════════════════════
val StatusPresent = Color(0xFF059669)         // Green
val StatusAbsent = Color(0xFFDC2626)          // Red
val StatusLate = Color(0xFFD97706)            // Amber
val StatusUpcoming = Color(0xFF2563EB)        // Blue

// ── Legacy Aliases (for backward compatibility with existing screens) ──
val StatusGreen = PaidText
val StatusRed = DueText
val StatusOrange = Color(0xFFD97706)
val StatusBlue = AttText
val PrimaryBlue = PrimaryDefault
val SecondaryTeal = CardIconProfile
val TextSecondaryLight = TextSecondary
val TextPrimaryLight = TextHeading
val CardGreenBg = PaidBg
val CardRedBg = DueBg
val CardOrangeBg = Color(0xFFFFFBEB)
val CardBlueBg = AttBg
