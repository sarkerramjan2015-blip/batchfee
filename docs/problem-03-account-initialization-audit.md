# Problem 03 — Account initialization audit

## Scope

This audit covers only the main Android application. `student_app` and
`web_form` are not part of the investigation or implementation.

## Confirmed root cause

The dashboard was allowed to render as soon as `SessionManager.login(...)`
published `SessionState.Authenticated`. That state confirms Firebase/account
mapping details held in memory; it does **not** confirm that the matching
Room `UserEntity` and `InstituteEntity` have been restored or refreshed.

`AuthViewModel.login` starts `CoreDataSyncCoordinator.refreshInstituteCache`
in a separate coroutine immediately after `SessionManager.login`, then calls
its navigation success callback. `MainActivity` therefore navigates to
`DashboardRoute` while that refresh may still be running. The same separation
exists on a process restart: `FirebaseSessionMonitor.restoreLocalSession`
restores only the local session mapping, then the dashboard starts its own
cache refresh.

At first composition, `DashboardViewModel` exposes `null` for the institute
and current user, and `0`/empty default values for dashboard data. The UI
rendered those unresolved values with generic display fallbacks. This is the
observed identity/count flash; it is not a Firebase session-expiry outcome.

## Exact default identity sources

`app/src/main/java/com/example/ui/dashboard/DashboardScreen.kt` contains the
unresolved-data display fallbacks:

- `institute?.name ?: "BatchFee Institute"` in `DashboardHeader`.
- `ownerName ... ?: "Institute Owner"` in `DashboardHeader`.
- `institute?.name ?: "BatchFee Demo Institute"` in the profile sheet.
- `currentPlan?.name ?: ... "Active Plan"` in the profile sheet.
- `currentPlan?.name ?: "Active plan"` passed to the header plan pill.

The initial values are also in that file: `_institute` and `_currentUser` are
`null`; student, batch, staff, trial, and subscription values begin at zero.

## Current bootstrap sequence

1. Firebase sign-in succeeds in `AuthViewModel.login`
   (`ui/auth/AuthScreen.kt`). It obtains/rebuilds a local user/institute where
   possible.
2. `SessionManager.login` records Firebase UID, institute ID, role, and staff
   permissions as authenticated.
3. The login success callback navigates from `MainActivity` to the dashboard.
4. A background `CoreDataSyncCoordinator.refreshInstituteCache` and the
   dashboard's `InstituteCacheRefreshManager.refreshIfStale` refresh Room.
5. Independent Room flows update the dashboard identity, subscription plan,
   and data/count flows at different times.

On cold start, `FirebaseSessionMonitor.restoreSession` validates Firebase then
restores a Room `UserEntity` that matches the Firebase UID. It does not itself
wait for the `InstituteEntity`; the dashboard refreshes that entity later.

## Cache and account switching

The Room DAOs used by the dashboard query an explicit `instituteId`, and the
current user query uses the authenticated Firebase UID. This prevents a normal
User A row from being selected for User B. The previous UI nevertheless
rendered generic identity while User B's scoped rows were unresolved. No
business tables should be cleared to solve this.

The implementation will additionally require that the Room user ID and
institute ID match the active `SessionManager` IDs before dashboard content is
considered ready. A mismatched or missing mapping cannot be rendered as a
generic or demo account.

## Demo-data findings

`BatchFeeApp` calls `AppDatabase.ensureDemoDataSeeded` during application
startup. `AppDatabase.seedDemoForRealUid` is called only for the explicit demo
owner emails (`demo@batchfee.app` or `owner@batchfee.app`) in `AuthScreen`.
The dashboard queries are scoped by the active institute ID. The physical
device's temporary generic labels are therefore from the UI fallbacks above,
not from `DemoDataSeeder` assigning a real authenticated user to the demo
institute. Legitimate demo support is left unchanged.

## Error and session behavior

`CoreDataSyncCoordinator.refreshInstituteCache` catches remote failures and
passes confirmed unauthenticated failures to Problem 02's
`SessionManager.handleRemoteFailure`; it does not report a result to the
dashboard. Network failures must not log the user out. The account gate will
show an honest retry state after a completed bootstrap attempt still lacks the
required scoped account identity; it will not display zero/default dashboard
content or demo identity.

Problem 02 remains authoritative for session expiry. Its authenticated versus
permission-denied classification and root-level expiry snackbar are not
changed by this work.

## Minimal implementation plan

1. Add a small, testable account-initialization resolver with `Initializing`,
   `Ready`, and `Error` states.
2. In `DashboardViewModel`, mark content ready only when the active Firebase
   UID, local user, institute ID, institute entity, and role mapping agree.
   A same-account Room cache may satisfy this immediately; otherwise a scoped
   cache refresh resolves it. Missing identity after that attempt becomes a
   retryable error rather than a generic identity.
3. Gate only the dashboard content with the existing visual style while that
   state is unresolved. Keep the existing dashboard layout once ready.
4. Replace the remaining identity/plan loading fallbacks in this screen with
   truthful neutral text, never demo/default identity.
5. Add focused resolver tests covering pending, ready, account mismatch,
   missing mapping, same-account cache, and session-expiry integration.

## Risks and intentionally deferred work

- Data/count flows are still independent after account identity becomes ready.
  Broad "loaded empty versus still loading" handling for every list/count is
  Problem 04 and is intentionally not redesigned here.
- Existing sync coordination intentionally swallows remote errors after
  forwarding confirmed auth invalidation to Problem 02. This change does not
  alter that session behavior.
- No Room or Firestore business data migration, deletion, or demo-data change
  is required.

## Implemented result

The main-app implementation adds these focused files/changes:

- `app/src/main/java/com/example/domain/DashboardAccountInitialization.kt`
  provides the small deterministic `Initializing`, `Ready`, and `Error`
  resolver.
- `ui/dashboard/DashboardScreen.kt` uses the resolver from
  `DashboardViewModel` and gates the dashboard tabs/content until the active
  session UID, local user, local institute, institute ID, and role agree.
  It refreshes only the active institute cache and provides a retry action
  when that completed attempt still cannot supply a scoped identity.
- The same screen no longer uses demo/default account names or `Active plan`
  as loading values. A missing final plan is shown as `Plan unavailable`.
- `app/src/test/java/com/example/DashboardAccountInitializationTest.kt` covers
  pending, ready, same-account cache restore, previous-account mismatch,
  missing mapping, retryable error, and session-expiry non-readiness.

No `SessionManager`, `FirebaseSessionMonitor`, `MainActivity`, Room schema,
Firestore business data, demo seeding behavior, `student_app`, or `web_form`
code was changed for Problem 03.

## Focused validation

Using the existing Temurin JDK 17 installation, the focused command below
passed with seven tests and zero failures:

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.batchfee.edu.DashboardAccountInitializationTest' --offline --no-daemon
```

The task compiled the debug production source as part of the test build.
`git diff --check` passed for the four Problem 03 files. No APK, full test
suite, lint, release build, or database migration was run for this checkpoint.
