# BatchFee App Production QA and Security Audit Report

Audit date: 2026-06-06  
Scope: Read-only audit of Android/Kotlin source, Gradle configuration, Firebase rules/configuration, web registration form, documentation, and existing tests.  
Build/test execution: Not run. The request explicitly prohibited commands that create build artifacts, and no source changes were made.

## 1. Executive Summary

BatchFee is not production ready in its current state. The app has useful business coverage for institutes, students, batches, fees, attendance, staff, salaries, billing screens, and registration approval, but several production-blocking risks exist in security, subscription enforcement, demo data, authorization boundaries, backup handling, and ledger integrity.

Scores:

| Area | Score | Summary |
| --- | ---: | --- |
| Overall readiness | 54/100 | Functional demo/pilot quality, not production quality. |
| Production readiness | 42/100 | Hardcoded secrets, demo credentials, incomplete cloud enforcement, weak docs/tests. |
| Security | 31/100 | Public Firestore writes, hardcoded signing passwords, weak password hashing, unencrypted biometric session data. |
| Stability | 58/100 | Room/local flows are mostly coherent, but many errors are swallowed or UI-only guarded. |
| Scalability | 45/100 | Large Compose files, N+1 query patterns, unlimited Firestore cache, manual list rendering. |

Highest-risk findings:

1. Firestore rules allow unauthenticated public read/write to registration and institute collections (`firestore.rules:6-20`).
2. Release signing passwords are hardcoded in Gradle (`app/build.gradle.kts:27-33`).
3. Demo owner/admin/staff accounts with password `123456` are seeded from application startup paths (`BatchFeeApp.kt:42`, `AppDatabase.kt:153-160`, `AppDatabase.kt:220-254`, `AppDatabase.kt:716`).
4. Subscription and blocking enforcement is client-side, bypassable on Firestore failure/offline, and uses mismatched fields/collection casing (`MainActivity.kt:560-568`, `SuperAdminScreen.kt:135-176`, `InstituteEntity.kt:10-14`).
5. Payment-history edit logic bypasses the fee repository transaction path and directly rewrites fee/payment/receipt records (`UnifiedCollectScreen.kt:671-740`).

Production verdict: Do not publish to Play Store or onboard real institutes/students until the Critical and High items in this report are fixed and re-tested.

## 2. Architecture Analysis

Strengths:

- The app has clear domain coverage: auth, dashboard, students, batches, fees, attendance, staff, salaries, billing, registration, and super-admin.
- Most local entities carry `instituteId`, and many DAO queries filter by it. This is a good foundation for tenant separation.
- The fee collection repository uses `RoomDatabase.withTransaction` for primary create/collect flows (`FeeCollectionRepository.kt:37`, `FeeCollectionRepository.kt:80`, `FeeCollectionRepository.kt:143`, `FeeCollectionRepository.kt:170`).
- Student attendance has a unique Room index for one attendance row per institute/batch/student/date (`AttendanceEntity.kt:7-9`).
- A focused fee repository unit test file exists (`FeeCollectionRepositoryTest.kt`), including overpayment and receipt checks.

Weaknesses:

- Business logic is spread across ViewModels and large Compose screens instead of consistently living in repositories/use cases. Examples include direct payment edits in `UnifiedCollectScreen.kt`, enrollment inserts in `BatchDetailScreen.kt`, reset/export actions in `SettingsScreen.kt`, and subscription checks in `MainActivity.kt`.
- Several files are very large and combine UI, persistence, intent launching, file handling, and business rules. High-risk examples include `DashboardScreen.kt`, `StudentProfileScreen.kt`, `UnifiedCollectScreen.kt`, `BatchDetailScreen.kt`, and `FeeScreens.kt`.
- Authorization is mostly a navigation/UI concern. `AccessControl.canAccessRoute` is checked when navigating from the dashboard (`MainActivity.kt:161`), while many typed destination composables do not repeat authorization checks at the screen or data-operation layer.
- Room schema export is disabled (`AppDatabase.kt:49`), making migration verification weaker.
- Firebase behavior is inconsistent: the registration flow uses lowercase `registrations` and `institutes`, while subscription/super-admin code uses uppercase `Institutes`, `SuperAdmin`, and `Global_Notifications`.

Risky areas:

- Security boundary: Firebase rules, demo credentials, release signing, password storage, biometric session storage, backup rules.
- Money boundary: direct edits to ledger records, receipt number uniqueness, duplicate fee prevention, lack of audit logs for edits/cancellations.
- Tenant/admin boundary: UI-only route gating, client-side plan checks, Firestore rule mismatch.
- Data integrity: few unique constraints and foreign keys beyond attendance.

Recommendations:

- Move high-value business operations into repositories/use cases with transaction boundaries and tests.
- Enforce authorization in each sensitive ViewModel/repository method, not only in navigation.
- Treat Firebase rules and server-side subscription state as the production security boundary.
- Add schema export, migration tests, and unique indexes for business invariants.

## 3. Module-by-Module Audit

### Auth/Login

Critical issues:

- Demo seeding is reachable from app startup and auth paths. `BatchFeeApp.kt:42` calls `AppDatabase.ensureDemoDataSeeded(database)`, and `AuthScreen.kt:80`, `AuthScreen.kt:201`, and `AuthScreen.kt:245` also seed demo data in login-related flows.
- Known passwords are seeded for privileged and staff accounts: `admin@batchfee.app` and `owner@batchfee.app` use `123456` (`AppDatabase.kt:220-254`), and a staff user is also seeded with `123456` (`AppDatabase.kt:716`).
- Password hashing uses salted SHA-256 only. `ITERATIONS` is declared but unused (`PasswordHasher.kt:7`), the hash is `sha256("$salt$password")` (`PasswordHasher.kt:14`), and verification compares strings directly (`PasswordHasher.kt:24`).
- Biometric login stores user id, institute id, role, and email in normal SharedPreferences (`BiometricAuthManager.kt:28`, `BiometricAuthManager.kt:61-68`). It does not use EncryptedSharedPreferences or a cryptographic key protected by biometric authentication.

Other concerns:

- Session state is in memory only and uses a one-minute timeout (`SessionManager.kt:8`, `SessionManager.kt:29-55`). That may be too short for normal usage, but still not a server-side auth boundary.
- Legacy password migration in `AuthScreen.kt` compares raw stored hashes/plain values and upgrades on login (`AuthScreen.kt:263`). This is useful for migration but should be removed or tightly controlled before production.
- Multiple catch blocks call `printStackTrace()` (`AuthScreen.kt:121`, `AuthScreen.kt:247`, `AuthScreen.kt:302`, `AuthScreen.kt:365`), which is not production-grade error handling.

Needed before production:

- Remove all demo seeding from release builds.
- Replace SHA-256 with PBKDF2, bcrypt, scrypt, or Argon2id.
- Store biometric session material using AndroidX Security and/or keys requiring biometric auth.
- Add tests for login, staff inactive/archived login denial, role permissions, and biometric edge cases.

### Dashboard/Home

Findings:

- The dashboard is a very large UI/business file. It includes profile photo handling, navigation, summaries, sharing, demo seed UI, and many visual sections in one screen.
- Dashboard route navigation checks `AccessControl.canAccessRoute(route)` (`MainActivity.kt:161`), but this is not enough as a security boundary because destinations and data operations need their own checks.
- The dashboard still contains non-production/demo elements such as "Home works" coming-soon behavior (`DashboardScreen.kt:2023-2027`) and hidden demo data seed UI (`DashboardScreen.kt:2934`).
- `AccentAmber` is assigned to `AccentCyan` (`DashboardScreen.kt:84`), which looks like a polish/semantic color bug.
- Several sections use `verticalScroll` with manually rendered content (`DashboardScreen.kt:536`, `DashboardScreen.kt:962`, `DashboardScreen.kt:1340`, `DashboardScreen.kt:2362`, `DashboardScreen.kt:2886`). This can become slow with larger datasets.

Needed before production:

- Split dashboard logic into smaller components and dedicated ViewModels/use cases.
- Remove demo controls and coming-soon cards or gate them behind explicit non-production flags.
- Add screenshot/UI tests for the primary dashboard states.

### Student Management

Findings:

- Student validation only requires name and phone (`StudentViewModel.kt:69-70`, `AddEditStudentScreen.kt:425-426`). There is no strong phone format validation, duplicate phone check, or student-code uniqueness check.
- `generateStudentCode()` uses generated digits/time but does not verify uniqueness before insert (`StudentViewModel.kt:47`, `StudentViewModel.kt:104`).
- Gallery photo URI is stored directly (`AddEditStudentScreen.kt:140-143`, `AddEditStudentScreen.kt:447`, `AddEditStudentScreen.kt:464`). Without copying the image into app-owned storage or taking persistable permissions, images can break after reboot, provider cleanup, or permission revocation.
- Camera images are created in cache via FileProvider and can be lost if cache is cleared.
- `StudentProfileScreen.kt` is large and performs direct actions for payment/profile/printing flows. It creates a WebView for printing (`StudentProfileScreen.kt:1266-1268`); this needs manual lifecycle/security verification even though this audit did not find remote WebView content.

Needed before production:

- Add unique indexes or DAO checks for student code and institute-scoped identifiers.
- Copy selected photos to app-owned storage and store stable references.
- Add tests for duplicate student creation, photo persistence, archive/delete behavior, and registration approval.

### Batch Management

Findings:

- Batch creation allows zero fee because it rejects only `feeAmount < 0` (`BatchViewModel.kt:31-36`), while update behavior appears stricter elsewhere. This creates inconsistent business rules.
- `maxStudents` defaults to 50 (`BatchViewModel.kt:56`) but enrollment does not enforce it.
- Enrollment directly inserts `BatchStudentEntity` from UI code (`BatchDetailScreen.kt:1330`) and lacks a unique index on active institute/batch/student enrollment.
- Removed students can be re-added with a new row. That may be intended for history, but active duplicate prevention should be enforced in the DB or repository.

Needed before production:

- Define and enforce batch uniqueness, allowed fee ranges, and max-student behavior.
- Move enrollment into a transaction-backed repository with duplicate checks.
- Add tests for duplicate enrollment, max-student limits, and archive/reactivate flows.

### Fee Collection

Strengths:

- The main repository validates positive payment amounts and overpayment (`FeeCollectionRepository.kt:239-248`).
- Main collection flows are transactional (`FeeCollectionRepository.kt:37`, `FeeCollectionRepository.kt:80`, `FeeCollectionRepository.kt:143`, `FeeCollectionRepository.kt:170`).
- Existing tests cover core fee create/collect behavior and overpayment rejection.

High-risk findings:

- Payment-history edit bypasses `FeeCollectionRepository`, directly recomputes paid totals, updates a fee, inserts a payment, and inserts a receipt (`UnifiedCollectScreen.kt:671-740`). This can corrupt ledgers if business rules change, if concurrent edits occur, or if fee period/batch/date changes are applied incorrectly to the whole fee.
- Receipt numbers are generated as `REC-$now` (`FeeCollectionRepository.kt:262`) and are not protected by a unique DB constraint. Same-millisecond collisions are unlikely but possible under automated/bulk flows.
- DAO atomic update methods exist (`FeeDao.kt:41-44`) but the repository still uses read/copy/update patterns (`FeeCollectionRepository.kt:203`, `FeeCollectionRepository.kt:259`).
- `createFee` does not enforce a unique fee per student/batch/period/type in the database; duplicate prevention appears mostly UI-side.
- Payment proof/image evidence is not consistently modeled as a durable field in `PaymentEntity`.

Needed before production:

- Route all fee/payment/receipt mutations through one repository/service.
- Add immutable audit logs for edits, reversals, discounts, cancellations, and receipt regeneration.
- Add unique constraints for receipt number and fee business keys.
- Add tests for edit payment, duplicate fee creation, receipt collisions, cancellation/refund, and concurrent collection.

### Attendance

Strengths:

- Student attendance has a unique index on institute/batch/student/date (`AttendanceEntity.kt:7-9`).
- Staff batch filtering exists when loading the batch list (`AttendanceViewModel.kt:126-127`).

Findings:

- `loadBatchStudentsAndAttendance()` and `markAttendance()` accept a `batchId` and do not re-check that the staff user is assigned to that batch (`AttendanceViewModel.kt:138-169`). If a route or call path is reached directly, staff may mark unassigned batches.
- Staff attendance has no unique index on institute/staff/date (`StaffAttendanceEntity.kt:6-11`), and `insertOrUpdateAttendance` uses conflict replacement only by primary key (`StaffAttendanceDao.kt:19`). Duplicate staff attendance records are possible.
- `sendAbsentMessage()` inserts a record with sent status before launching WhatsApp/SMS (`AttendanceViewModel.kt:242-257`). If the external app fails or the user cancels, the app may still treat the message as sent.
- `sendAllAbsentMessages()` loops through individual external intents (`AttendanceViewModel.kt:270-278`), which is not reliable bulk delivery.

Needed before production:

- Re-check staff assignment before every attendance write.
- Add unique index for staff attendance.
- Track absent-message states such as pending, launched, confirmed, failed, and retried.

### Staff Management

Findings:

- Staff add/update functions check `SessionManager.isAdmin()` (`StaffViewModel.kt:100`, `StaffViewModel.kt:183`), which is good, but this remains client-side.
- Staff password minimum is only four characters (`StaffViewModel.kt:118`) and then uses the weak app password hasher.
- Staff archive only archives the staff profile (`StaffViewModel.kt:267-271`). The linked `UserEntity` remains in the users table, although login does check staff status and archived state. This stale account should be disabled or locked explicitly.
- Salary generation computes net amount from components (`SalaryViewModel.kt:52`) but does not reject negative input components. It checks duplicates by reading existing salaries (`SalaryViewModel.kt:56-59`) instead of relying on a unique index/transaction.
- `markAsPaid` is one-way and does not create an audit trail (`SalaryViewModel.kt:85`).

Needed before production:

- Enforce strong password policy and account lock/disable state.
- Add unique salary constraints by institute/staff/month.
- Add salary edit/reversal audit logs.

### Billing and Subscription

Critical findings:

- Subscription expiry check reads `Institutes/{instituteId}.trialEndDate` and returns not expired on exception/offline (`MainActivity.kt:560-568`). This is a direct bypass.
- Super-admin writes `subscriptionEndMs`, `trialEndDate`, `studentLimit`, and `isActive` (`SuperAdminScreen.kt:135-176`), while local `InstituteEntity` uses `currentPlanId`, `trialEndDateMs`, and `currentPeriodEndMs` (`InstituteEntity.kt:10-14`). Field mismatch makes enforcement unreliable.
- Super-admin block toggles `isActive` (`SuperAdminScreen.kt:152-153`), but the app's startup expiry check only uses `trialEndDate`.
- Pricing buttons open WhatsApp purchase inquiries (`PricingScreen.kt:276-285`, `PricingScreen.kt:352-355`); there is no payment confirmation, entitlement update, server activation, or receipt verification in the audited code.
- `onSubscribe` is passed to `PricingScreen` (`PricingScreen.kt:142`) but is not the primary activation path.

Needed before production:

- Put subscription entitlement and block checks behind server-verified state and secure Firestore rules.
- Use one canonical collection name and schema for institutes.
- Make blocked/inactive state enforceable and deny app access even offline after stale grace rules are exhausted.

### Backup/Restore, Export, and Settings

Findings:

- Android backup is enabled (`AndroidManifest.xml:13-15`) and the XML files do not exclude Room databases/shared preferences. This can back up sensitive local data and session-related preferences.
- FileProvider exposes the full cache root for `student_photos` (`file_paths.xml:3`) and exports under cache (`file_paths.xml:4`), broader than necessary.
- Data export writes PII and financial data to CSV in cache and shares it (`DataExporter.kt:19-89`). There is no encryption, password protection, or CSV formula-injection protection.
- `DataExporter.exportAllToCsv()` runs under `Dispatchers.IO`, then calls `context.startActivity` inside that path (`DataExporter.kt:19`, `DataExporter.kt:83-89`). Starting UI intents should be returned to the main thread.
- Settings exposes "Reset Demo Data" and calls `db.clearAllTables()` from UI code (`SettingsScreen.kt:210-257`). This should never be reachable in production except by a protected test/dev build.

Needed before production:

- Disable backup or exclude database/shared preferences/secrets.
- Narrow FileProvider paths.
- Add encrypted export option and CSV injection escaping.
- Remove or strongly gate reset/demo actions.

### Settings/Profile

Findings:

- Settings contains powerful operations (export/reset) and relies on route gating rather than defense-in-depth checks in the action itself.
- Profile/dashboard photo handling uses cache FileProvider URIs (`DashboardScreen.kt:466-468`), which can be fragile for long-term profile images.
- Several screen-level operations catch broad exceptions and surface generic messages; production logs and user errors should be structured.

Needed before production:

- Require explicit admin checks inside settings actions.
- Move export/reset into a ViewModel/repository with confirmation, logging, and environment gating.

## 4. Critical Bugs

### C1. Public Firestore read/write rules expose production data

Severity: Critical  
Evidence: `firestore.rules:6-20`, `web_form/register.html:343`, `web_form/register.html:460-463`, `RegistrationRepository.kt:13-17`, `RegistrationRepository.kt:53`  
Impact: Anyone with the Firebase project details can read/write/delete registration and institute data under the opened collections. This includes student names, phone numbers, gender/class/form fields from the web form, plus institute metadata. Attackers can spam registrations, tamper approvals, scrape PII, or overwrite institute registration info.  
Root cause: Rules use `allow read, write: if true` for `registrations`, lowercase `institutes`, and `Demo_Visitors`.  
Fix direction: Require Firebase Auth and tenant-specific authorization. Validate create-only web submissions with App Check, rate limits, strict field allowlists, server timestamps, and no public reads. Move approval/admin operations behind authenticated institute staff/admin rules or Cloud Functions.

### C2. Release signing credentials are committed in Gradle

Severity: Critical  
Evidence: `app/build.gradle.kts:27-33`  
Impact: If the keystore is present or leaked, an attacker could sign malicious releases or impersonate app updates outside Play App Signing protections. Even without the keystore, credentials in source are a severe release-process failure.  
Root cause at audit time: the legacy release signing configuration contained plaintext passwords.
The values have been redacted from the repository; any key that used them must be rotated before
production use.
Fix direction: Rotate the release key if it has ever been used. Move signing config to CI secrets or local untracked properties. Do not keep release keystores or passwords in the repository.

### C3. Demo accounts with known passwords are seeded in app startup/login paths

Severity: Critical  
Evidence: `BatchFeeApp.kt:42`, `AuthScreen.kt:80`, `AuthScreen.kt:201`, `AuthScreen.kt:245`, `AppDatabase.kt:153-160`, `AppDatabase.kt:220-254`, `AppDatabase.kt:716`  
Impact: A production install may contain privileged known credentials (`123456`) for owner/admin/staff demo users. This is account takeover by design if real data is also present.  
Root cause: Demo data seeding is not isolated to a debug-only product flavor or test-only path.  
Fix direction: Remove demo seeding from release. Use build flavors or a debug-only initializer. Add a release build check that fails if demo credentials or seed calls are present.

### C4. Subscription and blocking can be bypassed or fail open

Severity: High  
Evidence: `MainActivity.kt:560-568`, `SuperAdminScreen.kt:135-176`, `InstituteEntity.kt:10-14`, `firestore.rules:25`  
Impact: Expired or blocked institutes can continue using the app when offline, when Firestore fails, or when the expected cloud document/fields are unavailable. Blocking by `isActive` may not take effect because the app checks only `trialEndDate`.  
Root cause: Client-side entitlement logic fails open and uses inconsistent collection/field names.  
Fix direction: Use server-authoritative entitlements, a single schema, fail-closed behavior after a small verified grace period, and secure Firestore rules for entitlement reads.

### C5. Payment-history edit path can corrupt financial records

Severity: High  
Evidence: `UnifiedCollectScreen.kt:671-740`, `FeeCollectionRepository.kt:239-298`  
Impact: Editing a past payment can rewrite fee totals, date/period/batch metadata, payment records, and receipts outside the repository rules. It may create incorrect balances, duplicate receipts, and inconsistent audit history.  
Root cause: A screen-level edit path bypasses the centralized transaction/service path.  
Fix direction: Implement payment edit/reversal as a repository transaction with immutable audit entries and tests. Prefer reversal plus new payment over destructive mutation.

## 5. Business Logic Issues

Fee and ledger:

- No DB-level unique constraint for receipt number (`PaymentEntity.kt:15`, `ReceiptEntity.kt:13`) or fee business keys (`FeeEntity.kt:6-12`).
- `BatchPaymentViewModel` uses `MMM yyyy` for current month (`BatchPaymentViewModel.kt:44-45`), while seeded/demo and collection flows use `MMMM yyyy` (`AppDatabase.kt:282`, `UnifiedCollectScreen.kt:1453`). Current-month stats may miss fees.
- `BatchPaymentViewModel` maps each student to the first matching fee only (`BatchPaymentViewModel.kt:108`), so multiple fee rows per student/batch can be undercounted.
- Duplicate fee creation is not enforced in the database.

Student/batch:

- Student code uniqueness is not enforced.
- Batch max-student limit is not enforced.
- Active duplicate enrollment is not protected by a unique index.
- Batch fee validation is inconsistent between add/update behavior.

Registration:

- Approval inserts a student and deletes the pending remote registration (`RegistrationListViewModel.kt:60-101`) without a visible duplicate-student check.
- Web form requires only basic fields and trusts an institute id query parameter (`web_form/register.html:368`, `web_form/register.html:442-463`).

Attendance:

- Student attendance has strong uniqueness, but staff attendance does not.
- Absent-message status is recorded before external delivery is confirmed.

Billing/subscription:

- Plan upgrade is a WhatsApp inquiry rather than entitlement activation.
- Super-admin writes fields that do not fully align with local billing/enforcement models.

Needs manual verification:

- Whether production release builds actually include the release keystore file.
- Whether any Firebase API key restrictions exist in the Firebase console.
- Whether Play App Signing is enabled and whether the committed release credentials have ever been used.
- Whether registration spam/rate limiting exists outside this repository.

## 6. Security Vulnerabilities

Critical/high:

- Public Firestore read/write on sensitive collections (`firestore.rules:6-20`).
- Hardcoded release signing credentials (`app/build.gradle.kts:27-33`).
- Demo privileged credentials (`AppDatabase.kt:220-254`, `AppDatabase.kt:716`).
- Weak password hashing (`PasswordHasher.kt:7-24`).
- Biometric session data stored in normal SharedPreferences (`BiometricAuthManager.kt:28`, `BiometricAuthManager.kt:61-68`).
- Android backup enabled without sensitive-data exclusions (`AndroidManifest.xml:13-15`, `backup_rules.xml`, `data_extraction_rules.xml`).
- Broad FileProvider cache exposure (`file_paths.xml:3-4`).
- Subscription check fails open (`MainActivity.kt:560-568`).

Medium:

- Firebase App Check is commented out with a production TODO (`BatchFeeApp.kt:28-30`).
- Crashlytics collection is enabled unconditionally (`BatchFeeApp.kt:25`), which may require consent/privacy review depending on target market.
- Firestore cache size is unlimited (`BatchFeeApp.kt:37`), which can retain cloud data locally without a cap.
- CSV export of student and financial PII lacks encryption and formula-injection handling (`DataExporter.kt:19-89`).
- `printStackTrace()` appears in production paths.

Firebase API key note:

- The Firebase web/API key appears in `web_form/register.html:343` and `app/google-services.json`. Firebase API keys are not secrets by themselves, but they become dangerous when combined with permissive rules. Here, the permissive rules are the critical issue.

## 7. QA Testing Gaps

Existing tests found:

- `app/src/test/java/com/example/data/repository/FeeCollectionRepositoryTest.kt`: focused fee repository tests.
- `app/src/test/java/com/example/MainActivityTest.kt`: class-name stability only.
- `app/src/test/java/com/example/ExampleUnitTest.kt`: sample arithmetic test only.
- `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt`: package-name sample only.
- `app/src/test/screenshots/greeting.png`: screenshot artifact/reference only.

Major missing coverage:

- Auth: password migration, demo account exclusion in release, staff inactive/archived login denial, biometric login behavior.
- Authorization: every staff permission route and direct destination access.
- Subscription: expired, blocked, offline, missing Firestore doc, field mismatch, fail-open behavior.
- Registration: web form validation, spam/tamper cases, approval duplicate prevention, reject/delete behavior.
- Fee: duplicate fee, edit payment, cancellation/reversal, receipt uniqueness, concurrent collection, discount changes, payment proof persistence.
- Student: duplicate student code/phone, photo persistence, archive/delete.
- Batch: duplicate enrollment, max-student enforcement, removed/re-added students.
- Attendance: staff assignment enforcement, staff attendance uniqueness, absent-message delivery failure.
- Export/backup: CSV escaping, sensitive field coverage, backup exclusion.
- UI: Compose screenshot/regression tests for dashboard, fee collect, student profile, attendance, settings.
- Migration: Room migrations with schema export and destructive-migration prevention in release.

Build/test status:

- No Gradle build or test command was run during this audit to comply with the read-only/no-artifacts requirement.

## 8. Performance Issues

High/medium:

- Large Compose screens render complex state and manual lists. This increases recomposition cost and makes regressions hard to isolate.
- Multiple `verticalScroll` usages on screens that may hold dynamic collections should be reviewed and replaced with `LazyColumn`/`LazyVerticalGrid` where appropriate.
- Dashboard and attendance summary code includes per-batch/per-student query patterns that can become N+1 behavior (`AttendanceViewModel.kt:291-293` and related dashboard summary flows).
- `FeeViewModel` enrichment reads all students/batches for due-fee mapping, which may be acceptable for small data but should be profiled for larger institutes.
- Firestore cache is unlimited (`BatchFeeApp.kt:37`).
- CSV export loads all major tables into a single export operation (`DataExporter.kt:19-89`).

Recommended profiling:

- 1,000 students, 50 batches, 12 months of fees/payments.
- Dashboard cold start and warm recomposition.
- Fee collection search/filter latency.
- Attendance marking for large batches.
- CSV export memory/time and share intent behavior.

## 9. Code Quality and Maintainability

Maintainability risks:

- Huge screen files make business rules hard to audit and easy to bypass.
- Inconsistent collection and field naming between local and cloud code (`institutes` vs `Institutes`; `trialEndDate` vs `trialEndDateMs`/`currentPeriodEndMs`).
- Many business invariants are enforced only in UI or ViewModel code, not in the database/repository.
- Room schema export is disabled, limiting migration review.
- Broad `catch (Exception)` blocks with `printStackTrace()` reduce observability quality.
- Default/generated docs are not production docs. `README.md` still contains generic AI Studio guidance and mentions debug signing cleanup rather than a complete release process.

Suggested refactor direction:

- Create use cases/repositories for enrollment, payment edit/reversal, registration approval, settings export/reset, staff salary, and subscription entitlement.
- Add database constraints for core invariants.
- Split large Compose screens into pure UI components plus smaller state holders.
- Add typed error models and user-safe error messages.
- Add a release checklist that blocks demo data, hardcoded signing config, permissive rules, debug backup config, and missing App Check.

## 10. Production Readiness Verdict

Current status: Not production ready.

Safe for:

- Local demo.
- Internal development.
- Limited pilot only with fake data and private Firebase rules.

Not safe for:

- Play Store release.
- Real student/parent PII.
- Real payments/financial records.
- Multi-institute production usage.
- Any deployment where the web registration form is public with the current Firebase rules.

Minimum production gates:

- Firebase rules locked down and verified.
- Demo seeding removed from release.
- Release signing credentials rotated and moved to secrets.
- Password/biometric storage upgraded.
- Backup/FileProvider/export sensitive-data handling fixed.
- Subscription enforcement made server-authoritative and consistent.
- Payment edit/reversal moved into transaction-backed repository with tests.
- Release build and test suite run in CI with artifact review.

## 11. Prioritized Fix Roadmap

Top 5 critical/high fixes:

1. Lock down Firestore rules for registrations, institutes, super-admin, config, and notifications. Add App Check and field validation for web registration.
2. Remove hardcoded release signing credentials, rotate keys if used, and move signing to secure CI/local secrets.
3. Remove demo seeding and known passwords from all release paths. Add a release build guard.
4. Replace password hashing and biometric SharedPreferences with production-grade credential/session storage.
5. Centralize all fee/payment/receipt edits in a transaction-backed repository with immutable audit logs.

Top 5 medium fixes:

1. Add Room unique indexes for receipt numbers, active batch enrollment, student code, staff attendance, salary month, and fee business keys.
2. Normalize month/period formatting and update batch payment summaries to aggregate all relevant fees.
3. Make subscription/block enforcement use one canonical schema and fail closed after a defined grace policy.
4. Disable or narrow Android backup and FileProvider paths; encrypt or protect CSV export.
5. Move registration approval, settings reset/export, enrollment, and salary generation into tested use cases.

Top 5 UI/UX polish fixes:

1. Remove demo/coming-soon controls from release UI.
2. Replace long manual scroll lists with lazy lists for large datasets.
3. Add clearer loading/error/empty states for subscription, registration approval, export, and sync failures.
4. Stabilize photo handling so selected/captured images persist.
5. Add screenshot regression tests for dashboard, collect payment, student profile, attendance, staff, and settings.

Top 5 future improvements:

1. Server-side audit log and admin dashboard for subscription and institute management.
2. Cloud sync strategy with conflict handling for multi-device usage.
3. Encrypted backup/restore with user-controlled password or platform keystore integration.
4. Formal privacy policy/data retention implementation for student PII, Crashlytics, and exports.
5. CI pipeline that runs unit tests, instrumented smoke tests, lint, dependency checks, Firebase rules tests, and release configuration checks.

## Files Inspected

Primary files inspected:

- `README.md`
- `.project-context.md`
- `PROJECT_AUDIT_REPORT.md`
- `HOME_SCREEN_POLISH_REPORT.md`
- `settings.gradle.kts`
- `build.gradle.kts`
- `app/build.gradle.kts`
- `AndroidManifest.xml`
- `firestore.rules`
- `firebase.json`
- `app/google-services.json`
- `debug.keystore.base64`
- `web_form/register.html`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/res/xml/file_paths.xml`
- `app/src/main/java/com/example/BatchFeeApp.kt`
- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/data/database/AppDatabase.kt`
- `app/src/main/java/com/example/data/database/DemoDataSeeder.kt`
- `app/src/main/java/com/example/data/firestore/RegistrationRepository.kt`
- `app/src/main/java/com/example/data/dao/FeeDao.kt`
- `app/src/main/java/com/example/data/dao/StaffAttendanceDao.kt`
- `app/src/main/java/com/example/data/models/*.kt`
- `app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt`
- `app/src/main/java/com/example/domain/PasswordHasher.kt`
- `app/src/main/java/com/example/domain/BiometricAuthManager.kt`
- `app/src/main/java/com/example/domain/SessionManager.kt`
- `app/src/main/java/com/example/domain/StaffAccess.kt`
- `app/src/main/java/com/example/domain/ForceUpdateChecker.kt`
- `app/src/main/java/com/example/domain/DataExporter.kt`
- `app/src/main/java/com/example/ui/auth/AuthScreen.kt`
- `app/src/main/java/com/example/ui/dashboard/DashboardScreen.kt`
- `app/src/main/java/com/example/ui/dashboard/SettingsScreen.kt`
- `app/src/main/java/com/example/ui/students/StudentViewModel.kt`
- `app/src/main/java/com/example/ui/students/AddEditStudentScreen.kt`
- `app/src/main/java/com/example/ui/students/StudentProfileScreen.kt`
- `app/src/main/java/com/example/ui/batches/BatchViewModel.kt`
- `app/src/main/java/com/example/ui/batches/BatchDetailScreen.kt`
- `app/src/main/java/com/example/ui/batches/BatchPaymentViewModel.kt`
- `app/src/main/java/com/example/ui/fees/FeeScreens.kt`
- `app/src/main/java/com/example/ui/fees/FeeDashboardScreen.kt`
- `app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt`
- `app/src/main/java/com/example/ui/attendance/AttendanceViewModel.kt`
- `app/src/main/java/com/example/ui/staff/StaffViewModel.kt`
- `app/src/main/java/com/example/ui/staff/StaffAttendanceScreen.kt`
- `app/src/main/java/com/example/ui/staff/SalaryViewModel.kt`
- `app/src/main/java/com/example/ui/pricing/PricingScreen.kt`
- `app/src/main/java/com/example/ui/billing/BillingScreen.kt`
- `app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt`
- `app/src/main/java/com/example/ui/registrations/RegistrationListViewModel.kt`
- `app/src/test/java/com/example/data/repository/FeeCollectionRepositoryTest.kt`
- `app/src/test/java/com/example/MainActivityTest.kt`
- `app/src/test/java/com/example/ExampleUnitTest.kt`
- `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt`

Notes:

- This was a source/configuration audit, not a dynamic runtime audit.
- Build, lint, emulator, Firebase emulator, and Play Store signing validation were not run.
- Findings marked "needs manual verification" require external console/device/build information not available from source alone.
