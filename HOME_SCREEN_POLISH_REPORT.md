# Home Screen Polish Audit Report

**Date:** 2026-06-04
**Scope:** Dashboard/Home screen only
**File audited:** `app/src/main/java/com/example/ui/dashboard/DashboardScreen.kt`

---

## 1. Current Home Screen Structure

The dashboard is built as a single ~2960-line file containing:

| Section | Lines | Composable |
|---------|-------|------------|
| Header (greeting, institute, plan badge) | ~1810 | `DashboardHeader` |
| Trial reminder card | ~1952 | `TrialReminderCard` |
| Institute Snapshot (Students/Batches/Staff rows) | ~580 | inline + `OverviewRow` |
| Staff Logs card | ~610 | inline |
| Attendance summary (student + staff bars) | ~630 | `AttendanceSegmentedBar`, `StaffSegmentedBar` |
| Attendance mini-cards | ~670 | `AttendanceMiniCard` |
| Financial collection (Today/Monthly/Lifetime) | ~695 | `AnimatedCounter` |
| Due Fees card (Active/Close) | ~745 | `DueSummaryBlock` |
| Tools & reminders (Exams, Birthdays, Home works, Enquiries) | ~792 | `HomeEngagementSection` |
| Quick Actions grid (Add Student, Create Batch, etc.) | ~806 | `ShortcutItem` |
| Batch detail dialog | ~850 | inline dialog |
| Enquiry form dialog | ~880 | `AddEnquiryDialog` |
| FAB menu (Add New...) | ~900 | `AddNewMenuPanel` |
| Profile popup (institute info, subscription, edit) | ~950 | inline popup |
| Edit profile dialog | ~1290 | inline dialog |
| More screen | ~2927 | `MoreScreen` |

Plus: `BackupRestoreScreen`, `SettingsScreen` in separate files.

---

## 2. UI/UX Problems Found

**A. CRITICAL — Suspicious comment block (lines ~773–782):**
```kotlin
    /*
    Text("View →", ...)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // ── Shortcut Grid ──────────────────────────────
            */
        }
    }
```
A `/* ... */` block incorrectly swallows closing braces of the Due Fees card and a `Spacer`. This is the same issue flagged in `PROJECT_AUDIT_REPORT.md`. It appears the `Spacer` and section comment between the Due Fees card and the Tools section are inside a comment — they may or may not be active depending on how Kotlin parses the braces.

**B. AccentAmber is defined as AccentCyan:**
```kotlin
private val AccentAmber = AccentCyan
```
This means the Due Fees icon uses cyan instead of amber. Naming is misleading.

**C. "Home works" tile shows count=0 with no functionality:**
```kotlin
HomeFeatureTile(title = "Home works", count = 0, ...)
```
Shows "0" permanently. Taps show a "Coming soon" snackbar. This looks incomplete.

**D. Financial collection cards lack visual tappability:**
The Today/Monthly/Lifetime cards are clickable (navigate to Reports) but have no chevron, underline, or "View" indicator. Users may not know they're interactive.

**E. "View" text on Due Fees card is static:**
The entire card is clickable, but the "View" label is just decorative text — no visual affordance like a chevron icon.

**F. Profile popup is complex:**
Contains edit dialog, photo picker, subscription card, WhatsApp contact button, and "Switch" plan button all in one popup. Scroll-heavy on small screens.

---

## 3. Safe Polish Suggestions

| # | Suggestion | Risk | Effort |
|---|-----------|------|--------|
| 1 | Fix the corrupt comment block (lines 773–782) — remove the `/*` and `*/` to restore intended layout | Medium | Small |
| 2 | Add chevron icons to clickable cards (Financial, Due Fees) for visual affordance | Safe | Tiny |
| 3 | Hide "Home works" tile until implemented, or place a "Coming Soon" badge | Safe | Tiny |
| 4 | Add loading shimmer/skeleton for attendance section instead of spinner | Safe | Small |
| 5 | Reduce profile popup height — use tabs or collapsible sections | Medium | Medium |
| 6 | Add pull-to-refresh to dashboard | Medium | Medium |
| 7 | Add empty-state illustrations for first-time users (0 students, 0 batches, 0 fees) | Safe | Medium |

---

## 4. Logic/Navigation Risks

- **No confirmation on "logout"** — tapping logout immediately clears session.
- **Trial expiry handling** — when trial ends, does the app block all features? Not visible in dashboard code alone.
- **Staff permission checks are on navigation but not on direct data access** — the ViewModel doesn't re-check permissions (it relies on UI-level gating). Minimal risk for now.
- **`AccentAmber = AccentCyan`** — not a logic bug but confusing for future maintainers.

---

## 5. Top 5 Small Next Tasks

1. **Fix corrupt comment block** — remove stray `/*` and `*/` around lines 773–782 so the Due Fees card section ends cleanly and the Tools section gets its proper spacer.

2. **Add chevron icons** — add a small `Icons.Filled.ChevronRight` to the Due Fees card header and financial collection cards for tappability affordance.

3. **Fix AccentAmber** — change to an actual amber color like `Color(0xFFF59E0B)` for visual correctness.

4. **Hide or badge "Home works"** — either remove the tile or wrap it in a styled "Coming Soon" badge until implemented.

5. **Add loading skeleton** — replace the `CircularProgressIndicator` in the attendance section with a simple shimmer placeholder for a more polished look.

---

**No source code was modified during this audit.**
