"use strict";

const assert = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { createFinancialLedgerHandler } = require("../src/financialLedger");

const projectId = process.env.GCLOUD_PROJECT || "demo-batchfee-financial";
if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run this test through the Firestore emulator.");
}

initializeApp({ projectId });
const db = getFirestore();
const commit = createFinancialLedgerHandler({ db });
const instituteId = "financial-owner";
const studentId = "financial-student";

function operation(operationId, action, values = {}) {
  return {
    auth: { uid: instituteId, token: {} },
    data: { instituteId, operationId, action, ...values },
  };
}

function createFeeRequest(operationId, feePeriod, values = {}) {
  return operation(operationId, "create_fee", {
    studentId,
    batchId: "batch-1",
    feePeriod,
    feeType: "monthly_fee",
    dueDateMs: 1_800_000_000_000,
    baseAmount: 1000,
    discountAmount: 0,
    lateFeeAmount: 0,
    ...values,
  });
}

async function expectCode(expectedCode, work) {
  let failure;
  try {
    await work();
  } catch (error) {
    failure = error;
  }
  assert.equal(failure && failure.code, expectedCode, failure && failure.stack);
}

async function collectionCount(name) {
  return (await db.collection("institutes").doc(instituteId).collection(name).get()).size;
}

async function main() {
  const instituteRef = db.collection("institutes").doc(instituteId);
  await instituteRef.set({ instituteName: "Financial Integration", isActive: true });
  await instituteRef.collection("students").doc(studentId).set({
    instituteId,
    fullName: "Ledger Student",
    status: "active",
    archivedAtMs: null,
  });
  await instituteRef.collection("receipts").doc("legacy-receipt-collision").set({
    instituteId,
    studentId,
    receiptNumber: "REC-0000000001",
    ledgerVersion: 0,
  });

  const initialRequest = createFeeRequest("operation-create-001", "May 2026", {
    amount: 400,
    paymentMethod: "cash",
    paymentDateMs: 1_700_000_000_000,
  });
  const initial = await commit(initialRequest);
  assert.equal(initial.fees.length, 1);
  assert.equal(initial.payments.length, 1);
  assert.equal(initial.receipts.length, 1);
  assert.equal(initial.receipts[0].receiptNumber, "REC-0000000002");
  assert.equal(initial.fees[0].paidAmount, 400);
  assert.equal(initial.fees[0].dueAmount, 600);

  const retry = await commit(initialRequest);
  assert.deepEqual(retry, initial);
  assert.equal(await collectionCount("fees"), 1);
  assert.equal(await collectionCount("payments"), 1);
  assert.equal(await collectionCount("receipts"), 2);

  await expectCode("already-exists", () => commit(
    createFeeRequest("operation-create-002", " may   2026 "),
  ));
  assert.equal(await collectionCount("fees"), 1);

  const paymentCountBeforeRejection = await collectionCount("payments");
  await expectCode("failed-precondition", () => commit(operation(
    "operation-overpay-01",
    "collect_payment",
    {
      feeId: initial.fees[0].id,
      amount: 601,
      paymentMethod: "cash",
      paymentDateMs: 1_700_000_000_100,
    },
  )));
  assert.equal(await collectionCount("payments"), paymentCountBeforeRejection);
  assert.equal((await instituteRef.collection("fees").doc(initial.fees[0].id).get()).get("paidAmount"), 400);

  const sharedReceiptGroup = "receipt-group-0001";
  const [june, july] = await Promise.all([
    commit(createFeeRequest("operation-create-003", "June 2026", {
      amount: 100,
      paymentMethod: "cash",
      paymentDateMs: 1_700_000_000_200,
      receiptGroupId: sharedReceiptGroup,
    })),
    commit(createFeeRequest("operation-create-004", "July 2026", {
      amount: 100,
      paymentMethod: "cash",
      paymentDateMs: 1_700_000_000_300,
      receiptGroupId: sharedReceiptGroup,
    })),
  ]);
  assert.equal(june.receipts[0].receiptNumber, july.receipts[0].receiptNumber);
  assert.equal(june.receipts[0].receiptNumber, "REC-0000000003");
  assert.equal((await instituteRef.collection("ledger_internal").doc("receipt_sequence").get()).get("lastValue"), 3);

  const secondPayment = await commit(operation("operation-payment-01", "collect_payment", {
    feeId: initial.fees[0].id,
    amount: 600,
    paymentMethod: "bkash",
    transactionId: "TXN-UNIQUE-01",
    paymentDateMs: 1_700_000_000_400,
  }));
  assert.equal(secondPayment.fees[0].status, "paid");
  assert.equal(secondPayment.receipts[0].receiptNumber, "REC-0000000004");

  await expectCode("already-exists", () => commit(operation(
    "operation-payment-02",
    "collect_payment",
    {
      feeId: june.fees[0].id,
      amount: 100,
      paymentMethod: "bkash",
      transactionId: " txn-unique-01 ",
      paymentDateMs: 1_700_000_000_500,
    },
  )));

  const reversalRequest = operation("operation-reverse-01", "reverse_payment", {
    paymentId: initial.payments[0].id,
    reason: "Duplicate collection",
  });
  const reversal = await commit(reversalRequest);
  assert.equal(reversal.reversals.length, 1);
  assert.equal(reversal.fees[0].paidAmount, 600);
  assert.equal(reversal.fees[0].dueAmount, 400);
  const immutablePayment = await instituteRef.collection("payments").doc(initial.payments[0].id).get();
  const immutableReceipt = await instituteRef.collection("receipts").doc(initial.receipts[0].id).get();
  assert.equal(immutablePayment.exists, true);
  assert.equal(immutablePayment.get("status"), "completed");
  assert.equal(immutableReceipt.exists, true);
  assert.deepEqual(await commit(reversalRequest), reversal);
  await expectCode("already-exists", () => commit(operation(
    "operation-reverse-02",
    "reverse_payment",
    { paymentId: initial.payments[0].id, reason: "Second reversal attempt" },
  )));

  await instituteRef.collection("staffs").doc("inactive-collector").set({
    status: "inactive",
    archivedAtMs: null,
    permissions: "collect_fee",
  });
  await expectCode("permission-denied", () => commit({
    auth: { uid: "inactive-collector", token: {} },
    data: createFeeRequest("operation-cross-001", "August 2026").data,
  }));
  await expectCode("permission-denied", () => commit({
    auth: { uid: "different-owner", token: {} },
    data: createFeeRequest("operation-cross-002", "August 2026").data,
  }));

  process.stdout.write("financial ledger emulator integration: PASS\n");
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
