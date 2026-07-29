# Problem 02 — Session expiry audit

## Scope

This audit covers only the Android main app under `app/`.  `student_app` and
`web_form` were not inspected or changed.

## Confirmed root causes

1. `domain/SessionManager.kt` represented authentication only as non-null
   in-memory user, institute, role, and permissions fields.  It had no
   explicit loading/authenticated/unauthenticated/session-expired state and
   was not connected to Firebase Authentication's auth-state or ID-token
   listeners.
2. `MainActivity.kt` enforced a five-minute local inactivity timeout, but did
   not validate Firebase Authentication on app resume and did not observe an
   externally invalidated Firebase user/token.  A Firebase user could therefore
   become invalid while the in-memory `SessionManager` still authorized
   protected navigation and Room-backed screens.
3. Manual logout only called `SessionManager.logout()` in `MainActivity.kt`,
   `ui/subscription/SubscriptionExpiredScreen.kt`, and
   `ui/superadmin/SuperAdminScreen.kt`; it did not sign out Firebase Auth.
   This left Firebase's persisted user available after a local logout.
4. Firestore sync helpers, including `data/firestore/CoreDataSyncCoordinator.kt`,
   `StudentSyncHelper.kt`, `BatchSyncHelper.kt`, `StaffSyncHelper.kt`, and
   `OperationalDataSyncHelpers.kt`, generally record and swallow exceptions.
   Their Room-backed screens consequently have no central session-invalid
   signal.  This does not itself delete data, but it allows stale or absent
   cached data to be rendered while the local session remains marked valid.

## Authentication lifecycle before the fix

* Password login uses `FirebaseAuth.signInWithEmailAndPassword`, then resolves
  the account/role from Firestore and Room, and finally calls
  `SessionManager.login` in `ui/auth/AuthScreen.kt`.
* Biometric login restores a Room/SharedPreferences identity and calls
  `SessionManager.login`; it does not revalidate Firebase Auth.
* Protected routes are ordinary Compose destinations in `MainActivity.kt`.
  They are removed only when the local inactivity timer calls
  `SessionManager.expireSession`.
* On resume, the app only refreshed the local inactivity timestamp.  It did
  not call Firebase's supported token/reload APIs.  On cold restart it also
  did not restore or validate Firebase's persisted current user.

## Failure path

When a Firebase user/token was revoked, disabled, deleted, or otherwise could
not be refreshed, `SessionManager` could still contain the prior user ID,
institute ID, role and permissions.  Protected composables therefore stayed in
the navigation back stack and read Room flows.  This explains apparent zero or
fallback account states instead of a login redirect.  Firebase's automatic
token refresh was neither observed nor classified by the app.

## Cache/default/demo findings

Room business data is not deliberately cleared on logout.  That is correct:
session expiry must not delete business data.  However, dashboard fallbacks in
`ui/dashboard/DashboardScreen.kt` include `BatchFee Demo Institute`, owner
defaults, `Unknown`, and `Date(0)` formatting.  These can be misleading if an
invalid session remains on a protected screen.

`BatchFeeApp.kt` calls `AppDatabase.ensureDemoDataSeeded` at application start.
This is a separate pre-existing demo-seeding design; it was not changed by this
session-expiry fix.  The session fix prevents a protected invalid session from
continuing to render those fallback labels.

## Error classification

* Confirmed Firebase Auth/token invalidation is a session-expired event.
* `FirebaseFirestoreException.Code.UNAUTHENTICATED` is a session-invalid event.
* `FirebaseFirestoreException.Code.PERMISSION_DENIED` is **not** a logout
  trigger.  It remains an authorization/role/rules failure.
* Network and server/unavailable failures are not logout triggers.

The project had no centralized Firestore exception classification before this
change.  The minimal fix adds one for the authoritative session monitor rather
than changing every feature's error behaviour.

## Related invalid subscription date

`DashboardScreen.kt` formats missing timestamps as `Date(0)`, which renders as
`01 Jan 1970`; `SubscriptionExpiredScreen.kt` has the same unsafe fallback.
`BillingScreen.kt` can similarly display a zero timestamp supplied by cached
or incomplete data.  These are corrected to `Not available`, without using
that fallback to mask an expired auth session.

## Proposed minimal fix

1. Give `SessionManager` explicit state and idempotent session-expiry handling
   while preserving its current role/permission API.
2. Add a small `FirebaseSessionMonitor` that uses Firebase Auth's auth-state
   listener, ID-token listener, and a resume-only `getIdToken(false)` plus
   `reload()` validation.  Firebase manages token refresh; no token is stored
   by the app and no forced refresh loop is added.
3. Wire the monitor into `MainActivity`, redirect once to `AuthRoute` with the
   protected back stack removed, and sign Firebase Auth out for manual and
   expired sessions.
4. Add pure unit tests for state transitions, simultaneous expiry, auth-vs-403
   classification, and safe subscription date output.

## Risks and unrelated issues

The app currently relies heavily on local Room cache and many feature-level
Firestore helpers intentionally swallow sync errors.  This change does not
redesign those features or convert all Firestore errors into logout.  A future
data-state/error-UI project should give each feature explicit loading, network,
and permission states.  That work is outside Problem 02.

## Implemented fix and validation

Implemented files:

* `domain/SessionManager.kt` — authoritative `SessionState`, idempotent
  expiry transition, and atomic clearing of in-memory user, institute, role,
  and staff permissions.
* `domain/FirebaseSessionMonitor.kt` — Firebase Auth-state and ID-token
  listeners plus one guarded resume validation using `getIdToken(false)` and
  `reload()`.  It signs Firebase out only for manual logout or a confirmed
  session-expiry transition.
* `domain/SessionErrorClassifier.kt` — maps `UNAUTHENTICATED` to session
  invalid and keeps `PERMISSION_DENIED` separate. Shared Firestore sync paths
  and billing listeners pass their errors to this classifier; only the former
  can trigger the central expiry state.
* `MainActivity.kt` — starts/stops the monitor with composition, restores a
  persisted Firebase user only after account metadata is safely resolved,
  validates on resume, removes protected destinations from the back stack on
  any terminal non-authenticated state, and routes all visible logout actions
  through the monitor.
* `DashboardScreen.kt`, `BillingScreen.kt`, and
  `SubscriptionExpiredScreen.kt` — use
  `SubscriptionDateFormatter.format(...)=Not available` for null/zero
  timestamps instead of formatting epoch zero.
* `SessionManagerTest.kt` — six focused JVM tests for authenticated login,
  safe restore/loading state, idempotent concurrent expiry, manual logout,
  401/unauthenticated versus 403 classification, clearing account state, and
  zero/missing subscription dates.

Validation on 2026-07-26 used Temurin JDK 17.0.19.  The focused
`SessionManagerTest` passed all 6 tests and `assembleDebug` succeeded.  The
complete `testDebugUnitTest` task had the same pre-existing 8 Firestore/gRPC
failures in `FeeCollectionRepositoryTest`; the other 10 tests, including all
Problem 01 and Problem 02 tests, passed. `lintDebug` completed and reported
four pre-existing errors: one suspicious indentation in `AuthScreen.kt` and
three `StateFlow.value`-in-composition errors in unchanged Dashboard code.
None are in the Problem 02 diff, so they were intentionally not changed.

No Room or Firestore business data was deleted, no data migration was executed,
and no persistent app settings were cleared.
