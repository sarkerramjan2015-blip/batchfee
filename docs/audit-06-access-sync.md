# Audit 06 — Access & Sync (Staff / Student Login, App Sync, Permissions)

Scope: staff login from any device, student login, student-app ↔ main-app data sync, and
strict staff permission enforcement.

Verdict per finding: `BUG` (fixed), `NO ISSUE`, or `NOTE`.

---

## Summary

Authentication flows are strong (server-issued custom tokens, claim/UID verification, no device
binding). The permission model is consistently enforced in three layers (client route map,
trusted callables, Firestore rules). Three student-app sync bugs were found and fixed:

| # | Severity | Verdict |
|---|----------|---------|
| 1 | High | BUG — student results read non-existent fields; unpublished results leaked → FIXED |
| 2 | High | BUG — student fee totals included cancelled mistaken-enrollment fees → FIXED |
| 3 | Medium | BUG — student dashboard attendance percentage was never computed → FIXED |

---

## 1. Student results sync read the wrong fields and leaked unpublished results — FIXED

- **Location:** [`StudentDataRepository.fetchResults`](app/src/main/java/com/batchfee/edu/data/repository/StudentDataRepository.kt:74).
- **Bug:** the main app writes `marksObtained` / `position` / `published` on the result document
  and keeps the exam name/subject/date/totals on the exam document
  ([`ResultSyncHelper.upsertResult`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:915)).
  The student repository read `obtainedMarks` / `examName` / `rank` / `totalStudents` — fields
  that never exist — so every dashboard result showed 0 marks, "Exam", and no rank. It also did
  not filter `published`, so unpublished results reached the student.
- **Fix:** the query now requires `published == true`, maps `marksObtained` and `position`
  correctly, and enriches each result from the exam document (`examName`, `subject`,
  `examDateMs`, `totalMarks`). Student reads of `results`/`exams` are permitted by
  [`firestore.rules`](firestore.rules:698) for the record owner.

## 2. Student fee totals included cancelled fees — FIXED

- **Location:** [`StudentDataRepository.fetchFees`](app/src/main/java/com/batchfee/edu/data/repository/StudentDataRepository.kt:39).
- **Bug:** the main app marks mistaken-enrollment fees `cancelled` and zeroes `dueAmount` but
  keeps `totalAmount`; the student repository returned all rows and read the non-existent
  `monthYear`/`description` fields. Cancelled charges inflated the dashboard totals and every
  fee showed the generic label "Fee".
- **Fix:** the query now filters `cancelledAtMs == null` and maps the canonical `feePeriod`
  (falling back to `feeType`) into the description/month fields — matching how the rest of the
  student app already reads fees ([`StudentFeeScreen.kt`](app/src/main/java/com/batchfee/edu/ui/studentapp/StudentFeeScreen.kt:253)).

## 3. Student dashboard attendance percentage was never computed — FIXED

- **Location:** [`StudentDashboardViewModel.load`](app/src/main/java/com/batchfee/edu/ui/studentapp/StudentDashboardViewModel.kt:50).
- **Bug:** the attendance list was fetched and discarded, so `attendancePercent` stayed `0.0`
  forever.
- **Fix:** the present ratio is computed from the fetched records and published to the state.

---

## Verified — NO ISSUE

### Staff login from any device
- [`StaffAuthRepository.signIn`](app/src/main/java/com/batchfee/edu/data/repository/StaffAuthRepository.kt:12)
  calls the trusted `loginStaff` callable and signs the returned custom token in with Firebase
  Auth. There is no device binding anywhere: the backend
  [`loginStaffHandler`](functions/src/index.js:1395) validates password, institute subscription,
  staff record, login/account documents and app-user state, then mints a custom token with
  `staffManaged` claims — identical on every device. Error mapping is explicit (invalid
  credentials, lockout, unavailable).

### Student login
- [`StudentAuthRepository.login`](app/src/main/java/com/batchfee/edu/data/repository/StudentAuthRepository.kt:27)
  verifies the returned UID, the session expiry and the full claim set
  (`student`, `studentId`, `instituteId`, `studentSessionExpiresAt`) against the signed-in user,
  with a forced token refresh fallback, and signs out on any mismatch. The backend requires an
  active, unarchived student with an enabled login (audited in Step 2).

### Student app ↔ main app sync
- Both apps read the same institute-scoped Firestore collections (`students`, `fees`,
  `attendance`, `results`, `exams`, `batch_students`); the student app has no separate replica,
  so after the Step 6 fixes the student view is a live projection of main-app data.
- Student-side screens with their own readers ([`StudentFeeScreen.kt`](app/src/main/java/com/batchfee/edu/ui/studentapp/StudentFeeScreen.kt:253),
  [`StudentResultScreen.kt`](app/src/main/java/com/batchfee/edu/ui/studentapp/StudentResultScreen.kt:96))
  already use the canonical field names and already filter cancelled fees — consistent with the
  fixed repository.

### Staff permission enforcement (three layers, same keys)
- **Client routes:** [`AccessControl.canAccessRoute`](app/src/main/java/com/example/domain/StaffAccess.kt:157)
  is enforced centrally in [`MainActivity`](app/src/main/java/com/example/MainActivity.kt:324);
  admin-only routes are hard-listed and every other route requires at least one permission from
  [`StaffPermissions`](app/src/main/java/com/example/domain/StaffAccess.kt:9).
- **Trusted callables:** [`financialLedger.js`](functions/src/financialLedger.js:222) allows
  staff mutations only with `collect_fee` and reserves corrections to the owner; student/staff/
  batch mutations use `manage_student` / `manage_staff` / `manage_batch` (audited in Step 2).
- **Firestore rules:** each operational collection maps to the same keys — `attendance` →
  `take_attendance`, `staff_attendance` → `manage_staff_attendance`/`manage_staff`,
  `teaching_sessions` → `manage_staff`, `salaries` → `manage_salary`, `expenses` →
  `manage_expenses`, `exams`/`results` → `manage_exams`, `reminder_templates` →
  `manage_reminders` ([`firestore.rules`](firestore.rules:661)).
- Session permissions are parsed from the staff CSV
  ([`SessionManager.parsePermissions`](app/src/main/java/com/example/domain/SessionManager.kt:64))
  and refreshed live after a staff profile edit (`StaffViewModel.updateStaff` calls
  `SessionManager.updateStaffPermissions` when the edited staff is the current user).

---

## Notes (no functional break)

- A staff member with only `manage_salary` can open `TeacherClassSessionsRoute`, but the
  Firestore rules restrict teaching-session writes to `manage_staff`; the screen is read-only
  for them in practice, which is stricter than the route map.
- A staff member with `take_attendance` but without `send_due_message` can tap the message
  buttons on the attendance screen; the `absent_messages` write is rule-denied and swallowed by
  `rethrowUnlessAccessDenied`, leaving a local-only message row. Harmless, but the buttons could
  be hidden per permission in a polish pass.
- The `student_activity` feed written during student login remains visible to the owner via
  realtime rules ([`firestore.rules`](firestore.rules:747)).

## Verification (executed 2026-09-05)

- `.\gradlew.bat :app:compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL**
  (deprecation warnings only) with all Step 6 fixes compiled.
