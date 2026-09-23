# Problem 13 — Student Online Payment → Owner Review/Approve Flow Audit

Date: 2026-09-22
Status: Feature is fully implemented in source (commit `27ef55a`, 2026-09-20) and the backend is deployed.
The only reason the owner currently sees no review option is that **no release APK has been built since
2026-09-16**, while this feature entered the app on **2026-09-20**. The APK on the phone is older than the feature.

---

## 1. Student side — submission

Screen: [`StudentPaymentRequestScreen.kt`](app/src/main/java/com/batchfee/edu/ui/studentapp/StudentPaymentRequestScreen.kt:104)

- Student picks due months (`OnlinePaymentAllocationResolver.dueMonths`), selects a method
  (bKash / Nagad / Rocket / Bank), enters sender phone + transactionId + amount + screenshot.
- Writes a request document to `institutes/{instituteId}/payment_requests` with `status: "pending"`,
  `studentId`, `method`, `transactionId`, `amount`, `months`, `screenshotRef`, `paymentDateMs`.
- The same screen listens to its own requests and shows live status
  (`pending` → `approved` / `rejected` / `correction_requested`).
- Editing/resubmission is supported while a request is still `pending` (`editingRequestId` flow).

## 2. Owner side — review UI (exists in source)

- Entry point: **Collection Fee** dashboard ([`FeeDashboardScreen.kt`](app/src/main/java/com/example/ui/fees/FeeDashboardScreen.kt:84))
  top bar has a mail-bell icon with a red **pending count badge**, listening to
  `payment_requests where status == "pending"`.
- Icon navigates to [`PaymentRequestReviewRoute`](app/src/main/java/com/example/ui/navigation/Routes.kt:52)
  wired in [`MainActivity.kt`](app/src/main/java/com/example/MainActivity.kt:831).
- Screen: [`PaymentRequestReviewScreen.kt`](app/src/main/java/com/example/ui/fees/PaymentRequestReviewScreen.kt:88)
  - Live listener on pending + reviewed requests.
  - Tap a request → [`PaymentRequestDetailDialog`](app/src/main/java/com/example/ui/fees/PaymentRequestReviewScreen.kt:293):
    - Shows student, method, transactionId, amount, screenshot, due-month breakdown.
    - **Duplicate warning** when the same `transactionId + method` already exists on another request.
    - **Approve** (with editable approved amount, honors partial-payment setting),
      **Reject**, and **Request correction** buttons.

## 3. What happens on Approve (backend, trusted callable)

`commitFinancialOperation` → action `review_payment_request` ([`financialLedger.js`](functions/src/financialLedger.js:1744)):

1. Validates decision, method (`bkash/nagad/rocket/bank`), transactionId, payment date, approved amount.
2. Builds grouped monthly allocations and runs the same atomic
   [`planGroupedCollection`](functions/src/financialLedger.js:1834) path used by manual collection:
   - Creates **payment** record(s) in `institutes/{id}/payments` (with `transactionId`, method).
   - Creates a **money receipt** (with `receiptNumber`) — yes, a receipt IS generated on approval.
   - Receipt text default: `Online payment approved: {METHOD} {transactionId}.`
3. Respects `payment_settings/config.allowPartialPayments`.
4. Applies overpayment to the student's `creditBalance` when applicable.
5. Updates the request: `status: "approved"`, `approvedPaymentId`, `receiptNumber`, `creditApplied`,
   `reviewedByUserId`, `reviewedAtMs`.
6. Writes an audit row to `payment_request_audit` and updates the institute `lastCollectionAtMs`.
7. Reject/correction move no money; they set `status` + `reviewNote` + audit row.

## 4. How it reflects in Collections / Payments (owner app)

[`FeeCollectionRepository.reviewPaymentRequest`](app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt:520)
runs through the durable financial outbox (`commitFinancialOperation`), and on success
`validateCanonicalResult` syncs the returned fees, **payments and receipt into the local Room DB**:

- The payment appears in the student's **Payment History** and collections summary.
- Monthly fees are marked paid; due amounts recalculate (same as a manual collection).
- The receipt is printable/shareable like any other money receipt.

## 5. Super Admin visibility

Read-only cross-institute trail: [`PaymentRequestTrailSection`](app/src/main/java/com/example/ui/superadmin/SuperAdminScreen.kt:7663)
(`collectionGroup("payment_requests")` listener) in the Super Admin overview.

---

## Why the owner currently cannot see/approve

| Layer | State |
|---|---|
| Source code (app + functions) | ✅ Present since commit `27ef55a` (2026-09-20) |
| Production `commitFinancialOperation` | ✅ Deployed 2026-09-21 (revision `commitfinancialoperation-00020-qoy`) |
| APK installed on the owner's phone | ❌ **Built 2026-09-16 (v1.8, code 12) — before the feature existed** |

Conclusion: the review/approve UI is not missing from the project — it has never shipped to the phone.
**Fix: build and install a new release APK from the current source** (on hold until the owner gives the go-ahead).

## Notes / minor gaps found

1. **Staff can open the screen but cannot approve.** Route access in
   [`StaffAccess.kt`](app/src/main/java/com/example/domain/StaffAccess.kt:97) allows staff with
   `VIEW_FEE_SUMMARY`/`COLLECT_FEE` to open `PaymentRequestReviewRoute`, but the backend action
   `review_payment_request` is owner-only ([`financialLedger.js`](functions/src/financialLedger.js:40)).
   Staff approvals would fail with a permission error. Either hide Approve/Reject for staff in the UI
   or add an explicit owner-only permission gate.
2. **No push notification** — the owner only learns about a new payment request through the
   in-app red badge on the Collection Fee screen. (Optional improvement, not a blocker.)
3. No other blockers found: duplicate detection, partial-payment policy, credit handling, audit trail,
   receipt generation and local DB reflection are all implemented.
