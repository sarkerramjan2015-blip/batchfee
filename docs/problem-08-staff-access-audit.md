# Problem 08 — Staff access audit

## Confirmed architecture

- `StaffEntity` already contains an account-scoped `photoUri`, role title, status, assigned batches and a CSV of granular permissions. `UserEntity` contains a one-way password hash; no recoverable password field exists.
- `StaffViewModel` creates the Firebase Authentication user with `FirebaseAuthApi.createUser` (REST), then stores the matching staff/user records locally and syncs the staff profile to `institutes/{instituteId}/staffs/{uid}`.
- `SessionManager` holds the effective role and staff permissions in memory. `AccessControl` maps protected routes to permissions, and the dashboard/bottom navigation already filters those routes. Repositories/Firestore continue to be the authority for data access.
- `FirebaseSessionMonitor` validates a persisted Firebase session at restore/resume. It restores a staff session only when the matching Room staff record is active and not archived. Firebase invalid-session handling remains distinct from permission and network failures.
- `AuditLogEntity` and `AuditLogSyncHelper` already provide institute-scoped append/sync storage. There is no normal DAO delete/update API, but there were no application writes for security or staff activity.

## Confirmed gaps and root causes

1. `photoUri` existed in the model and Firestore sync, but neither Add/Edit Staff nor staff list/profile provided photo selection/rendering; all staff were rendered as initials.
2. Route/menu visibility was permission-aware, but staff-management controls were only checked as admin at interaction time. There was no reusable presentation helper to make the effective-permission decision explicit.
3. Staff creation retained the temporary password only in the form, then immediately navigated away. It did not provide a one-time invite/share surface. The password hash is not reversible and must remain that way.
4. Existing "Staff Logs" are attendance/salary-oriented. No reliable app security/login events were recorded in `audit_logs`.
5. Archiving changed the staff document/status but cannot disable a Firebase Authentication account from this client-only app. Restore already rejects an inactive local staff record, but resume validation did not re-check the account's current staff status. Firebase rules protect staff documents for owners/superadmins and self-read, but their broad authenticated subcollection rule does not express every UI permission; UI restrictions therefore remain advisory and repository/rules enforcement remain authoritative.

## Minimal implementation plan

1. Reuse the existing `photoUri` field with a persistable gallery document picker, photo preview, replace/remove action, Coil rendering and initials fallback.
2. Reuse `AccessControl`/`SessionManager` for visibility and expose an "Effective Access" summary in the staff profile. Do not weaken Firestore or repository checks.
3. Return ephemeral invite details from successful staff creation and show a one-time share sheet. The temporary password is passed only in memory to that sheet and is never persisted or retrievable later.
4. Add a small audit writer over the existing immutable-by-normal-flow audit table. Record reliable successful login, manual logout, session expiry, staff create/update/archive and invite sharing; show staff-specific security/activity entries in the profile. Do not claim IP, location or failed-login identity that is not reliably known.
5. During Firebase resume validation, verify the active staff record. If an account has been archived/inactivated, expire its app session; do not turn a valid 403 into a logout.

## Risks and intentionally deferred work

- A client app cannot administratively disable Firebase Authentication accounts or revoke Firebase refresh tokens without a trusted backend/Admin SDK. This change blocks app access on the next centralized validation; true server-side credential reset/disable remains deferred.
- Existing edit fields update local/Firestore staff metadata but cannot safely change Firebase email/password from this client-only path. This is intentionally not represented as a completed credential reset feature.
- Existing Firestore rules should be reviewed/deployed separately for full granular server-side permission enforcement; they are not changed in this Android-only scope.
- No Room/Firestore business or financial data migration/deletion is required.
