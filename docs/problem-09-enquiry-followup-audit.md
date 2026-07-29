# Problem 09 — Enquiry follow-up audit

## Confirmed current behavior

- `EnquiryEntity` has only name, phone, address, subject, enquiry date, one status and archive timestamp. The DAO returns only unarchived rows.
- The UI provides `active`, `follow_up` and `close` status buttons. "Follow Up" is only a status count; it has no date, time, owner, note, priority or timeline.
- `EnquirySyncHelper` stores the same minimal entity in `institutes/{instituteId}/enquiries`.
- There is no archive/restore/delete UI or DAO method, no contact intent, no source/assignee, no student conversion link, and no duplicate-phone guard.
- `StudentEntity` creation needs student-specific information that an enquiry does not reliably contain (class/batch and admission inputs). There is no existing prefilled conversion route.
- The existing Problem 08 `AuditLogEntity`/`StaffAuditLogger` can record sensitive enquiry lifecycle events without a second history store.
- Route access currently maps Enquiries to the existing `VIEW_REPORTS` permission. This task must preserve that behavior; a new unrelated permission model is not justified.

## Minimal safe solution

1. Add nullable, additive follow-up, source, assignment and conversion-link fields to the enquiry record; migrate Room v16→v17 without destructive changes.
2. Add archive/restore and permanent delete DAO operations, with owner/admin guard for permanent delete. Normal list remains active-only; archived entries are retrievable separately.
3. Normalize contact values only for comparisons/intents, preserving the displayed original number. Add Call, WhatsApp, SMS and Copy actions.
4. Provide a compact enquiry detail sheet to update status/follow-up and show overdue/today/upcoming; use audit logs for meaningful lifecycle events.
5. Add duplicate detection and a conversion prefill object. A full student creation form is intentionally not bypassed: conversion must hand off to the existing student form rather than fabricate missing academic data.

## Risks / deferred work

- A dedicated per-enquiry immutable timeline table would require a larger CRM schema. The existing institute audit log is reused for lifecycle history.
- Server-side granular Enquiry permissions remain subject to the existing deferred Firebase rules hardening from Problem 08.
