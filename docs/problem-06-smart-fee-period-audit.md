# Problem 06 — Smart fee period audit

## Confirmed current limitation

`UnifiedCollectScreen.kt` has two separate paths:

- **Due** selects one existing `FeeEntity` and calls
  `FeeCollectionRepository.collectPayment`. This correctly keeps a partial
  payment on that one fee.
- **New Fee** uses Start/End indices from a raw dropdown, multiplies the batch
  monthly fee by the number of indices, and calls
  `createFeeWithInitialPayment` once for the whole label/range.

The second path neither uses Problem 05's individual month obligations nor
knows a student's admission/batch-join fee start. It can create an arbitrary
range record even when matching monthly obligations already exist. It also
labels a mixed range from calendar position rather than actual selected fee
records.

## Existing financial behavior

- `PaymentEntity` has one `feeId`; therefore an existing payment is attached
  to exactly one fee.
- Partial payment is safe for that one fee: `paidAmount`, `dueAmount`, and
  `status` remain on the same `FeeEntity`.
- The current discount control is a percentage. It is stored in
  `FeeEntity.discountAmount`; no separate fixed-discount or waiver model
  exists.
- The current screen prevents duplicate *range labels* locally and disables its
  button while saving, but it has no multi-month allocation operation.
- Existing month options span the prior year through two years after the
  current year. There is no separate institute-configured advance limit.

## Problem 05 integration

Problem 05 creates deterministic `monthly_due_*` fee records for each payable
student/batch/month. Those records, including their `dueAmount`, are the source
of truth for normal collection. Future advance months must use the same
deterministic month identity so reconciliation will not create a second fee when
the future month becomes current.

## Minimal implementation plan

1. Add pure month/selection/allocation planning that derives fee-start from the
   later of admission and active batch join date, disables earlier months, and
   classifies actual obligations as Paid, Partial, Due, Current, or Advance.
2. Replace raw Start/End dropdown interaction with the existing compact
   month-grid dialog, augmented with disabled pre-start months and real status.
3. Keep Start/End and add a small Auto/Custom selector. Auto selects the oldest
   partial, then due, then current, then advance. Custom permits non-contiguous
   exact month selections; an earlier unselected due requires confirmation.
4. Add a repository multi-allocation operation. Each allocation updates only
   its exact `FeeEntity` and creates its own `PaymentEntity`/receipt record.
   Percentage discounts apply only to selected obligations. A 100% selected
   discount is recorded as a zero-payment waiver; no payment history is edited.
5. Create selected future monthly obligations using the Problem 05 deterministic
   ID and Firestore create-if-absent flow before allocating to them.

## Risks and deferred items

- Existing undated/free-form legacy fee records cannot be safely assigned to a
  particular calendar month; they remain selectable as legacy due records but
  are not rewritten.
- Existing payments and receipts are not reallocated or rewritten.
- A consolidated multi-month receipt layout is deliberately deferred to Problem
  07. Problem 06 records exact per-month allocations so that future receipt work
  has accurate source data.
