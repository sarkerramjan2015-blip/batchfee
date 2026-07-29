# Problem 12 - Student Registration audit

## Confirmed current flow and root causes

- The Android app generates `https://batchfee-477b8.web.app/register.html?instituteId=<raw id>` in `RegistrationRepository`. The raw identifier is exposed and no registration token, link lifecycle, batch scope, expiry, or revoke state exists.
- The public form itself is outside the Android app (`web_form`) and the Android app is prohibited from changing it for this problem. It therefore cannot be converted safely to token resolution or branded rendering from this scope without breaking the live form.
- Pending submissions are Firestore-only at `registrations/{instituteId}/pending/{requestId}`; there is no Room entity/sync. The old Android flow reads only `status=pending`, creates a new `StudentEntity`, then deletes the submission on approve or reject. This loses approval/rejection history and makes retries unsafe.
- Existing submission fields are name, phone, guardian name, WhatsApp, gender, DOB, school/class and address. No batch id, photo or email is currently submitted by the Android-visible model.
- Institute branding fields already available in Android are name, profile photo/logo URI, address, phone and email. The public form does not currently receive a safe branded identity payload from Android.
- There was no duplicate check for phone, no pending-registration duplicate warning, no approval idempotency, no rejection reason/history, and no use of the existing Problem 08 audit trail.
- Registration management is reached through Settings (an admin-only route), but the registration ViewModel had no effective-access guard. Firestore currently permits public creates and only a narrow authenticated delete rule; it has no token validation or abuse/rate controls.

## Safe Android-only implementation boundary

- Keep the existing raw-id form URL for compatibility: issuing a token URL without updating the prohibited public form would make registration unavailable. Opaque token validation, branded public rendering, link expiry/revoke and server-side rate limiting are intentionally deferred to a coordinated web/backend change.
- Improve the Android-owned institute review flow only: safe share/copy, explicit legacy-link messaging, pending/approved/rejected retained states, duplicate checks, idempotent student IDs, approver/rejector metadata, optional rejection reason, and audit events.
- No existing registration or student data will be deleted or migrated by this change.
