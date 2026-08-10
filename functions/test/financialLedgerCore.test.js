"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  feeBusinessKey,
  ledgerStatus,
  paymentReferenceKey,
  receiptNumber,
  requestHash,
  toMoney,
} = require("../src/financialLedgerCore");

test("fee business keys are normalized and tenant-local inputs remain deterministic", () => {
  const first = feeBusinessKey({
    studentId: " Student-1 ", batchId: "Batch-1", feePeriod: " May 2026 ", feeType: "MONTHLY_FEE",
  });
  assert.equal(first, feeBusinessKey({
    studentId: "student-1", batchId: "batch-1", feePeriod: "may 2026", feeType: "monthly_fee",
  }));
  assert.notEqual(first, feeBusinessKey({
    studentId: "student-1", batchId: "batch-2", feePeriod: "may 2026", feeType: "monthly_fee",
  }));
});

test("money and ledger calculations use bounded two-decimal values", () => {
  assert.equal(toMoney(100.1), 100.1);
  assert.throws(() => toMoney(1.005), /two decimal/);
  assert.throws(() => toMoney(-1), /Invalid/);
  assert.deepEqual(ledgerStatus(1000, 400), {
    paidAmount: 400,
    dueAmount: 600,
    status: "partially_paid",
  });
  assert.equal(ledgerStatus(1000, 1000).status, "paid");
});

test("receipt sequence, payment references, and operation hashes cannot collide trivially", () => {
  assert.equal(receiptNumber(1), "REC-0000000001");
  assert.equal(receiptNumber(123), "REC-0000000123");
  assert.equal(paymentReferenceKey("bkash", " ABC-123 "), paymentReferenceKey("BKASH", "abc-123"));
  assert.notEqual(paymentReferenceKey("bkash", "ABC-123"), paymentReferenceKey("nagad", "ABC-123"));
  assert.equal(requestHash({ b: 2, a: 1 }), requestHash({ a: 1, b: 2 }));
});
