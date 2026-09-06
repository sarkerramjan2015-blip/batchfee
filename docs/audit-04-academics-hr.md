# Audit 04 — Academics & HR (Exam / Attendance / Routine / Staff / Salary)

Scope: Exam module (single-exam and final-exam flows), Attendance module (student and staff,
stuck states, student mapping), Routine module (incl. the custom routine PDF flow), Staff module,
and Salary management (per-class / per-hour breakdown).

Verdict per finding: `BUG` (fixed), `NO ISSUE`, or `NOTE`.

---

## Summary

The modules are well structured: exams are lifecycle-guarded, final-exam marks have an
approval workflow, salaries are ledger-consistent with locked teaching sessions, and the
previously reported Staff Attendance compile break is resolved (the app builds cleanly). Five
bugs were confirmed and fixed:

| # | Severity | Verdict |
|---|----------|---------|
| 1 | High | BUG — attendance marking crashed the app on offline/transient cloud errors → FIXED |
| 2 | High | BUG — staff attendance undo was local-only; the mark reappeared after sync (stuck state) → FIXED |
| 3 | High | BUG — teacher-tab Present never synced the staff attendance row to Firestore → FIXED |
| 4 | Medium | BUG — custom routine PDF watermark was covered by opaque table fills → FIXED |
| 5 | Medium | BUG — legacy exam marks unbounded (150/100 persisted) and empty saves marked the exam completed → FIXED |

---

## 1. Attendance marking crashed on transient cloud errors — FIXED

- **Location:** [`AttendanceViewModel.markAttendance`](app/src/main/java/com/example/ui/attendance/AttendanceViewModel.kt:334),
  `markAll`, `bulkMark`, `undoAttendance`, and
  [`StaffAttendanceViewModel`](app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt:167)
  (`setEntryTime`, `setExitTime`, `mark`).
- **Bug:** each ran `AttendanceSyncHelper.upsertAttendance` / `upsertStaffAttendance` /
  `deleteAttendance` inside an unguarded `viewModelScope.launch`. Those helpers
  ([`OperationalDataSyncHelpers.kt`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:447))
  rethrow every non-PERMISSION_DENIED error, and the app has no global
  `CoroutineExceptionHandler` — so a single offline tap on Present/Absent crashed the app,
  and marks were silently lost.
- **Fix:** all six paths now wrap the cloud-first write + Room insert in try/catch, preserving
  the existing cloud-first semantics (a failed write never leaves a local-only mark that other
  devices would overwrite).

## 2. Staff attendance undo was local-only (mark reappears) — FIXED

- **Location:** [`StaffAttendanceViewModel.undo`](app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt:245).
- **Bug:** `undo` deleted only the local Room row. The Firestore `staff_attendance` document
  survived, and the next `syncAllFromFirestore` re-inserted the row — the undone mark came back
  (the "stuck state").
- **Fix:** added [`AttendanceSyncHelper.deleteStaffAttendance`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:487)
  and `undo` now deletes the cloud document first, then the local row; if the cloud delete fails,
  both copies stay in place so they never diverge.

## 3. Teacher-tab Present never reached Firestore — FIXED

- **Location:** teacher dialog save in
  [`StaffAttendanceScreen.kt`](app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt:918).
- **Bug:** marking a teacher Present wrote `insertOrUpdateAttendance` to Room only. The admin
  tab, summaries and every other device read staff attendance from synced Firestore data, so the
  teacher's Present mark existed on exactly one device and disappeared after the next refresh.
  (The teaching sessions themselves did sync correctly via `createSessionIfAvailable`.)
- **Fix:** the dialog now calls `AttendanceSyncHelper.upsertStaffAttendance(attendanceRecord)`
  before the local insert, inside its existing try/catch with snackbar error reporting.

## 4. Custom routine PDF watermark invisible — FIXED

- **Location:** [`CustomRoutinePdfGenerator.drawCustomRoutinePage`](app/src/main/java/com/example/ui/batches/CustomRoutinePdfGenerator.kt:131).
- **Bug:** `drawTableWatermark` was invoked before the opaque navy table header and the
  alternating white/alt row rectangles, which painted over the faded logo entirely — the same
  z-order defect found in the Step 3 receipt PDF.
- **Fix:** the watermark is now drawn after the day-row loop and before the footer, so the
  faded institute logo is visible behind the timetable. The generator itself already runs on
  `Dispatchers.IO` from its caller
  ([`CustomRoutineScreens.kt`](app/src/main/java/com/example/ui/batches/CustomRoutineScreens.kt:1016)),
  and the header logo path was already correct.

## 5. Legacy exam marks unbounded; empty save completed the exam — FIXED

- **Location:** [`ExamViewModel.saveResults`](app/src/main/java/com/example/ui/exams/ExamViewModel.kt:266).
- **Bug:** entered marks were persisted with no upper bound (a typo of `150` in a 100-mark exam
  was saved), and saving while every entered value parsed to zero produced an empty result set
  yet still flipped the exam to `completed`. The old code also used `_selectedExam.value!!`,
  which could crash if the selection had not loaded.
- **Fix:** every mark is clamped with `coerceIn(0.0, totalMarks)`, an empty mark list is
  rejected with a message, and the captured `exam` instance replaces the `!!` access.
  Zero-mark entry in the legacy single-exam module remains unsupported (see Notes).

---

## Verified — NO ISSUE

### Exam module
- Final-exam publish requires zero unapproved subject marks
  ([`FinalExamViewModel.publishExam`](app/src/main/java/com/example/ui/exams/FinalExamViewModel.kt:412));
  a student's result is computed only when every subject has approved marks
  ([`recomputeResults`](app/src/main/java/com/example/ui/exams/FinalExamViewModel.kt:435)).
- Staff cannot edit approved marks; owner edits of approved marks write an audit log entry.
- Exam creation with a fee uses the trusted
  [`ExamFeeRepository.createExamWithFees`](app/src/main/java/com/example/data/repository/ExamFeeRepository.kt)
  and locks batch/name/date once fee rows exist
  ([`ExamViewModel.updateExam`](app/src/main/java/com/example/ui/exams/ExamViewModel.kt:213)).

### Attendance module
- Student lists for marking come from
  [`BatchStudentDao.getStudentsForBatch`](app/src/main/java/com/example/data/dao/BatchStudentDao.kt:11),
  which excludes archived/inactive students, so historic rows can never be re-mapped onto closed
  students.
- Summary denominators include unmarked students (`chartTotal`), preventing a class of 3 with 2
  present from showing 100% present.
- Staff batch scoping for non-admins derives from `assignedBatchIds`
  ([`AttendanceViewModel.getStaffAssignedBatchIds`](app/src/main/java/com/example/ui/attendance/AttendanceViewModel.kt:170)).

### Routine module
- Custom routine save validates name/class, coerces `periodCount` to 1–14, upserts entries and
  removes deleted ones atomically in Room; entry editing detects same-day time overlaps.
- The legacy batch routine PDF ([`generateRoutinePdf`](app/src/main/java/com/example/ui/batches/RoutinePdfGenerator.kt:36))
  runs on `Dispatchers.IO` from [`RoutineScreen.kt`](app/src/main/java/com/example/ui/batches/RoutineScreen.kt:158).
- Custom routines are local-only by design (no Firestore collection for them).

### Staff module & Salary management
- Staff add/update are admin-only; provisioning goes through the trusted
  [`EntitledCreationRepository.provisionStaff`](app/src/main/java/com/batchfee/edu/data/repository/EntitledCreationRepository.kt:71)
  and the local login cache is aligned afterwards. The edit form only offers `active`/`inactive`,
  so the audited archive flow ([`SafeDeletionRepository.archiveStaff`](app/src/main/java/com/example/data/repository/SafeDeletionRepository.kt:28))
  cannot be bypassed.
- Per-class/per-hour math is correct:
  `per_class → rate × classes`, `per_hour → rate × minutes ÷ 60`
  ([`StaffAttendanceScreen.kt`](app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt:935)).
- Salary generation locks teaching sessions into the salary in one Firestore transaction,
  uses a deterministic per staff/month ID, blocks duplicate taps, recalculates basic pay from
  unpaid sessions, and creates the matching institute expense
  ([`SalaryViewModel.generateSalary`](app/src/main/java/com/example/ui/staff/SalaryViewModel.kt:177)).
  Cancelling unlocks the sessions and archives the expense; payments are due-bounded and
  cloud-first. Legacy paid salaries without expenses are repaired once with deterministic IDs.

---

## Notes (no functional break)

- The legacy single-exam module still cannot save an explicit zero mark (the UI filters `> 0`
  to distinguish entered from empty fields). Supporting genuine zero/absence there would require
  entered-vs-empty UI state — a feature change; the final-exam module already supports it.
- `updateExam` allows editing `totalMarks`/`passingMarks` after results exist (only batch/name/
  date are locked by fees). Changing totals after results were saved can desync stored grades;
  recommended follow-up is to lock totals once results exist.
- `publishExam` does not require every batch student to have a result; incomplete students are
  simply excluded from the computed result list — acceptable for the current product but worth a
  product decision later.
- The teacher simple-count path records sessions with `batchId = ""` and a default 60-minute
  duration for per-hour teachers; the snackbar reports the entered class count even when the
  optional detailed rows are fewer — cosmetic.

---

## Verification (executed 2026-09-05)

- `.\gradlew.bat :app:compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL**
  (deprecation warnings only) — confirms the previously reported StaffAttendance compile break
  is fully resolved and all Step 4 fixes compile.
