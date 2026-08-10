# Institute Logo Save Failure — Root Cause & Fix

**Date:** 2026-08-07
**Scope:** Institute logo/profile image upload via Cloudinary
**Issue:** Logo save korte parche na, save korleo save hoy na

---

## Root Cause: Line 105 in `InstituteSyncHelper.kt`

```kotlin
// Line 105 — THE BUG
"profilePhotoUri" to institute.profilePhotoUri?.takeUnless { it.startsWith("file:") },
```

**Explanation:**

Cloudinary upload successful hoy, Cloudinary URL return kore. DashboardScreen save flow e `updated` institute object ready hoy **Cloudinary URL diye**. Then `syncInstituteToFirestore(updated)` call kora hoy.

But line 105 e logic ta holo: `takeUnless { it.startsWith("file:") }` — eita **null value pash kore** if profilePhotoUri is not a `file:` URI. But that's exactly wrong for the Cloudinary HTTPS URL.

Actually wait — `takeUnless { it.startsWith("file:") }` will **keep** Cloudinary URLs (they don't start with `file:`) and **discard** local file URIs. So that part is correct.

Let me re-examine the full flow more carefully...

---

## ACTUAL Root Cause: `mapOf()` with null values

```kotlin
firestore.collection("institutes").document(institute.id).set(
    mapOf(
        "instituteName" to institute.name,
        "phone" to institute.phone,
        "address" to institute.address,
        "whatsappNumber" to institute.whatsappNumber,
        "profilePhotoUri" to institute.profilePhotoUri?.takeUnless { it.startsWith("file:") },
        "ownerName" to institute.ownerName,
        "email" to institute.email,
        // ... more fields
    ),
    SetOptions.merge()
)
```

The `SetOptions.merge()` — eita Firestore ke bole **only the fields in the map update koro, existing fields untouched rakho**. But `profilePhotoUri to null` hole ki hoy? `SetOptions.merge()` with a `null` value actually **removes** the field from Firestore. Kotlin's `mapOf` allows null values and passes them as `null` to Firestore.

**But wait** — `takeUnless { it.startsWith("file:") }` Cloudinary URL er jonno non-null return kore. So this isn't the direct cause...

### Let me trace the ACTUAL save flow:

```kotlin
// DashboardScreen.kt lines 1835-1845
val profilePhotoUri = when {
    editProfilePhotoUri == null -> {
        deleteLocalInstituteProfilePhoto(inst.profilePhotoUri)
        null                           // ← CASE 1: null
    }
    editProfilePhotoUri.toString() == inst.profilePhotoUri -> inst.profilePhotoUri
                                       // ← CASE 2: existing URL, unchanged
    else -> CloudinaryImageUploadHelper.uploadInstituteLogo(
                context, editProfilePhotoUri!!
            )                          // ← CASE 3: new upload
}
```

**Case 3 e jemon hoy:**
1. Cloudinary upload succeeds, returns `https://res.cloudinary.com/cbhhlz9q/image/upload/...`
2. `profilePhotoUri` = Cloudinary URL
3. `updated = inst.copy(profilePhotoUri = profilePhotoUri)`
4. `syncInstituteToFirestore(updated)` — line 105 sends `profilePhotoUri to "https://res.cloudinary.com/..."` ✅
5. `db.instituteDao().updateInstitute(updated)` — Room DB update ✅

This path works fine in theory.

### Where it could fail:

**Scenario: `syncInstituteToFirestore` throws an exception**

Line 118-121:
```kotlin
} catch (e: Exception) {
    FirebaseCrashlytics.getInstance().recordException(e)
    throw e   // ← Re-throws to the caller
}
```

DashboardScreen line 1856-1858:
```kotlin
withContext(Dispatchers.IO) {
    InstituteSyncHelper.syncInstituteToFirestore(updated)
}
```

If Firestore sync fails (network, permission, etc.), the exception propagates to line 1868:
```kotlin
} catch (e: Exception) {
    snackbarHostState.showSnackbar(e.message ?: "Failed to update institute information.")
}
```

The Room DB on line 1860 is **never reached** because the sync is NOT inside a try-catch — the `throw e` in `syncInstituteToFirestore` jumps directly to the catch block at 1868.

**THIS IS THE BUG:** Firestore sync fails → exception thrown → Room DB never updated → logo URL lost → "Failed to update" snackbar.

---

## SECONDARY ISSUE: `OkHttpClient()` with no timeout

`CloudinaryImageUploadHelper.kt` line 29:
```kotlin
private val httpClient = OkHttpClient()
```

No `.connectTimeout()` or `.readTimeout()` set. Default OkHttp timeouts are 10 seconds for connect, 10 seconds for read. But on slow connections (3G/4G in Bangladesh), uploading a 500KB image could take longer than 10 seconds and fail silently.

---

## THIRD ISSUE: Camera launcher fails silently

```kotlin
// DashboardScreen.kt line 1641
try { cameraLauncher.launch(tempPhotoUri) } catch (_: Exception) {}
```

Empty catch — user gets no feedback.

---

## FIX

### Fix 1: Swap Firestore sync order — local first, then sync

The sync should be best-effort AFTER local save:

```kotlin
// WRONG (current):
syncInstituteToFirestore(updated)  // if this fails, nothing below runs
db.instituteDao().updateInstitute(updated)

// RIGHT (fixed):
db.instituteDao().updateInstitute(updated)  // save locally first
try {
    InstituteSyncHelper.syncInstituteToFirestore(updated)  // sync after
} catch (_: Exception) {
    // Don't crash — local is already saved
}
```

### Fix 2: Add timeouts to OkHttpClient

```kotlin
private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
```

### Fix 3: Proper null handling in sync

Don't send null for `profilePhotoUri`:

```kotlin
"profilePhotoUri" to (institute.profilePhotoUri?.takeUnless { it.startsWith("file:") } ?: com.google.firebase.firestore.FieldValue.delete()),
```

This ensures old Cloudinary URLs are cleaned up when logo is removed.

---

## VERDICT

**Primary root cause:** `syncInstituteToFirestore()` throws → Room DB never updated → logo URL lost. Save appears to work (no crash) but the update reverts on next Room reload because Firestore sync line 55-91 restores old data from Firestore (which wasn't updated due to the exception). The snackbar shows "Failed to update institute information" but the user may miss it.

**Fix priority:** Swap the order — save locally FIRST, then sync to Firestore in background.
