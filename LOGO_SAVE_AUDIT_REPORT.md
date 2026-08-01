# Institute Logo/Image Audit Report

**Date:** 2026-08-01  
**Scope:** Institute logo/profile photo — save, persist, and display flow  
**Files:** `DashboardScreen.kt`, `InstituteSyncHelper.kt`, `FirestoreManager.kt`, `FeeScreens.kt`

---

## How It Works Currently

1. **Save:** User selects image → `persistInstituteProfilePhoto()` copies to `filesDir/institute_profile/institute_profile_<timestamp>.jpg` → returns local `file:///data/data/...` URI
2. **Store:** Local URI saved in Room `InstituteEntity.profilePhotoUri` + synced to Firestore `institutes/{id}.profilePhotoUri`
3. **Load on dashboard:** Firestore → Room sync via `forceRefresh()` → `syncInstituteFromFirestore()` — **overwrites** Room with Firestore data including `profilePhotoUri`
4. **Display:** Coil `AsyncImage` loads from the local `file://` URI

---

## 🔴 CRITICAL: Firestore Sync Overwrites After Failed Save

**Files:** `DashboardScreen.kt:1777-1783`, `InstituteSyncHelper.kt:79-82`, `CoreDataSyncCoordinator.kt:20`

The save is a **two-phase, non-atomic** operation:

```kotlin
// Step 1: Save to Room ✅
db.instituteDao().updateInstitute(updated)

// Step 2: Sync to Firestore (CAN FAIL SILENTLY)
withContext(Dispatchers.IO) {
    InstituteSyncHelper.syncInstituteToFirestore(updated)  // exception swallowed
}
```

On every dashboard load:
```kotlin
InstituteCacheRefreshManager.forceRefresh(db, instId)
  → syncInstituteFromFirestore()
    → profilePhotoUri = data["profilePhotoUri"] as? String  // OVERWRITES Room
```

**Bug scenario:**
1. User saves logo → Room gets `file:///.../logo_123.jpg` ✅
2. `syncInstituteToFirestore()` fails silently (network, rate limit, etc.) ❌
3. Firestore still has old data (no `profilePhotoUri` or old value)
4. User opens app again → `forceRefresh` → Firestore returns old data → **Room overwritten**, `profilePhotoUri` = null/old → **logo disappears**

**This is the most likely cause of the reported bug.**

---

## 🔴 CRITICAL: Local File URI Stored in Firestore — No Cloud Upload

**File:** `InstituteSyncHelper.kt:94`

```kotlin
"profilePhotoUri" to institute.profilePhotoUri,  // "file:///data/data/com.batchfee.edu/..."
```

Images are **never uploaded to Firebase Storage**. The Firestore document stores a local Android file path. Consequences:
- App reinstalled → URI dead (file gone)
- User logs in from another device → URI pointing to non-existent file
- System clears `filesDir` (rare but possible) → URI dead

**Zero Firebase Storage usage anywhere in the codebase.** No `StorageReference`, `putFile`, `getDownloadUrl`.

---

## 🟠 MEDIUM: No Transaction / Retry on Save

**File:** `DashboardScreen.kt:1777-1783`

Room updated first, Firestore sync second. If Firestore fails:
- Logo exists locally but not in cloud
- No retry mechanism
- No user-facing error (exception swallowed with `Crashlytics.recordException`)
- Next app launch → `forceRefresh` → data lost

---

## 🟠 MEDIUM: Silent Display Failure

**Files:** `DashboardScreen.kt:2117-2122`, `FeeScreens.kt:301-308`

When the file is missing:
- Coil `AsyncImage` silently shows nothing (no error placeholder)
- Fee receipt `loadBitmapFromUri()` returns `null` silently

User sees a blank space with no indication that the image failed.

---

## 🟡 LOW: Old Logo Files Never Cleaned

**File:** `DashboardScreen.kt:1817`

```kotlin
val targetFile = File(directory, "institute_profile_${System.currentTimeMillis()}.jpg")
```

Every logo change creates a new timestamped file. Old files accumulate forever.

---

## 🟡 LOW: Camera Temp File in Cache Dir

**File:** `DashboardScreen.kt:730`

```kotlin
val tempPhotoFile = File(context.cacheDir, "profile_photo_${UUID.randomUUID()}.jpg")
```

`cacheDir` can be cleared by Android system when low on storage. Before `persistInstituteProfilePhoto` copies it to `filesDir`, if the cache is cleared, the photo is lost.

---

## Summary

| # | Severity | Issue |
|---|----------|-------|
| 1 | 🔴 Critical | Firestore sync overwrites Room after a failed save — logo disappears on next app launch |
| 2 | 🔴 Critical | Local `file://` URI in Firestore — no cloud storage, kills cross-device and reinstall |
| 3 | 🟠 Medium | Two-phase save with no transaction/retry |
| 4 | 🟠 Medium | Silent display failure when file missing |
| 5 | 🟡 Low | Old logo files accumulate in `filesDir` |
| 6 | 🟡 Low | Camera temp file in `cacheDir` |

## Recommended Fix

**Option A (proper):** Upload to Firebase Storage → store `downloadUrl` in Firestore/Room. Load from URL via Coil.

**Option B (quick):** Remove `profilePhotoUri` from Firestore sync entirely. Keep it local-only in Room. Don't let Firestore overwrite it. Add a `profilePhotoUpdatedAt` field to detect newer local data vs stale Firestore data.
