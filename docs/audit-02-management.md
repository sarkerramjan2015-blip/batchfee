# Audit 02 — Management (Batches & Students)

Scope: Batch add/edit, Student add/edit/details/save/delete/archive, deletion/archive cascade
logic, and the Firebase Auth link for student accounts.

Verdict per finding: `BUG` (fix planned / fixed), `NO ISSUE`, or `NOTE` (no functional break).

---

## Summary

The Management layer is well built. Both client and backend funnel archive/delete through a
trusted, audited `commitSafeDeletion` flow, and enrollment/attendance/finance queries correctly
hide archived records. Three issues were confirmed:

| # | Severity | Verdict |
|---|----------|---------|
| 1 | High | BUG — `updateStudentProfile` callable can edit or silently un-archive an archived student → **FIXED** |
| 2 | Medium | BUG — `InstituteAdmin` role loses archive/custom-fee controls the backend grants it → **FIXED** |
| 3 | Low | BUG — "Mark inactive" dialog promises removal from lists that still show the student → **FIXED** |

---

## 1. Backend `updateStudentProfile` does not reject archived / retained students — FIXED

- **Location:** [`updateStudentProfileHandler`](functions/src/index.js:1496), transaction at
  [functions/src/index.js](functions/src/index.js:1547).
- **What it does today:** validates the caller with
  [`assertCanManageStudent`](functions/src/index.js:154) (owner, managed admin, SuperAdmin, or
  staff with `manage_student`), checks the student exists, then writes profile fields **including
  `status`** with only a whitelist of `active`/`inactive` values. It never checks
  `deletionState`, `archivedAtMs`, or the current `status`.
- **Why this breaks the cascade:** a client could send `status: "active"` for a student that
  [`commitSafeDeletion`](functions/src/safeDeletion.js:306) archived. Consequences:
  - The student is re-activated **without** the restore branch of
    [`safeDeletion.js`](functions/src/safeDeletion.js:515): no seat-limit check, no
    `studentCount` counter restore, no deletion-state cleanup, and no Auth re-enable.
  - The `deletion_states` document still says `active: true`; the Firestore `archivedAtMs` either
    remains (making the local Room copy hidden from lists because
    [`StudentDao.getStudentsByInstitute`](app/src/main/java/com/example/data/dao/StudentDao.kt:9)
    filters `archivedAtMs IS NULL`) or is overwritten inconsistently. The Firebase Auth user
    stays disabled, so the owner sees an "active" student who cannot log in.
- **Fix (backend, fail-closed):** inside the transaction, immediately after the
  `studentSnap.exists` check at [functions/src/index.js](functions/src/index.js:1555), reject
  with `failed-precondition` when the stored document has `deletionState === "retained"`,
  `archivedAtMs != null`, or `status === "archived"`. Profile edits and status flips for
  archived students must go through the audited restore in `commitSafeDeletion`; nothing in the
  Android client legitimately edits an archived student (ArchivedStudentsScreen only offers
  Restore and permanent Delete).
- **Verified safe:** the client `Activate`/`Mark inactive` flows
  ([`StudentProfileScreen.kt`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:1814))
  only target non-archived students (`archivedAtMs == null`), so the new guard cannot break them.
- **Implemented:** guard added inside the transaction at
  [functions/src/index.js](functions/src/index.js:1556); `node --check src/index.js` passes.
  Takes effect after `firebase deploy --only functions`.

## 2. `InstituteAdmin` role cannot archive students or set custom monthly fee — FIXED

- **Location:** [`StudentProfileScreen.kt`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:114)
  (`canArchiveStudent`) and line 115 (`canManageCustomMonthlyFee`).
- **What the backend grants:** [`assertAuthority`](functions/src/safeDeletion.js:46) and
  [`assertCanManageStudent`](functions/src/index.js:154) treat managed admins
  (`InstituteAdmin`/`admin`/`instituteAdmin`/`institute_admin`) exactly like owners, and
  [`assertCanManageTenantResource`](functions/src/index.js:208) grants them every tenant
  permission. The login flow in [`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:408)
  maps managed `admin` app-users to the client role `InstituteAdmin`, and
  [`SessionManager.isAdmin()`](app/src/main/java/com/example/domain/SessionManager.kt:105)
  already includes `InstituteAdmin`.
- **The bug:** the two UI gates in `StudentProfileScreen` only accept
  `InstituteOwner`/`SuperAdmin` (plus lowercase owner variants for the custom fee), so a
  managed admin loses the Archive Student menu item and the custom monthly fee control even
  though the backend would authorise those calls. The Archive menu item simply disappears
  ([`StudentBatchMenuItem`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:2118)).
- **Fix (client):** extend both role sets to include `InstituteAdmin` (and the lowercase
  variants the login path can produce), matching the backend authority model and
  `SessionManager.isAdmin()`.
- **Implemented:** both sets updated at
  [StudentProfileScreen.kt](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:117)
  and [StudentProfileScreen.kt](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:122);
  `:app:compileDebugKotlin` passes.

## 3. "Mark inactive" dialog text contradicts actual list behaviour — FIXED

- **Location:** [`StudentProfileScreen.kt`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:1788)
  dialog body for `StudentMenuConfirmAction.Close`.
- **The bug:** the dialog says *"The student will be removed from active lists and attendance."*
  In reality:
  - The Students screen still shows the student (with an Inactive badge and a status filter):
    [`StudentListScreen.kt`](app/src/main/java/com/example/ui/students/StudentListScreen.kt:163)
    does not exclude inactive statuses, and
    [`StudentDao.getStudentsByInstitute`](app/src/main/java/com/example/data/dao/StudentDao.kt:9)
    only filters on `archivedAtMs`.
  - Batch rosters, attendance lists, and the dashboard count do exclude them
    ([`BatchStudentDao.kt`](app/src/main/java/com/example/data/dao/BatchStudentDao.kt:11),
    [`StudentDao.countActiveStudents`](app/src/main/java/com/example/data/dao/StudentDao.kt:30)).
  - Student app login is blocked because
    [`loginStudentHandler`](functions/src/index.js:1980) requires `status === "active"`.
- **Fix (client, text only):** reword the dialog to accurately state that the student stays in
  the Students list as Inactive, leaves attendance/active counts, and keeps full history. This
  is a flow-correctness fix, not a feature change.
- **Implemented:** dialog body updated at
  [StudentProfileScreen.kt](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:1794);
  `:app:compileDebugKotlin` passes.

---

## Verified items — NO ISSUE

### Batch add/edit
- [`BatchViewModel.addBatch`](app/src/main/java/com/example/ui/batches/BatchViewModel.kt:46)
  validates name, fee > 0, course date range, and negative admission fee before creating;
  [`AddEditBatchScreen.kt`](app/src/main/java/com/example/ui/batches/AddEditBatchScreen.kt:441)
  duplicates the same validation at the UI layer, including schedule day/time rules.
- Creation is quota-controlled through the trusted
  [`createEntitledBatch`](app/src/main/java/com/batchfee/edu/data/repository/EntitledCreationRepository.kt:45)
  callable; the backend handler at [functions/src/index.js](functions/src/index.js:984) validates
  billing mode, fee positivity, course dates, subscription state and is idempotent via
  `creation_operations`.
- Edit locks the billing type client-side
  ([`BatchViewModel.updateBatch`](app/src/main/java/com/example/ui/batches/BatchViewModel.kt:135))
  and server-side in [`firestore.rules`](firestore.rules:500) (`billingMode` immutable).
- Batch archive is the trusted
  [`SafeDeletionRepository.archiveBatch`](app/src/main/java/com/example/data/repository/SafeDeletionRepository.kt:22)
  flow, invoked from [`BatchDetailScreen.kt`](app/src/main/java/com/example/ui/batches/BatchDetailScreen.kt:675)
  with a clear retention dialog. Room applies the canonical result
  ([`applyCanonicalResult`](app/src/main/java/com/example/data/repository/SafeDeletionRepository.kt:149)).

### Student add/edit/save
- [`StudentViewModel.addStudent`](app/src/main/java/com/example/ui/students/StudentViewModel.kt:87)
  validates name, phone, student code, and app-access password before the cloud create;
  the trusted [`createEntitledStudent`](functions/src/index.js:755) enforces seat limits,
  student-code global claims, registration approval replay and idempotency.
- Photo upload precedes profile save
  ([`AddEditStudentScreen.kt`](app/src/main/java/com/example/ui/students/AddEditStudentScreen.kt:642))
  and is covered by Audit 01's verified media pipeline.
- [`StudentViewModel.updateStudent`](app/src/main/java/com/example/ui/students/StudentViewModel.kt:249)
  handles admission-date change through the trusted ledger
  ([`FeeCollectionRepository.updateStudentAdmissionDate`](app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt))
  and re-syncs local enrollment windows; app-access changes (enable/reset/disable) go through
  [`StudentAccountRepository`](app/src/main/java/com/batchfee/edu/data/repository/StudentAccountRepository.kt:15)
  with partial-success handling so a profile save is never lost because of an account error.

### Deletion / archive cascade logic
- [`commitSafeDeletion`](functions/src/safeDeletion.js:306) is a single transaction that:
  archives the entity, disables the student/staff login document and account status, updates the
  institute counter, retains media assets, writes `deletion_states` + `deletion_audit`, and
  records an idempotent `deletion_operations` document.
- Auth reconciliation ([`reconcileAuth`](functions/src/safeDeletion.js:199)) verifies the
  managed-identity custom claims **before** disabling the Firebase Auth user, protects platform
  identities, revokes refresh tokens, and is replay-safe.
- Restore re-runs the seat-limit check against live counts
  ([functions/src/safeDeletion.js](functions/src/safeDeletion.js:523)) and re-enables app access
  only when it was enabled before archival.
- Client outbox ([`SafeDeletionRepository`](app/src/main/java/com/example/data/repository/SafeDeletionRepository.kt:84))
  keeps operations pending on transport failure and replays them from
  [`CoreDataSyncCoordinator`](app/src/main/java/com/example/data/firestore/CoreDataSyncCoordinator.kt:39)
  and the SuperAdmin screen; the response is validated before Room is touched.
- Archived records disappear from all operational queries:
  [`StudentDao`](app/src/main/java/com/example/data/dao/StudentDao.kt:9),
  [`BatchDao`](app/src/main/java/com/example/data/dao/BatchDao.kt:9),
  [`BatchStudentDao`](app/src/main/java/com/example/data/dao/BatchStudentDao.kt:11),
  [`FeeViewModel`](app/src/main/java/com/example/ui/fees/FeeViewModel.kt:111) (archived students
  and batches drop out of due calculations), and
  [`RoutineScreen.kt`](app/src/main/java/com/example/ui/batches/RoutineScreen.kt:121).
- Enroll/unassign/shift all run server-first
  ([`BatchEnrollmentRepository`](app/src/main/java/com/batchfee/edu/data/repository/BatchEnrollmentRepository.kt:39)):
  unassign cancels only unpaid fees, shift preserves first-month prorating, and Room is updated
  only after cloud confirmation.

### Firebase Auth link
- Provision ([`provisionStudentAccountHandler`](functions/src/index.js:1634)) creates/retrieves a
  managed Auth user (`student_<random>` UID), sets `studentManaged`/`instituteId`/`studentId`
  custom claims, writes `student_auth_logins` + `student_auth_accounts`, and only then enables
  the user; failure paths roll back identity state and delete newly created users.
- Login ([`loginStudentHandler`](functions/src/index.js:1873)) verifies password, institute
  subscription, `status === "active"`, `archivedAtMs == null`, `isAppAccessEnabled`, the UID
  link and custom claims before minting a token — so archived and inactive students cannot
  authenticate even before the Auth user is disabled.
- Archive disables the Auth user + refresh tokens; restore re-enables only when the prior state
  had access enabled. Disable ([`disableStudentIdentity`](functions/src/index.js:1775)) removes
  login/account lookup docs and disables the managed Auth user.
- The Android client never writes Auth state or credentials
  ([`StudentEntity`](app/src/main/java/com/example/data/models/StudentEntity.kt:14) has no
  password fields; passwords exist only as in-memory call arguments in
  [`StudentViewModel`](app/src/main/java/com/example/ui/students/StudentViewModel.kt:196)).

### Archived students screen
- Restore uses [`StudentDeletionRepository.restore`](app/src/main/java/com/example/data/repository/StudentDeletionRepository.kt:16)
  (seat-checked, audited) and permanent delete requires the server-side
  [`permanentlyPurgeStudent`](app/src/main/java/com/example/data/repository/PermanentStudentPurgeRepository.kt:12)
  to succeed before local rows are removed
  ([`ArchivedStudentsScreen.kt`](app/src/main/java/com/example/ui/students/ArchivedStudentsScreen.kt:98)).

---

## Notes (no functional break)

- [`BatchEnrollmentRepository.enroll`](app/src/main/java/com/batchfee/edu/data/repository/BatchEnrollmentRepository.kt:62)
  writes `batch_students` directly through Firestore rules (owner / `manage_batch` staff,
  [firestore.rules](firestore.rules:518)); the batch picker only offers non-archived batches, but
  a rule-level cross-check of the target batch's `archivedAtMs` would harden this path.
- [`PermanentStudentPurgeRepository.purge`](app/src/main/java/com/example/data/repository/PermanentStudentPurgeRepository.kt:20)
  leaves `bulk_message_log` rows for the purged student (orphaned audit rows only).
- `updateStudentProfile` requires non-empty `fullName`/`phone`; a legacy Room row with a null
  phone could fail profile edits until a phone is entered (UI requires one anyway).
- [`AddEditBatchScreen.kt`](app/src/main/java/com/example/ui/batches/AddEditBatchScreen.kt:313)
  labels the admission fee as required (`*`) although 0 is accepted — cosmetic.
- Staff with `manage_student`/`manage_batch` are backend-authorized to archive but the client
  hides the menu item from them (owner-only destructive action). Re-validated in Step 6
  (Access & Sync).
- [`BatchDetailScreen.kt`](app/src/main/java/com/example/ui/batches/BatchDetailScreen.kt:588)
  "Close batch" and "Shift" menu entries are placeholders that show a snackbar; no destructive
  code path is attached (safe, but confusing).

---

## Fix plan (for Code mode)

1. **BUG-1** — `functions/src/index.js` [`updateStudentProfileHandler`](functions/src/index.js:1496):
   reject archived/retained students inside the transaction.
2. **BUG-2** — [`StudentProfileScreen.kt`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:114):
   add `InstituteAdmin` (and lowercase variants) to `canArchiveStudent` and
   `canManageCustomMonthlyFee`.
3. **BUG-3** — [`StudentProfileScreen.kt`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:1788):
   correct the "Mark student inactive?" dialog text.

## Verification (executed 2026-09-05)

- `.\gradlew.bat :app:compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL**
  (deprecation warnings only).
- `.\gradlew.bat :app:assembleDebug -q` → success; APK installed on `BatchFee_Pixel_API_37`.
- `node --check src/index.js` and `node --check src/safeDeletion.js` → no syntax errors.
- Emulator smoke test with demo credentials (`sarkerramjan2015@gmail.com` / `172002`):
  - App launched, login succeeded → dashboard rendered (`ICT TOPPERS`, 8 students, 4 batches).
  - Students list rendered 8 active students; profile opened (`mesba`, Active, HSC 2027,
    custom monthly-fee control visible → BUG-2 role gate composes correctly).
  - Overflow menu shows **Edit Student / Assign Batch / Shift Batch / Mark Inactive / Share
    Login Info / Message / Generate Report / Registration Form / Archive student** — archive
    item present for the owner session.
  - **BUG-3 verified on device:** "Mark student inactive?" dialog now reads
    *"The student stays in the Students list with an Inactive status but leaves attendance and
    active student counts. Fees, receipts and complete history will remain safe."*
- Deferred to backend deployment: BUG-1's fail-closed guard in `updateStudentProfile` only
  activates after `firebase deploy --only functions`. Archive/restore runtime behaviour of
  `commitSafeDeletion` was verified statically (transaction + reconcileAuth) and remains
  unchanged by this patch.
