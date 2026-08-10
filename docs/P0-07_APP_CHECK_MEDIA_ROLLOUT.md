# P0-07 production rollout and legacy-media migration

This repository is code-ready, but this checklist intentionally does not deploy functions,
change Firebase enforcement, change Cloudinary settings, or rewrite production data.

## Required production rollout order

1. Create the Functions secret `CLOUDINARY_URL` in the form
   `cloudinary://API_KEY:API_SECRET@CLOUD_NAME`. Never put it in Android resources,
   BuildConfig, Firestore, Remote Config, source control, or CI logs.
2. Register the Android app and release signing certificates for Play Integrity App Check.
   Register individual Firebase App Check debug tokens only for approved development devices;
   never register a debug token used by a release build.
3. Deploy the callable functions. Confirm `uploadSecureMedia` and `getSecureMediaUrl` metrics,
   authentication failures, App Check failures, latency, and Cloudinary error rate in a staging
   Firebase project before production.
4. Release the Android build that installs the debug provider only in the debug source set and
   the Play Integrity provider only in the release source set.
5. In Firebase App Check, monitor unverified traffic first. Then enable enforcement for Cloud
   Functions and Firestore after the supported-app threshold is acceptable. The callable code
   already rejects missing/invalid App Check tokens with `enforceAppCheck: true`.
6. Disable and then delete/rotate the legacy Cloudinary unsigned preset
   `bf_institute_logo_9q7k2m4x`. Confirm no supported app version still sends unsigned uploads.
   The preset value has been removed from the Android source, but only Cloudinary console/API
   action can invalidate the already-exposed production preset.

## New-media policy

- Institute logos use a server-signed upload and remain intentionally public because they are
  public branding embedded in receipts and ID cards.
- Student and staff photos upload through an Auth + App Check protected callable as Cloudinary
  `authenticated` assets. Firestore and Room store only `batchfee-media://v1/...` references.
- Image display exchanges that reference for a five-minute signed download URL after checking
  institute membership and purpose-specific permission. Signed delivery URLs are never stored.
- Ownership, upload idempotency, replacement state, and audit metadata live in backend-only
  `media_assets`, `media_upload_operations`, and `media_audit` documents.
- Replacement and safe-deletion flows retain assets and audit state. No client can physically
  delete a Cloudinary asset.

## Legacy public student/staff photo migration

Run migration only from a reviewed Admin SDK job and default it to dry-run.

1. Enumerate student/staff documents whose photo field is an HTTPS URL. Allow only the expected
   Cloudinary host/cloud name; report every other host for manual review. Never fetch arbitrary
   document URLs from the migration worker.
2. For each candidate, re-read the canonical institute and entity immediately before migration.
   Skip inactive/retained institutes and records whose URL changed since the scan.
3. Download with strict byte, MIME, redirect, timeout, and image-decode limits; optimize to the
   same JPEG bounds used by the app; upload as an `authenticated` asset through the trusted
   backend; create ownership and audit records.
4. In one Firestore transaction, verify the old URL is still current and replace it with the new
   opaque reference. Record old and new asset IDs/URLs in a backend-only migration audit.
5. Keep the legacy public asset for at least 30 days. Sample and verify authorized display,
   cross-tenant denial, offline sync, receipt/logo rendering, and rollback before cleanup.
6. Physical cleanup must be a separate privileged, idempotent job. It must prove the old URL is
   unreferenced, not under deletion/legal retention, and present in the migration audit before a
   signed Cloudinary destroy call. Failed cleanup stays retryable and never rolls back the new
   reference.

Public exposure of historical URLs ends only after this migration and the final verified cleanup;
the application deliberately keeps legacy URLs readable until then to avoid breaking existing data.
