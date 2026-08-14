# P0-07 Firebase Storage media rollout and legacy-media migration

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
6. The deployed Functions retain `CLOUDINARY_URL` only for temporary signed read/purge support of
   pre-cutover private assets. Only after all legacy references are migrated and sampled
   successfully, remove that compatibility code/secret, revoke the Cloudinary credentials/preset,
   and remove legacy assets according to retention policy.

## Legacy Cloudinary cutover

Do not switch off Cloudinary while documents still contain legacy values:

- HTTPS Cloudinary URLs remain displayable until they are explicitly migrated.
- Old `batchfee-media://v1/...` references whose asset documents have Cloudinary delivery metadata
  retain signed-read compatibility in this Functions version. New uploads never create them.
- Take a Firestore/Auth backup, inventory references, migrate a small sample to Firebase Storage,
  verify authorised display and rollback, retain legacy source assets for at least 30 days, then
  migrate the remainder. Physical legacy deletion is a separate reviewed operation.

The application deliberately does not auto-download or rewrite historical production media during
normal user actions. That avoids an unreviewed bulk data mutation and prevents a failed migration
from losing an original image.
