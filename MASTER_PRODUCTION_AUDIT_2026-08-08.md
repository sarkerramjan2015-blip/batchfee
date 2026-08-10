# BatchFee Master Production Audit

**Audit date:** 2026-08-08
**Scope:** Current Android/Kotlin source, Firestore rules, release configuration, local data model, sync design, and existing test artifacts.
**Method:** Static source review and local build/test-artifact inspection. No source, Firebase, or production data was changed.

## Release verdict

**NO-GO for production release.** The current app is suitable only for internal development/QA. Critical authorization, subscription, student-app, financial-integrity, privacy, and release-signing issues must be resolved and independently retested before any real institute data is onboarded.

## Current evidence

- Branch: `main`; last committed change: `a317dc9` (2026-08-05).
- The worktree contains 46 modified tracked files plus untracked student-app code and test screenshots. It is not a controlled release candidate.
- A debug APK exists at `app/build/outputs/apk/debug/app-debug.apk` (2026-08-08).
- Four test suites report **12 passing tests** (8 fee repository, 2 session manager, 2 basic tests). The Gradle task itself ended with a missing temporary test-result file, so the verification command is not a clean green build.
- The test report warns that Robolectric is running with Java 17 while Android SDK 36 requires Java 21. There is no evidence of Firebase-rule, real-auth, upgrade, migration, destructive-delete, or end-to-end UI coverage.

## P0 — production blockers

### P0-01: Firestore permits cross-tenant institute data disclosure

`firestore.rules` allows every authenticated account to read every `/institutes/{instituteId}` document. Those documents contain owner contact data, institute data, subscription information, and `securityPin`. A student or staff member from one institute can therefore read private data from other institutes if they know or enumerate IDs.

**Required fix:** split public branding from private institute data; restrict private reads to the owning institute, explicitly-authorized staff, or a verified server role. Remove `securityPin` from readable Firestore documents entirely.

### P0-02: Owner and staff Firestore permissions are not a production security boundary

The owner may write every field in their own institute document. This lets a modified client change `isActive`, `currentPeriodEndMs`, `currentPlanId`, student/staff limits, and other subscription fields. The generic subcollection rule gives any staff document read/write access to all business collections. `isActiveStaff()` only checks document existence, not `status == "active"`; archived/inactive staff retain cloud access.

**Impact:** subscription bypass; unauthorized reads/writes of fees, receipts, payroll, attendance, students, and reports.

**Required fix:** use a backend (Cloud Functions/Cloud Run with Admin SDK) for entitlements and privileged mutations. Make Firestore rules schema-specific and role/permission-aware; deny writes to entitlement fields from client apps; verify staff status and per-feature permission in rules and backend.

### P0-03: Student app login is currently broken and unsafe

New student accounts are created with random virtual emails (`studentCode.instituteCode.random@s.batchfee.app`), but no `student_login_mappings` document is written during student creation. Student login first requires that mapping; its legacy fallback tries only `studentCode@s.batchfee.app`, which does not match the generated account. New student accounts therefore cannot reliably sign in.

If a mapping exists, the rules compare Firebase Auth UID with the Room/Firestore student document ID. They are different IDs, so student profile, fee, attendance, result, batch, and work reads are denied or return empty. The mapping itself is publicly readable and can be created or updated by any authenticated account, allowing PII enumeration and account-link tampering.

Student logout clears only local student preferences; it does not call Firebase Auth sign-out. Student sessions are indefinitely persisted in normal SharedPreferences.

**Required fix:** redesign student authentication before release. Store a Firebase UID on the student record; create and manage accounts only through trusted backend code; use non-enumerable lookup/claim-based identity; lock rules to `request.auth.uid`; remove public mapping documents; sign out Firebase on logout; use an encrypted, expiring session only where needed.

### P0-04: Student password hashes and account metadata are exposed to staff

Student Firestore documents include `studentPasswordHash`, `appAccessEmail`, and app-access status. The current generic staff rule allows every active-or-archived staff document to read those records. Hashing is a single SHA-256 calculation with a salt; the declared iteration constant is not used.

**Required fix:** never store password hashes in Firestore client-readable data. Use Firebase Auth/backend-managed credentials, remove the field from all synced records, rotate/revoke affected student credentials, and use a modern slow password-hashing algorithm only where a server must store a password verifier.

### P0-05: Financial ledger can become inconsistent or be altered without audit

`FeeCollectionRepository` performs remote Firestore writes inside a Room transaction and writes fee, payment, and receipt sequentially. If a later remote write fails, Room rolls back but earlier cloud writes remain; if the device is offline, collection fails rather than safely queuing. Deleting a payment physically deletes payment and receipt records instead of creating an immutable reversal/audit event. Receipt numbers are timestamp-only (`REC-$now`) and have no unique constraint. Fee and enrollment business keys also lack database uniqueness constraints.

Because Firestore grants broad client write access, a modified client can directly alter or delete financial documents.

**Required fix:** move money mutations to a trusted backend transaction; use immutable payment/reversal records and server-generated receipt numbers; enforce unique business keys; make local operations an outbox/queued sync model rather than mixing network requests inside database transactions; reconcile and alert on mismatches.

### P0-06: Permanent deletion can cause irreversible partial data loss

Student deletion removes remote collections in sequence and then removes local records. There is no cross-system transaction, recovery journal, retention policy, or immutable audit trail. A failure midway produces partial remote deletion. The flow also does not reliably remove the corresponding Firebase Auth user or public Cloudinary photo.

**Required fix:** replace hard deletion with soft-delete plus retention policy; execute controlled server-side cascade jobs with idempotency and audit logs; revoke Auth users; delete media; require a verified backup/restore path before purge.

### P0-07: App Check is explicitly disabled; public third-party upload is unrestricted

`BatchFeeApp.kt` contains a TODO to re-enable Firebase App Check. The app also ships a reusable unsigned Cloudinary upload preset and uses public URLs for institute, student, and staff images. Anyone who extracts the preset can consume storage/bandwidth and uploaded student/staff photos are publicly accessible by URL. There is no asset ownership, deletion, or retention control.

**Required fix:** enforce App Check for Firebase services before release; move media uploads behind authenticated signed server-generated uploads (or properly scoped Storage rules); make student/staff media private; record asset ownership and delete media on account deletion.

### P0-08: Release signing is unsafe/not release-ready

`app/build.gradle.kts` silently signs a release build with the debug signing key when `keystore.properties` is missing. Only `keystore.properties.example` is present in the workspace. A production artifact must never fall back to the debug key.

**Required fix:** fail release builds when secure signing credentials are unavailable; use a protected upload key and Play App Signing; verify final APK/AAB certificate, version, minification, and mapping-file handling in CI.

## P1 — must be fixed before broad launch

1. **Subscription enforcement is still client-controlled.** Owner/staff behaviour is not consistently gated; staff are deliberately exempted from periodic expiry checks. Payment requests can be created by any authenticated account for any institute ID. Entitlements, limits, payment approval, and blocking need server authority.
2. **Demo accounts and credentials have been provisioned.** Debug code creates/uses `superadmin@batchfee.app` / `11223344` and `demo@batchfee.app` / `123456`; the login flow also recognizes demo identities outside the debug-only startup seed. Remove the paths and disable/delete these identities and related Firestore records in the actual production Firebase project.
3. **Backups and local PII are uncontrolled.** `android:allowBackup="true"` is enabled while backup/data-extraction XML has no exclusions. Firestore persistence is unlimited. Sensitive Room data, preferences, and cached PII can be backed up/transferred. The `FileProvider` exposes the cache root under `student_photos`.
4. **Export leaks data and permits CSV formula injection.** CSV values are quoted but values beginning with `=`, `+`, `-`, or `@` are not neutralized. Exports contain student, guardian, staff, and financial PII and are shared from cache without encryption or lifecycle cleanup.
5. **No functional backup/restore exists.** The screen says backup/restore is under development while the UI claims data is safely synced. Do not make recovery claims until export, encrypted backup, restore, and disaster-recovery tests exist.
6. **Sync has no conflict model.** Full refresh fetches entire Firestore collections and uses Room `REPLACE`, with no version vector, tombstone, conflict policy, or reliable pending-operation outbox. Concurrent devices can silently overwrite each other and remote deletions are not consistently mirrored locally.
7. **Authorization is inconsistent in the UI.** Route access is primarily navigation-side and can be bypassed by direct navigation; several work/homework/assignment routes are always allowed to staff. The permission map uses invalid uppercase `VIEW_STUDENTS` keys for two routes, while declared permissions are lowercase (`view_student`).
8. **Password/session handling needs hardening.** Password comparison is not constant-time; biometric and student sessions are stored in unencrypted SharedPreferences; production logs include login identifiers; several exceptions are swallowed, hiding sync/data-loss failures.
9. **Database integrity and migrations are under-tested.** Room schema export is disabled. New tables lack key indexes/uniqueness checks; staff attendance, salaries, active enrollments, fees, receipts, and periodic charges can be duplicated by competing writes.
10. **Student privacy retention is incomplete.** Hard deleting records does not clearly remove Firebase Auth identities, cached images, Cloudinary assets, or all public mapping metadata.

## P2 — release quality and maintainability

- Current working tree has a large uncommitted feature set and 3 trailing-whitespace issues in `ReminderTemplatesScreen.kt`.
- Existing `README.md` describes a different AI Studio project rather than accurate Android build, environment, Firebase deployment, support, privacy, and release procedures.
- Several large Compose screens combine presentation, business rules, persistence, networking, and file handling. This increases regression risk.
- Build/test infrastructure needs a clean Java/toolchain alignment: the report indicates SDK 36 with Java 17 for Robolectric.
- There is no evidence of accessibility, low-network/offline, upgrade, Play pre-launch, penetration, Firestore Emulator, backup-restore, or real-device regression testing.

## Required remediation order

1. Freeze release and create a clean release branch; do not commit screenshots, emulator DBs, or unrelated working files.
2. Rotate/disable demo and exposed accounts; audit the production Firebase project; rotate any affected credentials and Cloudinary preset.
3. Redesign Firebase data model, rules, roles, staff permissions, and student authentication around Firebase UID plus backend-authoritative claims/operations. Test rules in the Firestore Emulator.
4. Move subscription, billing, financial ledger, account lifecycle, and destructive deletes to trusted backend transactions and immutable audit trails.
5. Implement secure private media, encrypted data handling, scoped backups/exports, recovery/restore, and retention/deletion policies.
6. Add migration/schema tests, end-to-end test cases, emulator rules tests, real-device smoke tests, and a CI release gate. Resolve the Gradle/JDK test-run instability.
7. Build a signed AAB with no debug fallback; complete Play Console security, Data Safety, privacy-policy, and pre-launch checks.

## Minimum release gate

Production release may be reconsidered only after all P0 findings are closed, P1 findings are either closed or formally accepted with compensating controls, Firebase rules are deployed and emulator-tested, a clean signed release build succeeds, and a realistic multi-institute QA dataset passes end-to-end tests for owner, staff, and student roles.
