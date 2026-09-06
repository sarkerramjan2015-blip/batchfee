# Audit 01 — Core Setup (Login / Institute Creation / Logo)

Scope: login flow, institute creation/registration flow, institute logo setup/upload/rendering.
Verdict per finding: `FIXED`, `NO ISSUE`, or `NOTE` (no functional break).

---

## 1. Registration partial-failure left orphaned Firebase Auth + Firestore records — FIXED

- **Location:** [`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:274) — `AuthViewModel.registerInstitute` generic `catch (e: Exception)`.
- **Bug:** `registerInstitute` writes to Firebase Auth (`createUserWithEmailAndPassword`), Firestore (`institutes/{uid}`, trial receipt) and then Room. The `FirebaseFirestoreException` branch cleaned up the auth user, but the generic `Exception` branch (which fires for Room insert failures, subscription-plan seeding failures, etc.) did **not** clean up. Result: a half-created account — the email is taken in Firebase Auth and an institute document exists in Firestore, but the device has no local record. Retrying shows "Email already exists" with no institute to log into.
- **Fix:** Track the created `uid` (`var createdUid: String? = null` at line 135, assigned at line 151) and, in the generic catch, best-effort delete the Firebase Auth user and the `institutes/{uid}` Firestore document (lines 274–288).

## 2. Registration inputs were not trimmed (login/registration mismatch) — FIXED

- **Location:** [`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:1386) — `viewModel.registerInstitute(...)` call site.
- **Bug:** The login path trims credential and password before use ([`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:293) `input = credential.trim()`, `cleanPassword = passwordHash.trim()`), but registration passed raw field values. A trailing space inserted by keyboard autocomplete (common on the email/password fields) would create a password/email that the user can no longer log in with, or trip Firebase's `ERROR_INVALID_EMAIL`.
- **Fix:** Pass `instituteName.trim()`, `ownerName.trim()`, `email.trim()`, `password.trim()` to `registerInstitute` (lines 1387–1390).

## 3. Registration did not persist the last login id — FIXED

- **Location:** [`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:1394) — registration `onSuccess`.
- **Bug:** Login `onSuccess` calls `SessionManager.saveLastLoginId(...)` and refreshes biometric session; registration `onSuccess` only refreshed the biometric session. After register → logout, the login field was empty on the next launch.
- **Fix:** Added `SessionManager.saveLastLoginId(context, email.trim())` in registration `onSuccess` (line 1394) and aligned `refreshCurrentSession` to use the trimmed email (line 1395).

## 4. Institute creation Firestore write matches security rules — NO ISSUE

- **Location:** [`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:157) vs [`firestore.rules`](firestore.rules:337) `validOwnerInstituteCreate`.
- **Verified:** The created `institutes/{uid}` document contains every key required by `keys().hasAll([...])` (`instituteName`, `ownerName`, `email`, `role`, `createdAt`, `isActive`, `trialEndDate`, `currentPeriodEndMs`, `currentPlanId`, `subscriptionStatus`, `studentLimit`, `staffLimit`, `studentCount`, `staffCount`, `batchCount`) and no key outside `keys().hasOnly([...])`.
- **Verified:** `staffLimit == 1`, `studentLimit = 0` (the server treats `0` as "unlimited" for a live free trial — [`subscriptionPolicy.js`](functions/src/subscriptionPolicy.js:8)), `trialEndDate == createdAt + 30d`, `currentPeriodEndMs == trialEndDate`, `createdAt` inside the allowed clock-skew window.
- **Verified:** The zero-value trial receipt matches [`firestore.rules`](firestore.rules:575) (`instituteCode == ''`, `instituteAddress == ''`, `ownerPhone == phone == whatsappNumber`, `planId == 'plan_free_trial'`, `amountPaid == 0`).

## 5. Logo upload/crop/save/rendering is internally consistent — NO ISSUE

- **Upload:** [`DashboardScreen.kt`](app/src/main/java/com/example/ui/dashboard/DashboardScreen.kt:2088) crops via [`SquarePhotoCropDialog`](app/src/main/java/com/batchfee/edu/ui/components/SquarePhotoCropDialog.kt:70) (local file → `cacheSelectedImage` → `uploadInstituteLogo`), then retries the Firestore profile write before reporting success ([`DashboardScreen.kt`](app/src/main/java/com/example/ui/dashboard/DashboardScreen.kt:2175)).
- **Rendering:** [`FirebaseStorageImageUploadHelper`](app/src/main/java/com/batchfee/edu/data/media/FirebaseStorageImageUploadHelper.kt:62) `displaySource`/`resolveForDirectRead` + [`StudentAdmissionFormPdf.loadBitmap`/`drawLogo`](app/src/main/java/com/example/ui/students/StudentAdmissionFormPdf.kt:261) handle `file:`, `content:`, and `https:` sources. Coil's [`SecureMediaInterceptor`](app/src/main/java/com/batchfee/edu/data/media/SecureMediaInterceptor.kt:11) is registered in [`BatchFeeApp.newImageLoader`](app/src/main/java/com/example/BatchFeeApp.kt:56).
- **Sync:** [`InstituteSyncHelper`](app/src/main/java/com/example/data/firestore/InstituteSyncHelper.kt:79) preserves local `file:` logo URIs and strips them when writing to Firestore; the institute logo reference returned by the upload callable is a public HTTPS URL, so it renders on any device.
- **Double-submit:** Profile save is guarded by `isSavingProfile` + disabled confirm button; upload button disables while saving.

## 6. "Cloudinary migration" premise does not match the codebase — NO ISSUE (client is consistent)

- **Verified:** There is no Cloudinary usage anywhere in the Android sources (`*.kt`), `functions/src`, or `web_form`. The `cloudinary` npm package is present in `functions/node_modules` but is **not** a declared dependency in [`functions/package.json`](functions/package.json:17) and is unused.
- **Actual path:** Media still travels through Firebase Storage via the Admin SDK inside the `uploadSecureMedia`/`getSecureMediaUrl` callables ([`mediaSecurity.js`](functions/src/mediaSecurity.js:166)). The Android client never used the Firebase Storage SDK directly — it uses callables through [`FirebaseStorageImageUploadHelper`](app/src/main/java/com/batchfee/edu/data/media/FirebaseStorageImageUploadHelper.kt:34). No dead reference to the old storage exists on the client, so uploads/rendering are not broken by any migration.

## 7. `instituteCode` is never generated for self-service institutes — NOTE (no functional break)

- **Location:** [`InstituteCodeGenerator.kt`](app/src/main/java/com/example/domain/InstituteCodeGenerator.kt:12) (`generateCode`) is dead code — never called anywhere in the app.
- **Assessment:** Self-service registration omits `instituteCode`. This is **allowed** by [`firestore.rules`](firestore.rules:348) (it is in `hasOnly` but not `hasAll`) and the trial receipt **requires** an empty code. Student login authenticates by `studentCode`, not `instituteCode`, so nothing breaks. Impact is cosmetic: receipts/ID cards/PDFs show a blank or "N/A" institute code, and no owner-facing UI exists to set it later. Left unchanged because generating/persisting a code would be a feature change, and the existing client-side generator's full-collection `institutes` read is not tenant-safe (permission-denied would silently fall back to a colliding default).

## 8. Cold start / process-death session restoration — NO ISSUE

- **Owner/staff:** [`SessionManager`](app/src/main/java/com/example/domain/SessionManager.kt:21) keeps session state in memory only. After process death the app returns to [`AuthScreen`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:913) with the last login id pre-filled (persisted via `saveLastLoginId`) and biometric login available when enabled. Navigation is derived directly from session state in [`MainActivity`](app/src/main/java/com/example/MainActivity.kt:111).
- **Student:** [`StudentSessionManager.restoreFromFirebase`](app/src/main/java/com/batchfee/edu/domain/StudentSessionManager.kt:146) restores the session from Firebase Auth custom claims and the live UID-linked student record, invoked from [`BatchFeeApp.onCreate`](app/src/main/java/com/example/BatchFeeApp.kt:41).
- **Verified:** The expiry notice survives process recreation through `SessionManager.initialize` ([`SessionManager.kt`](app/src/main/java/com/example/domain/SessionManager.kt:24)); no crash or stuck state found.

## Verification

- `.\gradlew.bat :app:compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL** (warnings only: deprecations).
