# P0-04 legacy student credential migration

This runbook removes the retired client-readable student credential fields and
revokes the old virtual-email Firebase Auth identities. The migration is not run
automatically and must never be pointed at production without a reviewed backup
and an approved maintenance window.

## Safe rollout order

1. Back up Firestore and Firebase Auth, and confirm that the P0-03 Cloud Functions
   student authentication implementation is ready. New P0-03 student identities
   have no email and are not selected by this migration.
2. Run the migration in its default dry-run mode. It reports counts only; it never
   prints hashes, email addresses, document data, or passwords.
3. Review the counts. Notify institutes that legacy student app accounts require a
   one-time password reset/re-provision through the existing student edit flow.
4. In a maintenance window, run apply mode. Apply requires both an exact project
   confirmation and `--disable-legacy-auth`; it disables matching
   `@s.batchfee.app` Auth users, revokes their refresh tokens, removes all retired
   `student_login_mappings`, and deletes credential fields from student documents.
5. Re-run dry-run and require `enabledFound` to be zero, along with zero
   dirty student documents, and zero legacy mappings before deploying the hardened
   Firestore rules/client. Dirty documents intentionally fail client reads, because
   Firestore rules cannot redact individual fields.

## Commands

From the repository root, with reviewed Application Default Credentials:

```powershell
npm --prefix functions run migrate:student-credentials -- --project YOUR_PROJECT_ID
```

Apply only after review and backup:

```powershell
npm --prefix functions run migrate:student-credentials -- --project YOUR_PROJECT_ID --apply --confirm-project YOUR_PROJECT_ID --disable-legacy-auth
```

The same commands can target local Auth and Firestore emulators by setting both
`FIREBASE_AUTH_EMULATOR_HOST` and `FIRESTORE_EMULATOR_HOST`. Never deploy or mutate
production as part of local verification.
