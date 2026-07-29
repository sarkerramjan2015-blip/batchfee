# Problem 05 — Monthly due audit

## Confirmed root cause

Monthly fees are represented by persisted `FeeEntity` rows, and every current due
surface sums their `dueAmount`. There is no production flow that creates or
reconciles a monthly `FeeEntity` for each payable month after a student joins a
batch. As a result, a student can have a settled March fee while April through
July have no fee rows at all; the calculated due is then correctly zero for the
incomplete ledger, but financially wrong.

## Fee-start rule found

There is no explicit fee-start field. The available dates are
`StudentEntity.admissionDateMs` and the per-batch
`BatchStudentEntity.joinedAtMs`. The safe existing-model rule is therefore the
later of those two dates, rounded to its calendar month. This prevents charging
before admission or before enrollment in that specific batch. Payable months are
inclusive from that month through the current calendar month only.

## Affected modules

- `app/src/main/java/com/example/data/models/StudentEntity.kt` — admission date.
- `app/src/main/java/com/example/data/models/BatchStudentEntity.kt` — per-batch
  join date.
- `app/src/main/java/com/example/data/models/BatchEntity.kt` — monthly fee.
- `app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt` —
  manual one-fee payment and partial-payment lifecycle.
- `app/src/main/java/com/example/data/dao/FeeDao.kt` — persisted-due queries;
  its batch query was also joining a student's fees from other batches.
- `app/src/main/java/com/example/data/firestore/CoreDataSyncCoordinator.kt` and
  `OperationalDataSyncHelpers.kt` — Firestore/Room financial sync.
- `app/src/main/java/com/example/ui/fees/FeeViewModel.kt`,
  `StudentProfileScreen.kt`, and dashboard/due screens — all consume persisted
  fee rows and already sum `dueAmount`.
- `app/src/main/java/com/example/ui/batches/BatchPaymentViewModel.kt` — selected
  only the first fee for a student, so it would under-report once monthly rows
  exist.

## Current lifecycle before the fix

The fee collection screen creates a single `FeeEntity` for a manually selected
month or range and attaches every payment to that one fee. Firestore sync simply
copies those records to Room. Dashboard, profile, batch, and due screens sum
the records present; none derives obligations from admission/join dates. Demo
data contains manually seeded fee rows only and is not the cause for real
accounts.

## Minimal fix

Add an idempotent monthly reconciliation after a successful normal institute
sync. It plans one deterministic monthly fee ID per institute/student/batch/month
for active students in active batches with a positive monthly fee, creates only missing records,
and uses Firestore create-if-absent before inserting the matching Room row. It
does not alter existing fees, payments, receipts, discounts, waivers, or
historical amounts.

Existing monthly records are recognised by their established period labels
(`Mar 2026`, `March 2026`, or a supported monthly range), so a paid/partial
legacy month is retained rather than replaced. Undated/free-form legacy records
are deliberately not reallocated automatically because that would be unsafe.

The batch query and batch view-model will aggregate all fees scoped to the
current batch, keeping batch totals consistent with profile/dashboard totals.

## Risks and intentionally unchanged issues

- A legacy monthly payment whose period is completely undated cannot be safely
  allocated to a particular month; it is preserved and not rewritten.
- Moving a student between batches currently rewrites existing fee `batchId`
  values in `StudentViewModel`/`BatchViewModel`. That historic-data behavior is
  unrelated and is not changed here.
- No Room schema migration or production data deletion is required; the new
  deterministic fee IDs and existing primary key provide duplicate prevention.
