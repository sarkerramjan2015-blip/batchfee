# Problem 04 — False empty-state audit

## Scope

This audit and implementation are limited to the main Android app's Students
and Batches list screens. `student_app` and `web_form` are not in scope.

## Confirmed root cause

Both affected view models use `MutableStateFlow(emptyList())` as their only
initial list state:

- `ui/students/StudentViewModel.kt` — `_studentList` and `_batchList`.
- `ui/batches/BatchViewModel.kt` — `_batchList`.

Their load coroutines call `InstituteCacheRefreshManager.refreshIfStale(...)`
before starting the Room `Flow` collection. While the refresh is running, the
screen observes the initial empty list. Neither view model exposes a first-load
status nor a load-error status.

The screens then treat `emptyList()` as a completed result:

- `StudentListScreen.kt` renders `"0 Total Students found"`, `"0 students"`,
  and `"No students yet"` whenever `filteredStudents.isEmpty()`.
- `BatchListScreen.kt` renders `"No batches yet"` whenever `batches.isEmpty()`.

This is a local state-model issue; it is not a genuine empty Room query and is
not a Problem 02 session-expiry outcome.

## Cache and refresh findings

The current list loaders wait for refresh before they collect Room, so a valid
same-institute cache is not shown during that wait. `InstituteCacheRefreshManager`
also records a refresh timestamp before the remote work finishes; a concurrent
caller can skip that in-flight refresh and observe its own initial empty state.

`CoreDataSyncCoordinator` catches remote failures, forwards confirmed
unauthenticated failures to Problem 02, and otherwise only logs them. It does
not expose success/failure to these view models. Therefore a network/sync
failure can be rendered as an empty list after the current code resumes its
Room collection.

## Affected files/screens

Implementation scope:

- `app/src/main/java/com/example/ui/students/StudentViewModel.kt`
- `app/src/main/java/com/example/ui/students/StudentListScreen.kt`
- `app/src/main/java/com/example/ui/batches/BatchViewModel.kt`
- `app/src/main/java/com/example/ui/batches/BatchListScreen.kt`
- `app/src/main/java/com/example/data/firestore/CoreDataSyncCoordinator.kt`
- `app/src/main/java/com/example/data/firestore/InstituteCacheRefreshManager.kt`

The coordinator/manager change is limited to returning a refresh outcome and
sharing the same in-flight refresh by institute. Existing callers may ignore
the return value; Problem 02's existing unauthenticated classification remains
the authoritative session action.

## Similar screens intentionally not changed

`StaffViewModel` has an `isLoading` flow, but `StaffListScreen` currently does
not consume it before rendering `No staff found`. `BirthdayViewModel` and some
other list/detail flows also begin with empty collections. These are reported
for later review, but are not changed here to avoid an app-wide list UI rewrite.

## Minimal implementation plan

1. Add a small reusable `ListLoadState` reducer with `Loading`, `Data`,
   `Empty`, and `Error` states. `Loading` and `Error` retain any known cached
   rows so refresh never replaces visible rows with a false empty state.
2. Start scoped Room collection before refresh in Students and Batches. Do not
   interpret an empty pre-refresh Room emission as a completed empty result.
3. Obtain a post-refresh one-shot Room snapshot, then resolve the state as
   data, genuine empty, or retryable error.
4. Gate the existing count/header and empty-list UI using that same state, with
   a small existing-style progress/error/retry panel. No layout redesign.
5. Make `InstituteCacheRefreshManager` await one refresh per institute and
   return the coordinator result so concurrent Students/Batches loaders have a
   consistent bootstrap outcome.

## Risks

- This work distinguishes loading, successful empty, and a refresh failure for
  Students/Batches only. It does not broadly change all other screen lists.
- Cached rows remain visible during a failed refresh; this is preferable to
  replacing them with a false empty state. The retry action refreshes only the
  active institute cache and does not delete Room or Firestore business data.
- No authentication/session, account-bootstrap, fee, schema, or migration
  behavior is changed.

## Implemented result

- `domain/ListLoadState.kt` introduces the focused list state model. A loading
  or error state retains cached rows; only a completed successful empty result
  becomes `Empty`.
- `StudentViewModel` and `BatchViewModel` now start their scoped Room
  collections before the refresh, retain cached rows during that refresh, and
  resolve a one-shot post-refresh Room snapshot into data, genuine empty, or
  retryable error.
- `StudentListScreen` and `BatchListScreen` use the same state for headers,
  count/list content, initial progress, genuine empty UI, and retry UI. They
  therefore cannot show an initial zero count or empty-state message before
  the first bootstrap finishes.
- `CoreDataSyncCoordinator` returns its refresh outcome, while
  `InstituteCacheRefreshManager` shares a concurrent per-institute refresh and
  returns that outcome. Existing callers can continue ignoring it. Confirmed
  unauthenticated failures still pass through Problem 02's existing
  `SessionManager.handleRemoteFailure` path.

## Focused validation

Using the existing Temurin JDK 17 installation, this focused command passed:

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.batchfee.edu.ListLoadStateTest' --offline --no-daemon
```

The JUnit XML reports 7 tests, 0 failures, and 0 errors. The task compiled the
debug production source; Gradle reported `BUILD SUCCESSFUL`. `git diff --check`
passed for all Problem 04 files. No full suite, lint, release build, APK, Room
migration, or Firestore business-data operation was run.
