# BatchFee — Staff Attendance Work "Stuck State" Report

Date: 2026-09-04
Mode: Architect (investigation only — no code changed)

## 1. What happened

The last implementation session was building the **Staff Attendance** feature in
[`StaffAttendanceScreen.kt`](app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt:1).
It ran out of credits in the middle of the final edit — the code was left in a
**half-finished, never-built** state.

### Sequence of the last session
1. **Entry & Exit Time Tracking** — COMPLETED, built, installed on emulator, verified. ✅
2. **Teacher Attendance (v1, two tabs)** — completed and built OK (warning only). ✅
3. User feedback: the teacher flow was wrong. Monthly teachers should mark **P/A/L/H exactly
   like administration staff**; per-class/per-hour teachers should, **when marked Present**,
   enter the **number of classes** taken (with optional batch + subject class details), and
   the pay is auto-calculated class-wise.
4. A large restructure edit (`+401 / −255` lines) was applied to `StaffAttendanceScreen.kt`
   plus import additions.
5. `gradlew assembleDebug` was run once and reported an **unresolved reference error near
   line 712** of `StaffAttendanceScreen.kt` (message truncated: "Unr…").
6. ⛔ **Usage limit hit** — the error was never fixed and the file was never built again.

## 2. What is on disk right now (verified)

### Entry & Exit Time Tracking — fully present (all previously built + verified)
| File | State |
|------|-------|
| [`StaffAttendanceEntity.kt`](app/src/main/java/com/example/data/models/StaffAttendanceEntity.kt:17) | `entryTimeMs: Long?`, `exitTimeMs: Long?` added (default null) |
| [`InstituteEntity.kt`](app/src/main/java/com/example/data/models/InstituteEntity.kt:24) | `trackStaffEntryExit: Boolean = false` (institute-level, default OFF) |
| [`AppDatabase.kt`](app/src/main/java/com/example/data/database/AppDatabase.kt:71) | `version = 40` + `MIGRATION_39_40` adds the 3 columns |
| [`OperationalDataSyncHelpers.kt`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:496) | staff attendance upsert/read now carry `entryTimeMs` / `exitTimeMs` |
| [`InstituteSyncHelper.kt`](app/src/main/java/com/example/data/firestore/InstituteSyncHelper.kt:64) | cloud sync preserves the local `trackStaffEntryExit` toggle |
| DashboardScreen edit | institute edit keeps the toggle field |

### Teacher Attendance tab — INCOMPLETE (this is where it stopped)
- [`StaffAttendanceScreen.kt`](app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt:1) is
  now **1139 lines** and contains:
  - Top-level switch: **Administration** tab + **Teacher Attendance** tab (lines 314–377).
  - `TeacherAttendanceContent()` (line 611): date navigation, teacher list, and the
    **Present-with-class-count** dialog (class count + optional batch/subject/minute rows).
  - `TeacherAttendanceCard()` (line 1016): shows teacher pay type and today's recorded classes.
  - **This file has NOT compiled since the restructure** — a compile error was reported and
    never fixed.
- [`TeachingSessionDao.kt`](app/src/main/java/com/example/data/dao/TeachingSessionDao.kt:19)
  already gained `getSessionsForDate()` and has `getBySessionKey()`, `insertSession()`.
- No other file was mid-edit.

## 3. Dependency checks done (so the remaining error is localized)

All external symbols used by the new Teacher Attendance code **exist and resolve**:

- `StaffEntity` fields: `staffCategory`, `salaryType` (`monthly`/`per_class`/`per_hour`),
  `perClassRate`, `perHourRate` — [`StaffEntity.kt`](app/src/main/java/com/example/data/models/StaffEntity.kt:34)
- `TeachingSessionEntity` constructor matches usage — [`TeachingSessionEntity.kt`](app/src/main/java/com/batchfee/edu/data/models/TeachingSessionEntity.kt:21)
  (file physically under `com/example/...`, but declares `package com.batchfee.edu.data.models` — the
  project already completed the `com.example → com.batchfee.edu` package rename)
- `TeachingSessionSyncHelper.createSessionIfAvailable()` and `AttendanceSyncHelper` exist in
  [`OperationalDataSyncHelpers.kt`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:1184)
- `TeachingSessionDao`, `BatchDao`, `StaffAttendanceDao`, `InstituteDao`, `StaffDao` accessors all
  declared in [`AppDatabase.kt`](app/src/main/java/com/example/data/database/AppDatabase.kt:76)
- `BatchDao.getBatchesByInstituteOnce()` exists — [`BatchDao.kt`](app/src/main/java/com/example/data/dao/BatchDao.kt:12)

Conclusion: the remaining compile problem is **localised inside**
`StaffAttendanceScreen.kt` (likely a leftover/misspelt identifier from the merge of the two
teacher-tab versions). An actual build is needed to read the exact message(s).

## 4. Intended behaviour (final spec from the user)

- Staff Attendance has **two parts** under one screen.
- **Part 1 – Administration** (unchanged): fixed monthly staff mark **P/A/L/H**, optional
  institute-level **Entry/Exit** time tracking (already working).
- **Part 2 – Teacher Attendance**:
  - **Monthly teachers** → mark P/A/L/H exactly like administration staff (no class count).
  - **Per-class / per-hour teachers** → when marking **Present**, owner enters the
    **number of classes**; optionally records **which batch + subject** per class.
  - Pay auto-calculation: `per_class → rate × number of classes`,
    `per_hour → rate × minutes ÷ 60`.
  - Salary generation later picks these up via the existing teaching-session / salary flow
    (existing `SalaryScreen` already previews "N completed classes • BDT amount").

## 5. Proposed next steps

1. Switch to **code mode** and run `gradlew assembleDebug` to capture the exact
   compile error(s) in `StaffAttendanceScreen.kt`.
2. Fix the unresolved-reference error(s) in the Teacher Attendance section only.
3. Rebuild until clean (only pre-existing unrelated warnings).
4. Install on emulator + crash check.
5. Functionally verify Part 1 (Administration + Entry/Exit unchanged) and Part 2 behaviour.
6. Confirm the change list touches only the Staff Attendance feature files.
