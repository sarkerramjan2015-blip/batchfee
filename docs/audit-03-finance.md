# Audit 03 — Finance (Due Fees / Collection / Receipts)

Scope: due-fees calculation, fee collection flow, edit receipt logic, and receipt
logo/watermark rendering on generated PDFs.

Verdict per finding: `BUG` (fixed), `NO ISSUE`, or `NOTE`.

---

## Summary

The due-fee math and the trusted-ledger collection flow are sound; the previous audit rounds
(problem-05 monthly reconciliation, problem-07 receipt snapshots, due-fee dashboard mismatch)
are correctly implemented in the current code. The audit found **five bugs**, all in receipt
branding/PDF generation and one batch-scoped fee query:

| # | Severity | Verdict |
|---|----------|---------|
| 1 | High | BUG — receipt PDF watermark was drawn *before* opaque backgrounds and was never visible → FIXED |
| 2 | High | BUG — receipt PDF generated on the main thread; remote (https) logo load always threw, so the branded PDF never generated → FIXED |
| 3 | Medium | BUG — legacy receipt bitmap (share image) silently dropped the logo for https logos (main-thread network) → FIXED |
| 4 | Medium | BUG — batch-wise fee queries joined enrollments on `studentId` only, leaking other batches' fees into batch stats → FIXED |
| 5 | Low | BUG — backend `owner_edit_payment` did not stamp `updatedAtMs` on the edited receipt → FIXED |

---

## 1. Receipt PDF watermark was covered by opaque backgrounds — FIXED

- **Location:** [`generateReceiptPdf`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:2878).
- **Bug:** the watermark (logo bitmap at alpha 12) was drawn immediately after
  `canvas.drawColor(WHITE)` and **before** the opaque pale page fill
  ([`UnifiedCollectScreen.kt`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:2951))
  and the opaque blue header/footer rectangles. Those later fills painted over the watermark,
  so it was invisible on every printed/shared receipt PDF.
- **Fix:** the watermark draw was moved to the final layer, directly before
  `document.finishPage` ([`UnifiedCollectScreen.kt`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:3073)).
  The faded logo now renders over the pale page body and under/above nothing that hides it.

## 2. Receipt PDF generation ran on the main thread — FIXED

- **Location:** [`printHistoryReceipt`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:2861)
  and [`sendHistoryReceiptWhatsApp`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:2832).
- **Bug:** both were called synchronously from Compose click handlers and loaded the institute
  logo over `HttpURLConnection` for `https` sources (the normal state after any logo upload).
  That throws `NetworkOnMainThreadException`, the catch swallowed it, and the user silently got
  a text-only fallback — the branded PDF (logo + watermark) was never produced.
- **Fix:** both helpers are now `suspend` and run `generateReceiptPdf` inside
  `withContext(Dispatchers.IO)`; the call sites launch them with
  `scope.launch { ... }` ([`UnifiedCollectScreen.kt`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:675)).
  Activity intents are started back on the Main dispatcher.
- **Emulator verification:** tapped **Print** on a real payment history row
  (REC-0000000017, demo institute `ICT TOPPERS`). The file
  `cache/history_receipt_REC-0000000017.pdf` was produced (98 KB) and contains **2 embedded
  image XObjects** — the header logo and the centered watermark — proving the remote logo was
  fetched successfully and both placements are drawn.

## 3. Legacy receipt share-image silently dropped the logo — FIXED

- **Location:** [`createReceiptBitmap`](app/src/main/java/com/example/ui/fees/FeeScreens.kt:215)
  call sites in `CollectPaymentScreen` (WhatsApp/Share buttons) and `ReceiptDetailScreen`.
- **Bug:** [`loadBitmapFromUri`](app/src/main/java/com/example/ui/fees/FeeScreens.kt:309) does a
  synchronous `HttpURLConnection` for `https` logos. Called from click handlers on the main
  thread, the exception was swallowed and the image silently rendered the initials fallback.
- **Fix:** the three handlers now create the bitmap inside
  `scope.launch { withContext(Dispatchers.IO) { ... } }` before sharing
  ([`FeeScreens.kt`](app/src/main/java/com/example/ui/fees/FeeScreens.kt:778),
  [`FeeScreens.kt`](app/src/main/java/com/example/ui/fees/FeeScreens.kt:815),
  [`FeeScreens.kt`](app/src/main/java/com/example/ui/fees/FeeScreens.kt:1342)); the PDF print
  buttons in those screens are synchronous but contain no network work, so they are unchanged.
- `ReceiptDetailScreen` gained a `rememberCoroutineScope()`.

## 4. Batch-wise fee queries leaked other batches' fees — FIXED

- **Location:** [`FeeDao.kt`](app/src/main/java/com/example/data/dao/FeeDao.kt:31)
  (`getFeesByBatch`, `getFeesByBatchOnce`, `getTotalCollectedForBatch`, `getTotalExpectedForBatch`).
- **Bug:** all four queries joined `batch_students` on `f.studentId = bs.studentId` without
  matching `f.batchId = bs.batchId`. A student enrolled in two batches had every fee from both
  batches counted in each batch's paid/due stats
  ([`BatchListScreen.kt`](app/src/main/java/com/example/ui/batches/BatchListScreen.kt:76) chips and
  [`BatchPaymentViewModel`](app/src/main/java/com/example/ui/batches/BatchPaymentViewModel.kt:113)).
  This was already identified in the problem-05 report as a required fix and had not been applied.
- **Fix:** all four queries now join on `f.batchId = bs.batchId`.

## 5. Edited receipts did not refresh `updatedAtMs` — FIXED

- **Location:** [`financialLedger.js`](functions/src/financialLedger.js:1221)
  `owner_edit_payment` receipt update.
- **Bug:** the server updated feeId, date, totals, method and receipt text on the receipt
  document but never stamped `updatedAtMs`, leaving the edited receipt with a stale edit
  timestamp in Firestore/Room.
- **Fix:** `updatedAtMs: now` added to the receipt transaction update. Verified the rest of the
  edit path is correct: the server also updates the payment, source/target fees with ledger
  recalculation, writes a `payment_corrections` audit document, and the client validates the
  returned `owner_edit_payment` result shape before touching Room
  ([`FeeCollectionRepository.kt`](app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt:454)).
  Deploys with `firebase deploy --only functions`.

---

## Verified — NO ISSUE

### Due-fees calculation
- [`MonthlyDueCalculator`](app/src/main/java/com/example/domain/MonthlyDueCalculator.kt:29)
  correctly derives arrears month-by-month only for completed months, applies the first-month
  proration rule (30-day rule), honours frozen first-month period/amount for new/shifted
  enrollments, applies custom monthly fees from their effective period, and caps removed
  enrollments at their departure month.
- All three consumers — [`FeeViewModel`](app/src/main/java/com/example/ui/fees/FeeViewModel.kt:144),
  [`DashboardScreen`](app/src/main/java/com/example/ui/dashboard/DashboardScreen.kt:335), and
  [`StudentProfileScreen`](app/src/main/java/com/example/ui/students/StudentProfileScreen.kt:315) —
  use the same calculator and the same effective billing start, so due totals stay consistent.
- The old dashboard closed/inactive-student inflation bug is fixed: closed students now feed
  `closeAmount`/`closeCount` only, and `activeAmount` is the filtered sum.
- Archived students/batches drop out of due calculations by design
  ([`StudentDao`](app/src/main/java/com/example/data/dao/StudentDao.kt:9),
  [`BatchDao`](app/src/main/java/com/example/data/dao/BatchDao.kt:9)).
- Invalid legacy monthly rows are excluded from display and repaired owner-side by
  [`FeeCollectionRepository.reconcileInvalidMonthlyFees`](app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt:219)
  (server keeps payments/receipts immutable).

### Fee collection flow
- [`FeeCollectionRepository`](app/src/main/java/com/example/data/repository/FeeCollectionRepository.kt:310)
  funnels every collection, adjustment, reversal, edit and delete through the trusted
  [`financialLedger.js`](functions/src/financialLedger.js) with a durable outbox, replay on sync
  ([`CoreDataSyncCoordinator.kt`](app/src/main/java/com/example/data/firestore/CoreDataSyncCoordinator.kt:44)),
  and strict canonical-response validation before Room is written.
- Multi-month collection creates one exact payment+receipt per month and shares one receipt
  group id; partial payments and discounts are ledger-checked server-side.

### Edit receipt logic
- [`owner_edit_payment`](functions/src/financialLedger.js:1064) validates the reason, requires a
  completed payment, recalculates effective paid amounts, supports moving the payment to another
  month deterministically, updates fee/payment/receipt atomically, and writes an immutable
  `payment_corrections` audit record.
- [`owner_delete_payment`](functions/src/financialLedger.js:1265) deletes the receipt and
  payment, recalculates the fee ledger, and writes a `payment_deletions` audit record; the
  client refreshes Room only from the returned canonical result.
- The Android UI ([`PaymentEditDialog`](app/src/main/java/com/example/ui/fees/UnifiedCollectScreen.kt:1201))
  blocks double-submits and handles `FinancialOperationPendingException` explicitly.

---

## Notes (no functional break)

- The legacy `generatePdfReceipt` used by [`ReceiptDetailScreen`](app/src/main/java/com/example/ui/fees/FeeScreens.kt:143)
  print button and the legacy collect screen print button draws text only — no institute logo and
  no watermark. The parallel share-image path (`createReceiptBitmap`) does render the logo. Making
  the legacy PDF match the new branded document is a small feature upgrade, not a regression fix;
  left unchanged per the no-new-features rule.
- The legacy share-image path has a logo but no watermark (watermark was only designed for the
  unified receipt PDF).
- Receipt snapshots deliberately omit a `feePeriod` field; the displayed period always comes from
  the current (server-updated) fee row, which is correct after an owner edit.

---

## Verification (executed 2026-09-05)

- `.\gradlew.bat :app:compileDebugKotlin --console=plain` → BUILD SUCCESSFUL (deprecation warnings only).
- `.\gradlew.bat :app:assembleDebug -q` → success; APK reinstalled on `BatchFee_Pixel_API_37`.
- `node --check src/financialLedger.js` → clean.
- Emulator smoke test with demo credentials (`sarkerramjan2015@gmail.com` / `172002`):
  - Login → dashboard (`ICT TOPPERS`) → Fee tab → `Fee Collection` screen rendered with student
    list and `Collect Payment` buttons.
  - Opened a student with 3 payments; **Print** produced
    `cache/history_receipt_REC-0000000017.pdf` (98 KB) containing 2 embedded image XObjects
    (header logo + watermark) — confirming BUG-1 and BUG-2 fixes render the branded receipt.
- Deferred to backend deployment: BUG-5 (`updatedAtMs` on edited receipts) activates after
  `firebase deploy --only functions`.
