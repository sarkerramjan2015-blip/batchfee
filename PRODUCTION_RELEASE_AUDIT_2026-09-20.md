# BatchFee Production Release Audit — Play Store Readiness

Date: 2026-09-20 · Auditor: production-release pass · Status: **NO code changes made yet — awaiting your approval to implement fixes**

Three parallel audits were run: (1) Android app crashes/config, (2) Google Play policy compliance, (3) Firebase backend + security rules. Findings below with file:line evidence.

---

## Executive verdict

The app is **close to release-ready**: targetSdk 36 (exceeds Play requirement), no restricted permissions, no ad SDKs, release signing refuses debug keys, server-side SMS (no SEND_SMS), and storage is fully locked down. But there are **1 critical crash bug, 1 critical backend billing bug, and several major security/rule holes** that must be fixed before shipping. Estimated: all code fixes are small and local; nothing requires redesign.

---

## CRITICAL (must fix before release)

### C1. Room migration chain has gaps → existing users crash on update
`app/src/main/java/com/example/data/database/AppDatabase.kt:711`
- `MIGRATION_11_12` (:164), `MIGRATION_17_18` (:204), `MIGRATION_19_20` (:216) are **defined but missing** from `addMigrations(...)`. Release builds have no `fallbackToDestructiveMigration()` (debug-only), so any user upgrading across those versions crashes with `IllegalStateException: A migration from X to Y was required but not found`.
- **Fix:** add the 3 migrations to the list at :711; enable `exportSchema = true` + `room.schemaLocation` so this class of bug is caught by tests.

### C2. Due-automation month-end is wrong → every monthly due date lands ~30 days early
`functions/src/dueAutomation.js:157-163` (`monthEndMs`)
- `next.setUTCMonth(+1); next.setUTCDate(1); return next.getTime() - 1` returns the **start** of the billing month, not its end. All monthly due items get a `dueDateMs` ~1 month early, so `triggerForDue` escalates a just-overdue month straight to the `overdue_15d` cohort.
- **Fix:** `Date.UTC(year, month + 1, 1) - 6 * 3600 * 1000 - 1` (next month's Dhaka midnight minus 1 ms).

### C3. Production-active demo seeding with hardcoded credentials
`app/src/main/java/com/example/data/database/AppDatabase.kt:767` (`superadmin@batchfee.app` / password `11223344`), `AuthScreen.kt:619-628`
- Login-time demo seeding for `demo@batchfee.app` / `owner@batchfee.app` runs on **release** builds (not gated by `BuildConfig.DEBUG`) and hardcodes credentials in source.
- **Fix:** gate the `AuthScreen` seeding block behind `BuildConfig.DEBUG`; keep the hardcoded demo credentials debug-only.

---

## MAJOR (fix before release, or accept explicitly)

### M1. Firestore rules let Super Admin clients forge server-authoritative fields
`firestore.rules:435-443`
- The super-admin update branch (`superAdminClientUpdateAvoidsSubscriptionControls`, :324-340) does not block `sms_balance`, `total_sms_purchased`, `sms_used_today`, `sms_used_this_month`, `sms_usage_day_key`, `sms_usage_month_key`, `sms_send_method` — a root client can forge the SMS wallet. Also does not block the new churn counters `lastCollectionAtMs` / `lastAttendanceAtMs`.
- The super-admin **create** branch (`allow create: if isSuperAdmin() || ...`, :435) has zero field validation — a root client can create an institute with `subscriptionStatus: "active"`, far-future expiry, and a huge SMS balance, bypassing the callable quote/audit path.
- **Fix:** add the SMS wallet fields + both churn counters to the super-admin blocklist; remove the unconstrained `isSuperAdmin()` create branch (creation is callable-only).

### M2. Due-automation collections fall through the catch-all and are client-writable
`firestore.rules:448-489`
- `due_automation`, `due_reminders`, `due_automation_runs`, `reminder_templates` are absent from the catch-all exclusion list → an owner/admin can write policy and reminder docs directly, bypassing server validation and the credit-checked send path.
- **Fix:** add the four collections to the exclusion list and add explicit `allow read: if …; allow write: if false;` rules.

### M3. BI code correctness bugs (my recent changes — found by fresh-eyes review)
`functions/src/platformAdmin.js`
- **M3a** `smsForecast` (:843) and `instituteRankings` (:773) sum raw `sms_used_this_month` without checking the stored month key → last month's usage inflates "this month", daily burn, rankings, and reorder suggestions. Fix: use `walletDefaults(data, now).sms_used_this_month`.
- **M3b** `smsForecast` (:862) uses `SMS_RECHARGE_CHARGE_PERCENT` (1.8%, owner service charge) instead of `SMS_PROVIDER_RECHARGE_CHARGE_PERCENT` (2%) for provider procurement cost. Fix: import + use the provider constant.
- **M3c** Subscription forecast (:804-819) and dashboard month bucketing (:347-350) use UTC months; a Dhaka-time month boundary mis-buckets revenue. Fix: Dhaka month keys via `dhakaUsageKeys`/`Intl.DateTimeFormat` Asia/Dhaka.
- **M3d** `dashboardMetrics` active predicate (:359) requires `isActive === true`, but the directory and rules treat missing `isActive` as enabled → legacy institutes undercounted. Fix: `isActive !== false`.

### M4. Unbounded backend reads (scaling risk)
`functions/src/platformAdmin.js:341-345, 886-887`
- `institutes.get()` and `collectionGroup("subscription_receipts").get()` are unbounded and run inside a 60 s / 256 MiB callable. Fine at today's ~350 institutes; will OOM/timeout as receipts grow. (Pre-existing in dashboard; my BI action repeats it.)
- **Fix (later phase):** materialize revenue rollups in a scheduled job. Not blocking for this release — document as a known limit.

### M5. Play compliance items
- **M5a** Broken-functionality risk: "Export", "Import Students", "Sample File" menu rows only show "coming soon" snackbars — `StudentListScreen.kt:536-553`. Play rejects prominently-shown non-functional UI. Fix: remove the three rows.
- **M5b** No hosted privacy-policy URL anywhere (manifest/resources/code). Play Console requires a public URL. Fix (console, not code): publish the in-app policy (`LegalScreens.kt`) to a hosted page (e.g., `batchfee-477b8.web.app/privacy`) and enter it in App Content.
- **M5c** Backup rules are the untouched Android sample — `AndroidManifest.xml:15-17`, `res/xml/backup_rules.xml`, `data_extraction_rules.xml` → the Room DB with student/fee PII is auto-backed up to Google Drive. Fix: `android:allowBackup="false"` (Room is a cache; source of truth is Firestore) + declare accurately in Data Safety.
- **M5d** B2B subscription collected via manual bKash/Nagad + admin approval, outside Play Billing (`PricingScreen.kt:704-728`). Generally exempt for B2B SaaS, but reviewers can flag it. Prepare a written B2B justification for review; do not add card processing.
- **M5e** `DemoDataSeeder.kt` hardcodes 20 real-looking student names + Bangladeshi phone numbers; `AppDatabase.kt:1005` hardcodes a real-looking owner name. Fix: replace with obviously fake data.

### M6. Release keystore credentials in plaintext on disk
`keystore.properties`, `keystore-credentials.txt`, `app/batchfee-release.jks`
- Passwords (`batchfee123`) sit unencrypted next to the keystore (gitignored, so not committed — but on-disk risk). Fix (your action, not mine): rotate the keystore password and keep credentials only in CI env.

---

## MINOR

- `SuperAdminScreen.kt:1279` — `currentUser!!.reauthenticate()` can NPE if the session dies between the email read and re-auth. Fix: capture `currentUser` once + null-check.
- `PaymentRequestReviewScreen.kt:370` — `result.payments.first()` throws if the server returns an empty list. Fix: `firstOrNull()?.id`.
- `SuperAdminScreen.kt:8044-8062` — `remember` inside a `forEach` (BI month labels). Safe (try/catch) but fragile; hoist labels out of the loop.
- Locale-sensitive currency: `"%,.0f".format(...)` uses device locale → wrong separators on some locales (`SuperAdminScreen.kt:7749,7785`, `SmartDueAutomationScreen.kt:702`). Fix: `String.format(Locale.US, …)`.
- `debug.keystore.base64` not gitignored (root `.gitignore`). Fix: add to `.gitignore`.
- `Demo_Visitors` collection has `allow read, write: if true` (`firestore.rules:1165-1167`) — the only unauthenticated write in the ruleset. Fix: restrict/remove.
- Source files under `com/example/…` declare package `com.batchfee.edu.*` — legal but confusing; optional cleanup, not release-blocking.

---

## Verified FINE (balanced report)

- targetSdk 36 / compileSdk 36.1 / minSdk 24, versionCode 12, versionName 1.8 — exceeds Play requirements.
- Release build: minify + shrink + ProGuard wired; signing refuses debug keys; no debuggable/cleartext.
- Manifest: only MainActivity exported; FCM/FileProvider not exported; no restricted permissions declared; CAMERA optional (`uses-feature required=false`) and covered by in-app policy; POST_NOTIFICATIONS declared + runtime-requested with education dialog.
- No Google Play Billing needed integration-wise; no ad/analytics SDKs beyond Firebase; no client-side SEND_SMS (server-side Zend via Functions only).
- App Check correctly split debug (Debug provider) vs release (Play Integrity).
- New BI/automation code is defensively written; `activitySignal.js` trigger and the 3 `lastCollectionAtMs` writes are transaction-safe; no new composite indexes required; `storage.rules` denies all direct access (callable-only media).
- Functions unit tests: 228/228 pass. Debug build compiles clean.

---

## Proposed fix plan (waiting for your go)

**Batch 1 — crash & correctness (code):**
C1 Room migration list + schema export · C2 `monthEndMs` · M3a-d BI fixes · re-auth NPE · `payments.firstOrNull()`.

**Batch 2 — security rules:**
M1 super-admin blocklist + create branch · M2 due-automation exclusions · `Demo_Visitors` restriction.

**Batch 3 — Play compliance (code):**
C3 gate demo seeding behind DEBUG · M5a remove coming-soon menu rows · M5e scrub demo data · M5c backup off · locale formatting · `.gitignore`.

**Batch 4 — verification:** `compileReleaseKotlin`, full functions test suite, Firestore rules emulator tests, then (only if you want) a signed release build.

**Your manual actions (no code):** M5b hosted privacy policy URL → Play Console · M5d B2B payment justification · M6 rotate keystore password.

Say "implement" and I'll execute batches 1–4 in order.
