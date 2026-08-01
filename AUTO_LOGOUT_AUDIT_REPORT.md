# Auto-Logout Audit Report (Admin Android App Only)

**Date:** 2026-08-01
**Scope:** 5-minute inactivity auto-logout mechanism in the Android admin app
**Files:** `SessionManager.kt`, `MainActivity.kt`

---

## How It Works (The Flow)

There are **3 triggers** that can cause auto-logout:

### Trigger 1 — Activity Touch Handler
```kotlin
// MainActivity.kt line 46-48
override fun onUserInteraction() {
    SessionManager.markActivity()   // resets _lastActivityAtMs
}
```
Every tap/scroll/keyboard input resets the inactivity timer.

### Trigger 2 — Compose Timer (Primary)
```kotlin
// MainActivity.kt line 168-176
LaunchedEffect(isLoggedIn, lastActivityAtMs) {
    val activeUserId = isLoggedIn ?: return@LaunchedEffect
    val remainingMs = (300_000L - elapsedMs).coerceAtLeast(0)
    delay(remainingMs)
    if (userId still matches && isSessionInactive()) {
        expireSession()
    }
}
```
Counts down from last activity. When 5 minutes pass with zero interaction → logs out. Both keys (`isLoggedIn`, `lastActivityAtMs`) in the LaunchedEffect mean the timer **automatically resets on any activity** since `lastActivityAtMs` changes → old timer cancelled → new timer starts.

### Trigger 3 — ON_RESUME Lifecycle Check
```kotlin
// MainActivity.kt line 102-113
LifecycleEventObserver { _, event ->
    if (event == ON_RESUME && isLoggedIn()) {
        if (isSessionInactive()) expireSession()
        else markActivity()
    }
}
```
When app returns from background, checks if timeout already elapsed. Prevents user from bypassing timeout by backgrounding the app.

### Trigger 4 — Firebase Auth State
```kotlin
// MainActivity.kt line 118-127
AuthStateListener { firebaseAuth ->
    if (isLoggedIn() && firebaseAuth.currentUser == null) {
        expireSession()   // Firebase revoked token independently
    }
}
```
Safety net: if Firebase invalidates the token (account disabled, password changed remotely), user is logged out immediately.

---

## What Works Correctly ✅

- **Timer resets on any touch** — `onUserInteraction()` catches all Activity-level touches
- **Timers don't double-fire** — `logout()` sets `_currentUserId = null` immediately, and every subsequent check either guards on `isLoggedIn()` or `currentUserId.value == activeUserId`, preventing the second call
- **Background bypass blocked** — ON_RESUME catches timeout on return
- **LaunchedEffect key-based restart** — both `isLoggedIn` and `lastActivityAtMs` as keys mean the timer correctly restarts on login, logout, and activity
- **Firebase sign-out** — `expireSession()` properly calls `FirebaseAuth.signOut()`
- **Login screen shows notice** — `_sessionNotice` is displayed as a styled red/pink banner on the auth screen after expiry

**Verdict: The 5-minute auto-logout mechanism is functionally correct. It does what it's supposed to do.**

---

## Issues Found

### 🟠 Major — Subscription Expiry Shows Wrong Message
**File:** `MainActivity.kt` line 161

When `checkSubscriptionExpired()` returns true, the code calls `expireSession()`. This sets `_sessionNotice` to **"Your session has expired. Please log in again."** — but the real reason is **subscription expiry**, not session timeout. User sees a misleading message.

**Fix:** Either:
- Create a separate `expireSubscription()` with its own message like "Your subscription has expired."
- Or pass a custom message to `logout()` instead of using the boolean flag

---

### 🟡 Minor — Hardcoded 5-Minute Timeout
**File:** `SessionManager.kt` line 10

```kotlin
const val SESSION_TIMEOUT_MS = 300_000L
```

Not configurable per institute or role. Fine as a default, but if any institute needs a longer admin session, there's no way to adjust it.

---

### 🟡 Minor — No Warning Before Auto-Logout
**File:** `SessionManager.kt`, `MainActivity.kt`

User is kicked instantly. No "Your session will expire in 60 seconds. Tap to stay logged in." dialog. If a user is mid-form (adding a student, entering payment), all unsaved data is lost. You mentioned this isn't a priority — just noting it.

---

### 🔵 Info — ON_RESUME + Timer Interaction (Not a Bug)
The ON_RESUME check and the Compose timer can both trigger on background→foreground transitions, but they **do not race**:
- ON_RESUME fires first → `expireSession()` → `_currentUserId = null`
- Timer's LaunchedEffect restarts with `isLoggedIn = null` → returns immediately
- Firebase listener: `isLoggedIn()` returns false → no-op

All triggers are properly guarded. No actual race condition.

---

## Summary

| # | Severity | What |
|---|----------|------|
| 1 | 🟠 Major | Subscription expiry → shows "session expired" instead of "subscription expired" |
| 2 | 🟡 Minor | Hardcoded 5-min timeout, not configurable |
| 3 | 🟡 Minor | No pre-logout warning (data loss risk) |

**Bottom line: Auto-logout kaj thikthak e kortese. 5 min inactivity hole logout hoy, activity te timer reset hoy, background theke firle check hoy. Kono race condition nai. Ekta misleading message ache — subscription expire korleo "session expired" message show kore.**
