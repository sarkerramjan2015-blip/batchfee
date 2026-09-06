# Audit 05 — Admin (Subscriptions / Archives / Profit & Loss)

Scope: subscription logic (client + trusted backend), the Archives module, and institute
Profit & Loss calculations.

Verdict per finding: `NO ISSUE`, or `NOTE` (no functional break). No code changes were
required in this module.

---

## 1. Subscriptions — NO ISSUE

- **Server-owned pricing:** [`SubscriptionRepository.submitRequest`](app/src/main/java/com/example/data/repository/SubscriptionRepository.kt:48)
  never sends an amount; the trusted `commitSubscriptionOperation` callable resolves plan price,
  validates the sender number, quotes the plan, and enforces one-pending-request protection.
  All approve/reject/extend/block/manage actions flow through the same callable with strict
  response parsing ([`SubscriptionRepository.commit`](app/src/main/java/com/example/data/repository/SubscriptionRepository.kt:259)).
- **Backend policy is consistent across every entitlement check:**
  [`hasCurrentSubscription`](functions/src/subscriptionPolicy.js:10) requires `isActive`,
  no retained deletion state, `subscriptionStatus` of `trial`/`active`, and an unexpired
  `currentPeriodEndMs`; [`hasUnlimitedTrialStudents`](functions/src/subscriptionPolicy.js:24)
  additionally requires the free-trial plan id so a malformed paid record can never bypass
  student limits. The client mirror is [`SubscriptionPolicy.kt`](app/src/main/java/com/batchfee/edu/domain/SubscriptionPolicy.kt:4)
  (30-day trial), and the Android-side entitlement gate
  ([`CoreDataSyncCoordinator.hasServerConfirmedEntitlement`](app/src/main/java/com/example/data/firestore/CoreDataSyncCoordinator.kt:149))
  uses the same statuses server-confirmed.
- **UI:** [`BillingScreen.kt`](app/src/main/java/com/example/ui/billing/BillingScreen.kt:374)
  displays the correct period end (trial vs paid) and status color; the expired gate screen
  exists as [`SubscriptionExpiredScreen.kt`](app/src/main/java/com/example/ui/subscription/SubscriptionExpiredScreen.kt).
- **Idempotency:** every subscription mutation carries a client-generated `operationId`
  (`createEntitled*`, `commitSubscriptionOperation`) so lost responses cannot double-apply.

## 2. Archives — NO ISSUE

- **Restore:** [`AllArchivesScreen.restoreArchived`](app/src/main/java/com/example/ui/archive/AllArchivesScreen.kt:369)
  routes students through the seat-checked, audited
  [`StudentDeletionRepository.restore`](app/src/main/java/com/example/data/repository/StudentDeletionRepository.kt:16)
  and batches/staff through
  [`SafeDeletionRepository`](app/src/main/java/com/example/data/repository/SafeDeletionRepository.kt:25),
  so counters, media and Auth state are reconciled exactly as in the Step 2 audit.
- **Permanent purge:** [`PermanentArchivePurgeRepository`](app/src/main/java/com/example/data/repository/PermanentArchivePurgeRepository.kt:12)
  and [`PermanentStudentPurgeRepository`](app/src/main/java/com/example/data/repository/PermanentStudentPurgeRepository.kt:12)
  call the trusted `permanentlyPurge*` callables first and mirror local deletes only after
  server success. The server-side student purge
  ([`permanentStudentPurge.js`](functions/src/permanentStudentPurge.js:58)) requires an archived
  student, is owner/SuperAdmin-only, deletes Auth identity + login docs + claims + media asset,
  and treats a lost-response retry as a replay (`replayed: true`) instead of a failure.
- **Client purge gate matches backend authority:** `canPermanentlyDelete` is
  `InstituteOwner`/`SuperAdmin` ([`AllArchivesScreen.kt`](app/src/main/java/com/example/ui/archive/AllArchivesScreen.kt:118)),
  matching the server's owner-or-SuperAdmin requirement.

## 3. Institute Profit & Loss — NO ISSUE

- **Income** is the sum of `completed` payments
  ([`ProfitLossViewModel.kt`](app/src/main/java/com/example/ui/reports/ProfitLossViewModel.kt:31);
  [`PaymentDao.getRecentPayments`](app/src/main/java/com/example/data/dao/PaymentDao.kt:9) has no
  LIMIT, so it is the full history). Reversed payments are excluded because reversal flips the
  status off `completed`, and deleted payments are removed with their fee recalculation.
- **Expense** is the sum of active expense rows
  ([`ExpenseDao.getExpensesByInstitute`](app/src/main/java/com/example/data/dao/ExpenseDao.kt:13)
  filters `archivedAtMs IS NULL`), so cancelled salaries (whose expense row is archived) and
  manually archived expenses drop out correctly.
- Net profit and margin render from the two totals
  ([`ProfitLossScreen.kt`](app/src/main/java/com/example/ui/reports/ProfitLossScreen.kt:57)).

---

## Notes (no functional break)

- Salary expenses are booked in full (`netSalary`) at salary generation time, before payment
  ([`SalaryViewModel.buildSalaryExpense`](app/src/main/java/com/example/ui/staff/SalaryViewModel.kt:132)).
  P&L therefore uses an accrual view — an unpaid generated salary still counts as expense.
  Acceptable, but a cash-basis report would need a product decision.
- The P&L margin is clamped to `0.0` when the net result is negative
  ([`ProfitLossScreen.kt`](app/src/main/java/com/example/ui/reports/ProfitLossScreen.kt:58)) — a
  display choice; the net amount itself is shown honestly.
- The "Add Income" / "Add Expense" chips on the P&L screen are navigation placeholders.
- [`AllArchivesScreen`](app/src/main/java/com/example/ui/archive/AllArchivesScreen.kt:104) shows
  the Restore button to every role that reaches the route; the backend still rejects
  unauthorized restores with a snackbar. Re-checked in Step 6.
- `permanentlyPurgeStudent` local mirror leaves `bulk_message_log` rows for the purged student
  (orphaned audit rows only, noted in audit-02).

## Verification

- Static verification of both client and `functions/src` subscription/purge flows; no code
  changes were required, so no rebuild was necessary for this module (the last
  `:app:compileDebugKotlin` build in Step 4 remains green).
