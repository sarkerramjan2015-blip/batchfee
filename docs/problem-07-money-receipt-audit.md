# Problem 07 — Money Receipt audit

## Confirmed receipt flow

The main app records a payment in `PaymentEntity`, then creates one `ReceiptEntity` for that payment in `FeeCollectionRepository.collectFromFee()` (`app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt`).  The same entities are synced to Firestore by `OperationalDataSyncHelpers.kt`.

Problem 06's `collectMonthlyAllocations()` creates a separate payment and receipt per selected monthly obligation.  Each receipt is therefore already scoped to one exact `FeeEntity` / fee period; it does not use a synthetic date range or move money to an unselected month.

There are two receipt presentations:

- `ReceiptDetailScreen` in `app/src/main/java/com/example/ui/fees/FeeScreens.kt`, reached after the legacy single-fee collection flow.
- Payment-history print/share helpers in `app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt`.

Both presentations currently rebuild student, batch, fee-period and discount details from live Room rows.  This is the confirmed cause of incomplete receipts and means a renamed student/batch or a later fee adjustment can alter how an old receipt is presented.  The persisted receipt already snapshots its receipt number, date, payment method, total, paid and remaining-due values; the payment separately preserves amount, method, reference field, note, status and collector user ID.

## Branding and authorization audit

`InstituteEntity` already has an admin-configurable `profilePhotoUri`, `name`, `phone`, `address` and `email` fields (`app/src/main/java/com/example/data/models/InstituteEntity.kt`).  The institute profile editor in `DashboardScreen.kt` already lets an institute update `profilePhotoUri`; no fake logo or new settings surface is required.  There are no stored signature-image or seal fields in the current main-app model, so an authorization line must remain a truthful placeholder rather than pretending an image exists.

`PaymentEntity.collectedByUserId` is persisted.  `UserEntity` and `StaffEntity` can provide the collector name, role and staff code at collection time, but those display values are not currently snapshotted.

## Confirmed limitations/root causes

1. `ReceiptEntity` lacks transaction-time student, batch, institute, collector and fee-detail snapshots.
2. `ReceiptDetailScreen` is visually minimal and its PDF/image exporters use ad-hoc arguments and the current clock rather than the persisted receipt date.
3. The existing history PDF has a signature line but does not use the institute logo, collector identity, payment reference/status, or a common receipt document model.
4. No refund/reversal workflow was found in the audited payment repository.  Existing payment status is retained and presented honestly; unsupported statuses are not invented.

## Implemented solution

1. Added nullable, additive receipt snapshot columns and a non-destructive Room 15-to-16 migration. New collections snapshot matching institute, student, batch, user and staff presentation data without changing payment/fee amounts.
2. Synced the snapshot fields to/from the existing institute-scoped Firestore receipt document. Old Room/Firestore receipts remain readable because all new fields are nullable.
3. Added ReceiptPresentation.kt, a pure mapper that prefers transaction-time snapshots, falls back only to a matching same-institute live record for legacy receipts, and never fabricates missing information.
4. Updated ReceiptDetailScreen and the Unified Collect payment-history print/WhatsApp/share actions to use one receipt document model and the stored receipt date. Output contains real logo/initials fallback, institute contacts, exact fee period, financial breakdown, payment status/reference/remarks, collector details and honest authorization lines. It renders a document bitmap/PDF rather than a screenshot of application chrome.
5. Added an optional transaction/reference field to Problem 06's existing payment input card. It is stored on PaymentEntity and snapshotted with the receipt; remarks remain separate.
6. Added focused JVM tests for the pure mapper. No migration was executed and no historical payment, fee, receipt, Room or Firestore business data was deleted or rewritten.
7. A Problem 06 multi-month collection now gives every exact allocation the same receipt number while retaining its own FeeEntity, PaymentEntity and ReceiptEntity rows. The receipt screen/history export groups only same-institute, same-student rows with that receipt number into one read-only consolidated document. Its totals are sums of the stored exact allocations; no generic date range or new financial record is created.
8. Added a focused v15-to-v16 SQLite upgrade test. It creates a v15 receipt/payment database, applies the real additive migration, and verifies the legacy financial values and nullable new columns after upgrade.

## Risks and intentional limits

- Old receipts lack several historical snapshots.  They can use the matching same-institute record when available, otherwise show `Not available`; they will not be overwritten automatically.
- A multi-month Problem 06 submission remains a set of exact per-month receipts.  This task does not introduce a new aggregate receipt transaction or change payment allocation.
- Signature-image/seal configuration and a settings redesign are intentionally deferred because the existing data model has no such fields.
- SMS remains text-only because the existing platform share route does not attach a document. Print, WhatsApp and generic share use the document export when a persisted receipt exists; legacy records fall back to the existing text/PDF representation if their receipt record is unavailable.
