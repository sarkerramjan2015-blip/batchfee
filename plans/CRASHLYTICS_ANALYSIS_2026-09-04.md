# Crashlytics Analysis — BatchFee v1.6.3

**Date range:** Aug 24 – Sep 3
**Scope:** Crashes 187 (+592.6%), users 39; non-fatals 3.4K (+378.2%), users 148.
**Status:** Analysis only — **no code changes made**. Awaiting approval before fixing.

---

## 1. Priority order (recommended fix sequence)

| # | Issue | Type | Volume | Priority |
|---|-------|------|--------|----------|
| 1 | Firestore `PERMISSION_DENIED` (crash + non-fatal) | crash / non-fatal | 151 crashes + 1.7K non-fatals | **P0** |
| 2 | Camera `SecurityException` (OPPO/ColorOS) | crash | 2 / 1 user | **P1** |
| 3 | Duplicate LazyColumn key (`IllegalArgumentException`) | crash | 4 / 1 user | **P1** |
| 4 | Cancellation noise (`LeftCompositionCancellationException`, `Job was cancelled`) | non-fatal | 152 + 36 + 13 | **P2** |
| 5 | `FirebaseAuthInvalidCredentialsException` | non-fatal | 48 / 15 users | **P2** |
| 6 | Network / offline `FirebaseNetworkException` / `Connection reset` | non-fatal | low | **P3** |
| 7 | `FirebaseFunctionsException` (Super Admin delete) | non-fatal | 2 / 1 | **P3** |
| 8 | `LegacyCursorAnchorInfoController` NPE | crash | 1 / 1 | **P3** (platform bug) |

---

## 2. Issue-by-issue analysis

### P0 — Firestore `PERMISSION_DENIED: Missing or insufficient permissions`

**Evidence**
- Non-fatal `io.grpc Status.asException · ka.s1 — PERMISSION_DENIED`: **1.7K events / 39 users**
- Crash `Status.asException · ka.s1 — PERMISSION_DENIED` (repetitive): **151 / 29 users**, versions 1.6.3 only
- Minor variants: `ba.v1` (1.3–1.4, 8/1) and `io.grpc.StatusException` (1/1)

**Root cause**
[`firestore.rules`](firestore.rules) gates nearly every institute read/write behind `hasActiveSubscription()` / `isActiveStaff()` / `isStudentOf()` ([`firestore.rules`](firestore.rules:93)). The moment a subscription lapses, or a staff/student record is deactivated/archived, every Firestore operation on that data fails closed with `PERMISSION_DENIED`. On the client this is both **expected** (access genuinely ended) and **noisy/fatal** depending on where it surfaces:

- Several sync helpers catch the error, record it, and **re-throw** it — e.g. [`OperationalDataSyncHelpers.kt`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:69), [`...:95`](app/src/main/java/com/example/data/firestore/OperationalDataSyncHelpers.kt:95). Re-thrown `FirebaseFirestoreException` can escape the coroutine boundary and crash in callers that do not wrap the sync call.
- Many `.get()/.set()/.update()/.delete().await()` calls have only partial protection (e.g. [`AuthScreen.kt`](app/src/main/java/com/example/ui/auth/AuthScreen.kt:183), [`MainActivity.kt`](app/src/main/java/com/example/MainActivity.kt:972), [`SubscriptionRepository.kt`](app/src/main/java/com/example/data/repository/SubscriptionRepository.kt:245)).
- The crash variant is new to 1.6.3, suggesting a recently added listener/write path that does not handle `PERMISSION_DENIED` at all.

**Proposed fix**
1. Make Firestore errors non-fatal at every boundary: catch `FirebaseFirestoreException` with code `PERMISSION_DENIED`, log a breadcrumb, and surface a graceful "access ended / subscription required" state instead of letting it propagate.
2. Gate writes on access state before firing — reuse the same `hasActiveSubscription` logic already used by [`InstituteRealtimeSyncManager.kt`](app/src/main/java/com/example/data/firestore/InstituteRealtimeSyncManager.kt:79) so expired institutes stop issuing writes.
3. Route all Firestore failures through [`FirebaseFailureReporter.report()`](app/src/main/java/com/example/data/firebase/FirebaseFailureReporter.kt:14) with `permissionDeniedIsExpected = true` for access-expiry paths, so they don't inflate issue counts.
4. Audit [`firestore.rules`](firestore.rules) for legitimate owner/staff operations that are wrongly denied (e.g. `isOwner()` fallback when `ownerUid` is missing, or field-name mismatches between client writes and rule expectations).

---

### P1 — Camera `SecurityException` on OPPO/ColorOS

**Evidence**
Crash in `AddEditStaffScreen.kt:194` → `SecurityException: Permission Denial: starting Intent { act=android.media.action.IMAGE_CAPTURE ... cmp=com.oplus.camera/.Camera ... clip={text/uri-list ...} }`.

**Root cause**
`CAMERA` is declared in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:7) but is **never requested at runtime**. [`AddEditStaffScreen.kt`](app/src/main/java/com/example/ui/staff/AddEditStaffScreen.kt:120) launches the camera directly via `ActivityResultContracts.TakePicture()`:
```kotlin
onCameraClick = { cameraLauncher.launch(tempPhotoUri) }
```
On ColorOS the stock camera (`com.oplus.camera`) requires the caller to hold the `CAMERA` permission at start time; without a runtime grant the system throws `SecurityException` when starting the activity. The `FileProvider` setup itself is correct ([`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:34), [`file_paths.xml`](app/src/main/res/xml/file_paths.xml:2) covers `cache-path`).

**Proposed fix**
1. Request `CAMERA` at runtime (e.g. `ActivityResultContracts.RequestPermission()`) before launching `TakePicture`; only launch once granted.
2. Wrap the launch in `try/catch (SecurityException)` and fall back to the gallery picker so the screen never crashes.

---

### P1 — Duplicate LazyColumn key (`IllegalArgumentException`)

**Evidence**
Crash `LayoutNodeSubcompositionsState.subcompose — Key "UAlloqHdKJOA5Z4hDO4N4y9oM9j1" was already used.` (4 / 1 user). The key is a 28-char **Firebase Auth UID**.

**Root cause**
A `LazyColumn` item is keyed by a value that is not unique across items. Strong candidate: [`SuperAdminScreen.kt`](app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt:3020) renders the "Logins" detail with:
```kotlin
items(activity.events, key = { it.id }) { event -> ... }
```
If `event.id` is the owner's UID (constant for one owner) and the feed contains multiple login events for the same user, the same key repeats → `IllegalArgumentException`. The managed-users list is built with `id = doc.id` ([`SuperAdminScreen.kt`](app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt:764)) and is likewise only safe if document IDs are unique.

**Proposed fix**
Use a guaranteed-unique key for list items (e.g. an event index or a composite key), or `distinctBy { it.id }` before rendering. Apply the same guard to any list keyed by `id` where duplicates are possible.

---

### P2 — Cancellation noise (`LeftCompositionCancellationException`, `Job was cancelled`)

**Evidence**
- `d1.r — The coroutine scope left the composition`: 152 / 31 users
- `ee.h1 — z1 was cancelled`: 36 / 8; `ee.h1 — Job was cancelled`: 13 / 4
- `androidx.compose.runtime.LeftCompositionCancellationException`: 1 / 1

**Root cause**
[`FirebaseFailureReporter.report()`](app/src/main/java/com/example/data/firebase/FirebaseFailureReporter.kt:19) already drops `CancellationException` (`LeftCompositionCancellationException` is a `CancellationException`). But many call sites record exceptions **directly** with `FirebaseCrashlytics.getInstance().recordException(e)` and bypass that filter — e.g. [`SuperAdminScreen.kt`](app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt:415) (and ~20 more in the same file), [`SubscriptionRepository.kt`](app/src/main/java/com/example/data/repository/SubscriptionRepository.kt:85), [`WorkSyncHelper.kt`](app/src/main/java/com/batchfee/edu/data/firestore/WorkSyncHelper.kt:23), [`DemoAuthRepository.kt`](app/src/main/java/com/example/domain/DemoAuthRepository.kt:32), and DB seeding in [`AppDatabase.kt`](app/src/main/java/com/example/data/database/AppDatabase.kt:699). When a coroutine is cancelled mid-flight (screen left), these record the cancellation as a non-fatal.

**Proposed fix**
1. Add a `if (error is CancellationException) return` guard to every direct `recordException` call, or route them all through `FirebaseFailureReporter`.
2. For Compose coroutines, prefer `LaunchedEffect` keyed correctly and avoid `rememberCoroutineScope` work that outlives the composition.

---

### P2 — `FirebaseAuthInvalidCredentialsException`

**Evidence**
Non-fatal 48 / 15 users — "The supplied auth credential is incorrect, malformed or has expired."

**Root cause**
Single re-auth site: [`SuperAdminScreen.kt`](app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt:921) calls `EmailAuthProvider.getCredential(email, superAdminPassword)` then `reauthenticate(...).await()`. A wrong/expired password (or a session whose credential is no longer valid) throws `FirebaseAuthInvalidCredentialsException`, which is then recorded.

**Proposed fix**
On invalid credential, surface a clear "re-enter your password" / re-login prompt and avoid repeated auto-retries that spam the same non-fatal. Optionally refresh the Firebase session before re-auth.

---

### P3 — Remaining low-volume items

- **`FirebaseNetworkException` / `FirebaseException — Connection reset`** (5/3, 2/2): expected transient network failures. Already treated as expected by [`FirebaseFailureReporter.isExpected()`](app/src/main/java/com/example/data/firebase/FirebaseFailureReporter.kt:29); ensure all paths use the reporter.
- **`FirebaseFirestoreException — Failed to get document because the client is offline`** (1/1): expected offline state; handle with cached Room data and do not record as an issue.
- **`FirebaseFunctionsException — Only an active Super Admin can permanently delete an institute`** (2/1): a server-side business-rule response, not a bug. Surface the message to the user; no crash involved.
- **`LegacyCursorAnchorInfoController.updateCursorAnchorInfo` NPE** (1/1): known Android/Compose framework text-input issue on certain devices. Low priority; mitigate via Compose/foundation version bump if it grows, otherwise accept.

---

## 3. Suggested immediate actions

1. **P0 first**: stop `PERMISSION_DENIED` from ever becoming an unhandled exception (boundary handling + access gating). This alone should recover most of the 151 repetitive crashes and 1.7K non-fatals.
2. **P1**: runtime `CAMERA` permission + fallback in [`AddEditStaffScreen.kt`](app/src/main/java/com/example/ui/staff/AddEditStaffScreen.kt:120); unique keys in [`SuperAdminScreen.kt`](app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt:3020).
3. **P2**: centralize exception recording through [`FirebaseFailureReporter`](app/src/main/java/com/example/data/firebase/FirebaseFailureReporter.kt) to eliminate cancellation/permission/network noise.

No code changes have been applied. Awaiting approval to proceed with the fixes.
