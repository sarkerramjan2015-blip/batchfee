# 🔐 BatchFee Authentication Audit Report

**Date:** June 8, 2026  
**Issue:** Existing users (Owner, Admin, SuperAdmin) can't log in, but newly registered users can.

---

## 🎯 Root Cause Summary

**Three interconnected bugs** are causing old users to be locked out:

1. **UID Mismatch in Seeder** – Hardcoded UIDs don't match Firebase Auth real UIDs
2. **Missing Firestore Documents** – No `institutes/{uid}` doc exists for old users → login fails
3. **isSuperAdmin() rule broken** – Firestore security rule resolves against non-existent documents

---

## 🔍 Bug #1: UID Mismatch Between Seeder & Firebase Auth

### Where
`AppDatabase.kt` → `populateSuperAdmin()` (line ~170)

### What Happens
```
Seeder creates:                        Firebase Auth generates:
──────────────────────────           ────────────────────────────
User(id="sys_super_admin_1")        admin@batchfee.app  → UID: Az88wbUVbRcn813HnTYi3MulI8f2
User(id="demo_owner_1")             owner@batchfee.app  → UID: zHKmLQIfa1g8gix54x9Mg3nWFNG2
Institute(id="demo_institute_1")    ictabr@gmail.com    → UID: IfeYGRA79xgAKoCjzkgtIqenbR92
```

The seeder hardcodes IDs that **never match** the real Firebase Auth UIDs. When a user logs in with Firebase Auth, their `authResult.user.uid` (e.g., `Az88wbUVbRcn813HnTYi3MulI8f2`) is used to query both Room DB and Firestore — and **nothing exists at that UID in either database**.

### The Seeder Guard is Also Broken
```kotlin
// AppDatabase.ensureDemoDataSeeded():
val user = db.userDao().getUserByEmail("admin@batchfee.app")
if (user == null) {
    populateInitialPlans(...)
    populateSuperAdmin(...)
    populateDemoData(...)
}
```
On first login with the REAL UID (`Az88wbUVbRcn813HnTYi3MulI8f2`), the Room lookup by email returns `null` (seeder hasn't run), so seeder runs and creates `sys_super_admin_1` — but the logged-in UID is `Az88wbUVbRcn813HnTYi3MulI8f2`. **The seeder data is completely orphaned from the real auth user.**

---

## 🔍 Bug #2: Login ROUTING Fails When Firestore Doc Missing

### Where
`AuthScreen.kt` → `AuthViewModel.login()` (line ~250-260)

### What Happens (Flow Trace)
```
1. Firebase Auth login SUCCESS → UID: Az88wbUVbRcn813HnTYi3MulI8f2 ✅
2. Check Firestore: institutes/Az88wbUVbRcn813HnTYi3MulI8f2
   → DOES NOT EXIST ❌
3. var localUser = db.userDao().getUserById(uid)
   → Also null (seeder created "sys_super_admin_1", not this UID) ❌
4. Falls into STAFF lookup branch
   → Tries staff subcollections
   → Tries local staff by email
   → ALL FAIL ❌
5. Shows: "Staff account not found. Contact your admin." 👈 WRONG ERROR
```

### Code (AuthScreen.kt, line ~250):
```kotlin
if (firestoreUserDoc.exists()) {
    // ← OLD USERS NEVER ENTER THIS BRANCH
    role = "SuperAdmin" or "InstituteOwner"
    instituteId = uid
} else {
    // ← OLD USERS FALL HERE → staff lookup → FAILS → locked out
    var foundStaff: StaffSyncHelper.StaffFirestoreData? = null
    // ...
    if (foundStaff == null || foundInstId == null) {
        onError("Staff account not found. Contact your admin.")
        return@launch
    }
}
```

### Why New Users Work
`registerInstitute()` writes to Firestore **before** local Room:
```kotlin
firestore.collection("institutes").document(uid).set(
    mapOf("role" to "owner", ...)
).await()
```
So newly registered users ALWAYS have the Firestore document → login succeeds.

---

## 🔍 Bug #3: isSuperAdmin() Security Rule is Completely Broken

### Where
`firestore.rules` (line 8)

### Rule
```javascript
function isSuperAdmin() {
    return request.auth != null
        && get(/databases/$(database)/documents/institutes/$(request.auth.uid)).data.role == 'SuperAdmin';
}
```

### Why It Fails
When `admin@batchfee.app` (UID: `Az88wbUVbRcn813HnTYi3MulI8f2`) logs in:
- `request.auth.uid` = `Az88wbUVbRcn813HnTYi3MulI8f2`
- Rule tries to read `institutes/Az88wbUVbRcn813HnTYi3MulI8f2`
- **That document doesn't exist** → `get()` returns nothing → `.data` is null → crash or false
- But there IS a document at `institutes/sys_super_admin_1` with `role: "SuperAdmin"` — it's just at a **different ID**

Net effect: **NO ONE can ever pass `isSuperAdmin()`**. The seeder wrote the SuperAdmin doc at a fake ID that doesn't match any real auth UID.

---

## 📋 Dead Code: `tryFirebaseLogin()`

`AuthViewModel.tryFirebaseLogin()` (line ~68) exists but is **never called** anywhere. The actual login method (`login()`) re-implements the same logic inline. This is dead code clutter.

---

## 🔧 Recommended Fixes

### Fix 1: Write Firestore Docs for Existing Users (IMMEDIATE)
Manually create Firestore documents for each existing user at the correct UID:

| Email | UID | Firestore Path | Required Fields |
|-------|-----|---------------|-----------------|
| `admin@batchfee.app` | `Az88wbUVbRcn813HnTYi3MulI8f2` | `institutes/Az88wbUVbRcn813HnTYi3MulI8f2` | `role: "SuperAdmin"`, `instituteName: "BatchFee System"` |
| `owner@batchfee.app` | `zHKmLQIfa1g8gix54x9Mg3nWFNG2` | `institutes/zHKmLQIfa1g8gix54x9Mg3nWFNG2` | `role: "owner"`, `instituteName`, `ownerName`, etc. |
| `ictabr@gmail.com` | `IfeYGRA79xgAKoCjzkgtIqenbR92` | `institutes/IfeYGRA79xgAKoCjzkgtIqenbR92` | `role: "owner"`, full institute data |
| `sahdbban@gmail.com` | `f2bceaXUn6VHenWZgA04mVZxache` | `institutes/f2bceaXUn6VHenWZgA04mVZxache` | `role: "owner"`, full institute data |

### Fix 2: Add Room DB Fallback in `login()`
When `firestoreUserDoc` doesn't exist, check local Room DB BEFORE falling into the staff path:
```kotlin
if (firestoreUserDoc.exists()) {
    // existing logic
} else if (localUser != null) {
    // FALLBACK: user exists in Room, use Room data
    role = localUser.role
    instituteId = localUser.instituteId
} else {
    // staff lookup fallback
}
```

### Fix 3: Fix `ensureFirebaseAuthAccounts()` to Write Firestore Docs
Currently it only creates Auth accounts. It should also write the Firestore `institutes/{uid}` document:
```kotlin
private suspend fun ensureFirebaseAuthAccounts() {
    val adminUid = try { FirebaseAuthApi.createUser("admin@batchfee.app", "123456") } catch { null }
    if (adminUid != null) {
        // Write Firestore doc at institutes/{adminUid}
        FirebaseFirestore.getInstance()
            .collection("institutes").document(adminUid)
            .set(mapOf("role" to "SuperAdmin", "email" to "admin@batchfee.app", "isActive" to true))
            .await()
    }
    // Same for owner...
}
```

### Fix 4: Rewrite `isSuperAdmin()` to Use a Config Collection
Instead of looking up by UID, use a dedicated super admin list:
```javascript
function isSuperAdmin() {
    return request.auth != null
        && exists(/databases/$(database)/documents/superAdmins/$(request.auth.uid));
}
```
Then create a document at `superAdmins/Az88wbUVbRcn813HnTYi3MulI8f2` with any data.

### Fix 5: Add Auto-Repair Migration
On app startup (or on first successful login of a real user), scan for users in Room DB that lack corresponding Firestore docs and backfill them.

---

## 📊 Impact Summary

| Severity | Issue | Affected Users |
|----------|-------|---------------|
| 🔴 CRITICAL | Existing owners/admins can't log in | ALL pre-existing users |
| 🔴 CRITICAL | `isSuperAdmin()` never returns true | SuperAdmin functionality completely broken |
| 🟡 MEDIUM | Seeded demo data uses fake UIDs | Demo institute data orphaned and unused |
| 🟢 LOW | `tryFirebaseLogin()` dead code | None (code debt only) |
