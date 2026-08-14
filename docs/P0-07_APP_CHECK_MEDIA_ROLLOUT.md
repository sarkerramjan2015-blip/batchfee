# P0-07 Firebase Storage media rollout

This repository is ready to use the active Firebase Storage bucket
`batchfee-477b8.firebasestorage.app`. This checklist intentionally does not deploy
functions/rules or modify production media.

## Security model

- Android sends an optimized JPEG only to the existing Auth + App Check protected
  `uploadSecureMedia` callable. It has no direct Firebase Storage write permission.
- The function writes with the Admin SDK, generation-match create-only semantics, an
  opaque object path, checksum metadata, ownership metadata, and an audit record.
- Student and staff photos are stored below the private prefix. Firestore/Room keep only
  `batchfee-media://v1/...`; an authorised request receives a five-minute V4 signed URL.
- Institute logos retain their intentional direct-display compatibility for receipts and
  PDFs. They use an opaque Firebase Storage path plus a server-created Firebase download
  token. Neither clients nor Firestore rules can upload, list, delete, or mutate any object.
- `storage.rules` denies every direct client read/write. Admin SDK calls and signed URLs are
  the only serving paths, so Firebase Storage must not be left in Test mode.

## Required rollout order

1. Confirm the active default bucket is `batchfee-477b8.firebasestorage.app`. For staging or a
   differently named bucket, set `FIREBASE_STORAGE_BUCKET` in the Functions runtime before deploy.
2. Give the deployed Functions runtime service account least-privilege object create/read/delete
   access to this bucket. It also needs permission to sign blobs for V4 URLs (`iam.serviceAccounts.signBlob`).
3. Deploy `storage.rules`, Firestore rules, and Functions to staging. Confirm all direct Android
   Storage SDK reads/writes fail, while the callable flow succeeds with App Check and Firebase Auth.
4. Run the media integration test against a Firestore emulator and perform real staging smoke tests:
   institute logo, student photo, staff photo, replacement/idempotency, signed-URL expiry,
   cross-institute denial, staff permissions, student self-photo access, and purge deletion.
5. Deploy the Android app after the Functions/rules rollout. Monitor upload and signed-URL errors,
   App Check rejections, Storage permission errors, object count, and egress cost.
6. The production media path is Firebase Storage only. `uploadSecureMedia`,
   `getSecureMediaUrl`, and permanent student purge do not use a third-party media service or a
   related secret. A non-Storage managed-media record is denied rather than being silently routed
   to another provider.

## Historical media

The application deliberately does not auto-download, rewrite, or delete historical media during
normal user actions. That avoids an unreviewed bulk data mutation and prevents a failed migration
from losing an original image. Any old private managed-media record that is not backed by the
configured Firebase Storage bucket is unavailable after this rollout and must be handled through
a separately reviewed migration or retention process.
