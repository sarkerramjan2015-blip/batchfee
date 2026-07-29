# Problem 01 — Text Encoding Audit

## Scope

This audit covers the main Android app only (`app/`). The `student_app` and
`web_form` projects were not inspected for changes and must not be modified by
this fix.

## Architecture inspected

- **Framework/UI:** Kotlin Android app using Jetpack Compose.
- **State:** screen-level `ViewModel`s plus Compose state.
- **Local storage/cache:** Room database (`AppDatabase`, `batchfee_database`).
- **Remote storage:** Firebase Firestore, with local Firestore persistence
  enabled in `BatchFeeApp.kt`.
- **HTTP/JSON:** OkHttp + Moshi in `data/firebase/FirebaseAuthApi.kt` for
  Firebase Auth REST requests; Firestore SDK is used for application data.
- **Localization:** production UI text is primarily hard-coded Kotlin text;
  `res/values/strings.xml` does not contain the affected labels.

## Confirmed root cause

The affected Kotlin source files were saved with UTF-8 bytes incorrectly
decoded as Windows-1252 and then re-saved as UTF-8 (classic mojibake). This is
confirmed by a strict, read-only conversion check: every affected file can be
round-tripped from Windows-1252 bytes to valid UTF-8, while unaffected files
do not require the conversion.

Examples found in source before the repair:

- `ui/students/StudentProfileScreen.kt:1615`:
  `Batch Fee â€¢ Monthly â€¢ ...`
- `ui/fees/UnifiedCollectScreen.kt:1211`:
  `1 Month Â· ...`
- `ui/dashboard/DashboardScreen.kt:1196`:
  `Trial Â· ... days remaining`
- `ui/billing/BillingScreen.kt:256`:
  `Plan: ... Â· ... Month(s)`
- `ui/exams/ExamScreen.kt:602`:
  `Result â€” ...`
- `data/database/AppDatabase.kt:993`: corrupted birthday emoji
  `ðŸŽ‚` in demo data.

The same corruption also appeared in source comments, arrows, box drawing
characters, receipt/PDF text, share text, and static demo data.

## Affected modules and files

Affected production source exists in these modules (the repair is limited to
the files whose whole-file round-trip was verified):

- App/bootstrap: `MainActivity.kt`, `data/dao/FeeDao.kt`,
  `data/database/AppDatabase.kt`, `data/database/DemoDataSeeder.kt`,
  `data/firebase/FirebaseAuthApi.kt`, `data/firestore/StudentSyncHelper.kt`,
  `domain/ForceUpdateChecker.kt`.
- Auth/attendance/billing: `ui/auth/AuthScreen.kt`,
  `ui/attendance/AttendanceScreens.kt`, `ui/billing/BillingScreen.kt`,
  `ui/update/ForceUpdateScreen.kt`.
- Students/batches/staff: `ui/students/BirthdayReminderScreen.kt`,
  `ui/students/StudentListScreen.kt`, `ui/students/StudentProfileScreen.kt`,
  `ui/batches/AddEditBatchScreen.kt`, `ui/batches/BatchDetailScreen.kt`,
  `ui/batches/BatchListScreen.kt`, `ui/batches/BatchPaymentViewModel.kt`,
  `ui/staff/AddEditStaffScreen.kt`, `ui/staff/SalaryScreen.kt`,
  `ui/staff/StaffListScreen.kt`, `ui/staff/StaffProfileScreen.kt`,
  `ui/staff/StaffViewModel.kt`.
- Fees/other screens: `ui/fees/FeeDashboardScreen.kt`,
  `ui/fees/FeeScreens.kt`, `ui/fees/UnifiedCollectScreen.kt`,
  `ui/exams/ExamScreen.kt`, `ui/enquiries/EnquiryListScreen.kt`,
  `ui/expenses/ExpenseScreen.kt`, `ui/pricing/PricingScreen.kt`,
  `ui/registrations/RegistrationListScreen.kt`, `ui/reports/ReportsScreen.kt`,
  `ui/dashboard/DashboardScreen.kt`, `ui/dashboard/SettingsScreen.kt`,
  `ui/superadmin/SuperAdminScreen.kt`, and `ui/components/PhoneInputField.kt`.

## Source vs runtime/storage findings

| Source | Finding |
| --- | --- |
| Source literals | **Confirmed cause.** Corrupted literals drive the visible UI, receipt/PDF, share text, and demo defaults. |
| Moshi/OkHttp JSON | No incorrect UTF-8, Latin-1, byte, or double-serialization conversion found. Firebase Auth already accepts the existing JSON request media type; no transport behaviour change is needed for this source-literal issue. |
| Firestore/API | No client-side decoding conversion found. Firestore strings are read/written directly. No backend source is present here to audit server headers or database charset. |
| Room/local cache | Room stores strings directly. Old demo/static values and any old cached value written while the source was corrupt can remain corrupt. |
| URI/HTML | `URLEncoder`/`Uri.encode` usage is confined to WhatsApp URL query text and is not used to decode ordinary display text. No HTML decoding was found. |

## Safe implementation plan

1. Apply the verified, source-only Windows-1252-to-UTF-8 repair to the 36
   affected Kotlin files and save them as UTF-8.
2. Standardize metadata separators to `•`; retain `—` only for intended em
   dashes and retain other intended Unicode (for example emoji and arrows).
3. Set the Firebase Auth REST request media type to
   `application/json; charset=utf-8`.
4. Add focused tests and a repository validation script. The script will scan
   production Kotlin sources only and ignore generated/build/third-party files.

## Risks and limits

- Updating source fixes future display, generated text, and newly seeded demo
  data. It cannot alter already-corrupted Firestore or production Room data
  without an explicit, reviewed data migration.
- The repository has no backend/database service source, so server response
  headers and existing remote records cannot be verified or safely repaired
  here.
- Existing production Room/Firestore records could not be inspected from this
  repository. No migration utility is included because an uninvoked generic
  converter would not safely establish which production records need repair.
  Any future data repair must start with a backed-up, reviewed export.

## Implementation and validation status

Implemented in this repository:

- Repaired the verified source corruption in 36 Kotlin files, retaining the
  existing UI structure and behaviour.
- Replaced ordinary metadata separators with `•`, including fee, plan,
  dashboard, receipt, and share text. Intended result labels retain `—`.
- Corrected static PDF/canvas receipt text, result/share text, and new demo
  defaults at their sources.
- Kept Firebase Auth request behaviour unchanged: the encoding defect is not
  in the API transport layer.
- Added unit tests for valid Bangla/Unicode preservation and Moshi UTF-8
  parsing.
- Added `scripts/check-mojibake.ps1`; it passes against production Kotlin
  sources. A strict UTF-8 read also passes for every main-app Kotlin file.

The corrected source examples now include:

- `Batch Fee • Monthly • 1000`
- `1 Month • Running Month`
- `Trial • 101 days remaining`
- `Result — Shajeda Akter Azmi`
- `Thank you • ICT TOPPERS`

## Strict verification (current working tree)

- **Affected and changed production Kotlin files:** 36. The complete list in
  “Affected modules and files” matches the 36 tracked Kotlin files in the
  current Git diff; no repaired source file is missing.
- **Diff review:** 35 files match the verified Windows-1252-to-UTF-8 repair
  exactly (with metadata middle dots standardized to `•`). The sole additional
  text-only change is in `ui/fees/UnifiedCollectScreen.kt`: receipt footer
  text uses `Thank you • …`, as requested. No business logic, layout, color,
  navigation, authentication, fee calculation, Room schema, or Firestore
  mapping changed.
- **Source validation:** `scripts/check-mojibake.ps1` passes. A strict UTF-8
  read passes for every production Kotlin file. No production Kotlin file
  contains the known broken marker sequences.
- **Expected strings:** source templates contain the corrected Batch Fee,
  plan/billing, trial-status, result, and receipt separator characters.
  “1 Month • Running Month”, “Trial • 101 days remaining”, and
  “Basic • 1 Month” are runtime values composed from the corrected templates,
  not single hard-coded literals.
- **Bangla source:** there are no Bangla literals in the production Kotlin
  files to rewrite; the added unit test checks a Bangla/Unicode JSON round trip
  and the source files all passed strict UTF-8 decoding.
- **Build status:** `gradlew.bat testDebugUnitTest` was attempted but stopped
  before Gradle started because this PC has no usable JDK (`JAVA_HOME` is
  empty, `java` is absent from `PATH`, and no Android Studio JBR/common JDK is
  installed). Debug/release build, lint, unit-test execution, and runtime
  device/emulator validation are therefore not verified.

No production database or Firestore repair was executed.
