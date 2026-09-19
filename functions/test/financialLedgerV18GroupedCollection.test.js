"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createFinancialLedgerHandler } = require("../src/financialLedger");

class Snapshot {
  constructor(ref, data) {
    this.ref = ref;
    this.id = ref.path.split("/").at(-1);
    this.exists = data !== undefined;
    this.value = data === undefined ? undefined : structuredClone(data);
  }
  data() { return this.value === undefined ? undefined : structuredClone(this.value); }
  get(field) { return this.value && this.value[field]; }
}

class Query {
  constructor(db, path, filters = [], limitValue = null) {
    this.db = db;
    this.path = path;
    this.filters = filters;
    this.limitValue = limitValue;
  }
  where(field, operator, value) {
    return new Query(this.db, this.path, [...this.filters, [field, operator, value]], this.limitValue);
  }
  limit(value) { return new Query(this.db, this.path, this.filters, value); }
  rows() {
    const depth = this.path.split("/").length + 1;
    const matching = [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value))
      .map(([path, data]) => new Snapshot(new Document(this.db, path), data));
    return this.limitValue == null ? matching : matching.slice(0, this.limitValue);
  }
}

class Collection extends Query {
  doc(id) { return new Document(this.db, `${this.path}/${id}`); }
}

class Document {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new Collection(this.db, `${this.path}/${name}`); }
}

class Db {
  constructor(seed) {
    this.documents = new Map(Object.entries(seed).map(([path, data]) => [path, structuredClone(data)]));
  }
  collection(name) { return new Collection(this, name); }
  async runTransaction(callback) {
    const transaction = {
      get: async (target) => target instanceof Query
        ? { docs: target.rows(), empty: target.rows().length === 0, size: target.rows().length }
        : new Snapshot(target, this.documents.get(target.path)),
      create: (ref, data) => this.documents.set(ref.path, structuredClone(data)),
      set: (ref, data, options) => this.documents.set(
        ref.path,
        options && options.merge ? { ...this.documents.get(ref.path), ...structuredClone(data) } : structuredClone(data),
      ),
      update: (ref, data) => this.documents.set(ref.path, { ...this.documents.get(ref.path), ...structuredClone(data) }),
      delete: (ref) => this.documents.delete(ref.path),
    };
    return callback(transaction);
  }
}

function seededDb() {
  const now = Date.now();
  return new Db({
    "institutes/i": {
      isActive: true,
      currentPlanId: "spark",
      subscriptionStatus: "active",
      currentPeriodEndMs: now + 86_400_000,
    },
    "app_users/owner": { instituteId: "i", role: "InstituteOwner", status: "active" },
    "institutes/i/students/s": { status: "active" },
  });
}

test("full waiver settles an eligible fee without creating a false cash payment", async () => {
  const db = seededDb();
  db.documents.set("institutes/i/fees/admission", {
    instituteId: "i", studentId: "s", batchId: "b", feePeriod: "Admission",
    feeType: "admission_fee", baseAmount: 500, discountAmount: 0, lateFeeAmount: 0,
    totalAmount: 500, paidAmount: 0, dueAmount: 500, status: "unpaid", cancelledAtMs: null,
  });
  const handler = createFinancialLedgerHandler({ db });

  const result = await handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "waive_fee", feeId: "admission",
    reason: "Scholarship approved", operationId: "waive-admission-0001",
  } });

  assert.equal(result.payments.length, 0);
  assert.equal(result.receipts.length, 0);
  const fee = db.documents.get("institutes/i/fees/admission");
  assert.deepEqual(
    [fee.discountAmount, fee.totalAmount, fee.paidAmount, fee.dueAmount, fee.status],
    [500, 0, 0, 0, "paid"],
  );
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/payments/")).length, 0);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/fee_waivers/")).length, 1);
  const activityEntries = [...db.documents.entries()].filter(([path]) => path.includes("/platform_activity_events/"));
  assert.equal(activityEntries.length, 1);
  const activity = activityEntries[0][1];
  assert.equal(activity.action, "fee_waived");
  assert.equal(activity.actorRole, "owner");
  assert.equal(activity.targetType, "student");
  assert.equal(activity.targetId, "s");
  assert.equal(activity.outcome, "completed");
});

test("grouped monthly collection is atomic, has one receipt, and is idempotent", async () => {
  const db = seededDb();
  const handler = createFinancialLedgerHandler({ db });
  const request = { auth: { uid: "owner" }, data: {
    instituteId: "i",
    action: "collect_grouped_payment",
    operationId: "grouped-months-0001",
    studentId: "s",
    paymentMethod: "cash",
    paymentDateMs: 1_788_000_000_000,
    allocations: [
      { batchId: "b", feePeriod: "Jun 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
      { batchId: "b", feePeriod: "Jul 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
    ],
  } };

  const first = await handler(request);
  const replay = await handler(request);

  assert.equal(first.receipts.length, 1);
  assert.equal(first.payments.length, 2);
  assert.deepEqual(replay, first);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/payments/")).length, 2);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/receipts/")).length, 1);
  const receipt = first.receipts[0];
  assert.equal(receipt.grouped, true);
  assert.equal(receipt.lineItems.length, 2);
  assert.equal(new Set(first.payments.map((payment) => payment.receiptNumber)).size, 1);
  assert.equal(first.payments[0].receiptNumber, receipt.receiptNumber);

  const activityEntries = [...db.documents.entries()].filter(([path]) => path.includes("/platform_activity_events/"));
  assert.equal(activityEntries.length, 1, "replayed operations must not duplicate the activity event");
  const activity = activityEntries[0][1];
  assert.equal(activity.action, "fees_collected");
  assert.equal(activity.summary.includes("BDT 2,000"), true);

  await assert.rejects(handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "owner_delete_payment", operationId: "grouped-delete-0001",
    paymentId: first.payments[1].id, reason: "Attempt to change one receipt line",
  } }));
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/payments/")).length, 2);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/receipts/")).length, 1);
  const activityAfterFailedDelete = [...db.documents.entries()].filter(([path]) => path.includes("/platform_activity_events/"));
  assert.equal(activityAfterFailedDelete.length, 1, "a rejected operation must not write an activity event");
});

test("owner can correct every line of one grouped receipt atomically", async () => {
  const db = seededDb();
  const handler = createFinancialLedgerHandler({ db });
  const collected = await handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "collect_grouped_payment", operationId: "grouped-edit-source-0001",
    studentId: "s", paymentMethod: "cash", paymentDateMs: 1_788_000_000_000,
    allocations: [
      { batchId: "b", feePeriod: "Jun 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
      { batchId: "b", feePeriod: "Jul 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
    ],
  } });

  const corrected = await handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "owner_edit_grouped_payment", operationId: "grouped-edit-0001",
    receiptNumber: collected.receipts[0].receiptNumber,
    paymentMethod: "bkash", paymentDateMs: 1_789_000_000_000,
    note: "Corrected deposit", reason: "Cash count corrected",
    allocations: [
      { paymentId: collected.payments[0].id, amount: 900 },
      { paymentId: collected.payments[1].id, amount: 800 },
    ],
  } });

  assert.equal(corrected.payments.length, 2);
  assert.equal(corrected.receipts.length, 1);
  assert.deepEqual(corrected.payments.map((payment) => payment.amount).sort(), [800, 900]);
  assert.equal(corrected.payments.every((payment) => payment.paymentMethod === "bkash"), true);
  assert.equal(corrected.receipts[0].paidAmount, 1700);
  assert.equal(corrected.receipts[0].dueAmount, 300);
  assert.deepEqual(
    corrected.fees.map((fee) => fee.dueAmount).sort(),
    [100, 200],
  );
  const receipt = db.documents.get(`institutes/i/receipts/${corrected.receipts[0].id}`);
  assert.equal(receipt.lineItems.reduce((sum, line) => sum + line.collectedAmount, 0), 1700);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/grouped_payment_corrections/")).length, 1);
});

test("invalid grouped correction cannot partially change any month", async () => {
  const db = seededDb();
  const handler = createFinancialLedgerHandler({ db });
  const collected = await handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "collect_grouped_payment", operationId: "grouped-invalid-edit-source-0001",
    studentId: "s", paymentMethod: "cash", paymentDateMs: 1_788_000_000_000,
    allocations: [
      { batchId: "b", feePeriod: "Jun 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
      { batchId: "b", feePeriod: "Jul 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
    ],
  } });

  await assert.rejects(handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "owner_edit_grouped_payment", operationId: "grouped-invalid-edit-0001",
    receiptNumber: collected.receipts[0].receiptNumber,
    paymentMethod: "bkash", paymentDateMs: 1_789_000_000_000,
    reason: "Invalid correction",
    allocations: [
      { paymentId: collected.payments[0].id, amount: 900 },
      { paymentId: collected.payments[1].id, amount: 1001 },
    ],
  } }));

  const payments = collected.payments.map((payment) => db.documents.get(`institutes/i/payments/${payment.id}`));
  assert.deepEqual(payments.map((payment) => payment.amount), [1000, 1000]);
  assert.equal(payments.every((payment) => payment.paymentMethod === "cash"), true);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/grouped_payment_corrections/")).length, 0);
});

test("an invalid grouped allocation leaves every month untouched", async () => {
  const db = seededDb();
  const handler = createFinancialLedgerHandler({ db });

  await assert.rejects(handler({ auth: { uid: "owner" }, data: {
    instituteId: "i", action: "collect_grouped_payment", operationId: "invalid-grouped-0001",
    studentId: "s", paymentMethod: "cash", paymentDateMs: 1_788_000_000_000,
    allocations: [
      { batchId: "b", feePeriod: "Jun 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1000 },
      { batchId: "b", feePeriod: "Jul 2026", feeType: "monthly_fee", dueDateMs: 1_788_000_000_000, baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0, amount: 1001 },
    ],
  } }));

  assert.equal([...db.documents.keys()].filter((key) => key.includes("/fees/")).length, 0);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/payments/")).length, 0);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/receipts/")).length, 0);
});
