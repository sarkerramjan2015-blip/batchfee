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
  constructor(db, path, filters = []) { this.db = db; this.path = path; this.filters = filters; }
  where(field, operator, value) { return new Query(this.db, this.path, [...this.filters, [field, operator, value]]); }
  rows() {
    const depth = this.path.split("/").length + 1;
    return [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value))
      .map(([path, data]) => new Snapshot(new Document(this.db, path), data));
  }
}

class Collection extends Query { doc(id) { return new Document(this.db, `${this.path}/${id}`); } }
class Document {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new Collection(this.db, `${this.path}/${name}`); }
}

class Db {
  constructor(seed) { this.documents = new Map(Object.entries(seed).map(([path, data]) => [path, structuredClone(data)])); }
  collection(name) { return new Collection(this, name); }
  async runTransaction(callback) {
    const transaction = {
      get: async (target) => target instanceof Query
        ? { docs: target.rows(), empty: target.rows().length === 0 }
        : new Snapshot(target, this.documents.get(target.path)),
      create: (ref, data) => this.documents.set(ref.path, structuredClone(data)),
      update: (ref, data) => this.documents.set(ref.path, { ...this.documents.get(ref.path), ...structuredClone(data) }),
    };
    return callback(transaction);
  }
}

test("course admission correction leaves its course contract unchanged", async () => {
  const original = timestamp(2026, 8, 1);
  const corrected = timestamp(2026, 7, 10);
  const db = new Db({
    "institutes/a": { isActive: true, currentPlanId: "plan_spark", subscriptionStatus: "active", currentPeriodEndMs: Date.now() + 86400000 },
    "app_users/owner": { instituteId: "a", role: "InstituteOwner", status: "active" },
    "institutes/a/students/s": { admissionDateMs: original },
    "institutes/a/batches/course": { billingMode: "course", monthlyFeeAmount: 0, courseFeeAmount: 5000 },
    "institutes/a/batch_students/e": { studentId: "s", batchId: "course", status: "active", joinedAtMs: original, firstMonthFeePeriod: null },
  });
  await createFinancialLedgerHandler({ db })({ auth: { uid: "owner" }, data: {
    instituteId: "a", studentId: "s", action: "update_student_admission_date", admissionDateMs: corrected, operationId: "course-admission-0001",
  } });
  assert.equal(db.documents.get("institutes/a/students/s").admissionDateMs, corrected);
  assert.equal(db.documents.get("institutes/a/batch_students/e").joinedAtMs, original);
  assert.equal(db.documents.get("institutes/a/batch_students/e").firstMonthFeePeriod, null);
});

test("same-month independent assignment is preserved during admission correction", async () => {
  const db = new Db({
    "institutes/a": { isActive: true, currentPlanId: "plan_spark", subscriptionStatus: "active", currentPeriodEndMs: Date.now() + 86400000 },
    "app_users/owner": { instituteId: "a", role: "InstituteOwner", status: "active" },
    "institutes/a/students/s": { admissionDateMs: timestamp(2026, 8, 1) },
    "institutes/a/batches/b": { billingMode: "monthly", monthlyFeeAmount: 1000 },
    "institutes/a/batch_students/e": { studentId: "s", batchId: "b", status: "active", joinedAtMs: timestamp(2026, 8, 20), firstMonthFeePeriod: "Sep 2026", firstMonthFeeAmount: 367, admissionDateLinked: false },
  });
  await createFinancialLedgerHandler({ db })({ auth: { uid: "owner" }, data: {
    instituteId: "a", studentId: "s", action: "update_student_admission_date", admissionDateMs: timestamp(2026, 7, 10), operationId: "independent-admission-0001",
  } });
  assert.equal(db.documents.get("institutes/a/batch_students/e").firstMonthFeePeriod, "Sep 2026");
  assert.equal(db.documents.get("institutes/a/batch_students/e").firstMonthFeeAmount, 367);
});

function timestamp(year, monthIndex, day) {
  return Date.UTC(year, monthIndex, day, 6, 0, 0);
}

function billingPeriod(offset = 0) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Dhaka",
    month: "short",
    year: "numeric",
  }).formatToParts(new Date());
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const month = months.indexOf(parts.find((part) => part.type === "month").value);
  const year = Number(parts.find((part) => part.type === "year").value);
  const key = year * 12 + month + offset;
  return `${months[key % 12]} ${Math.floor(key / 12)}`;
}

function customFeeFixture(extra = {}) {
  const current = billingPeriod();
  return new Db({
    "institutes/a": {
      isActive: true, currentPlanId: "plan_spark", subscriptionStatus: "active",
      currentPeriodEndMs: Date.now() + 86400000,
    },
    "app_users/owner": { instituteId: "a", role: "InstituteOwner", status: "active" },
    "institutes/a/students/s": { admissionDateMs: Date.now() - 86400000 },
    "institutes/a/batches/b": { billingMode: "monthly", monthlyFeeAmount: 1000 },
    "institutes/a/batch_students/e": {
      studentId: "s", batchId: "b", status: "active", joinedAtMs: Date.now() - 86400000,
      firstMonthFeePeriod: current, firstMonthFeeAmount: 1000, admissionDateLinked: false,
    },
    ...extra,
  });
}

test("custom fee returns the canonical future period and updates only unpaid fees", async () => {
  const next = billingPeriod(1);
  const db = customFeeFixture({
    "institutes/a/fees/next": {
      instituteId: "a", studentId: "s", batchId: "b", feePeriod: next,
      feeType: "monthly_fee", baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0,
      totalAmount: 1000, paidAmount: 0, dueAmount: 1000, status: "unpaid",
      createdAtMs: Date.now(), updatedAtMs: Date.now(), ledgerVersion: 1,
    },
  });
  const result = await createFinancialLedgerHandler({ db })({ auth: { uid: "owner" }, data: {
    instituteId: "a", enrollmentId: "e", studentId: "s", batchId: "b",
    action: "set_custom_monthly_fee", customMonthlyFeeAmount: 700,
    customFeeReason: "Scholarship", effectiveFromPeriod: next,
    operationId: "custom-fee-future-0001",
  } });
  assert.equal(result.metadata.customFeePolicy.effectivePeriod, next);
  assert.equal(result.metadata.customFeePolicy.customMonthlyFeeAmount, 700);
  assert.equal(db.documents.get("institutes/a/fees/next").totalAmount, 700);
  assert.match(db.documents.get("institutes/a/batch_students/e").customFeePolicyTimeline, /=700$/);
});

test("paid selected period rejects a custom fee without changing its receipt history", async () => {
  const current = billingPeriod();
  const originalFee = {
    instituteId: "a", studentId: "s", batchId: "b", feePeriod: current,
    feeType: "monthly_fee", baseAmount: 1000, discountAmount: 0, lateFeeAmount: 0,
    totalAmount: 1000, paidAmount: 200, dueAmount: 800, status: "partially_paid",
    createdAtMs: Date.now(), updatedAtMs: Date.now(), ledgerVersion: 1,
  };
  const db = customFeeFixture({
    "institutes/a/fees/current": originalFee,
    "institutes/a/payments/pay": { feeId: "current", amount: 200, status: "completed" },
  });
  await assert.rejects(createFinancialLedgerHandler({ db })({ auth: { uid: "owner" }, data: {
    instituteId: "a", enrollmentId: "e", studentId: "s", batchId: "b",
    action: "set_custom_monthly_fee", customMonthlyFeeAmount: 700,
    customFeeReason: "Scholarship", effectiveFromPeriod: current,
    operationId: "custom-fee-paid-00001",
  } }), { code: "failed-precondition" });
  assert.deepEqual(db.documents.get("institutes/a/fees/current"), originalFee);
  assert.equal(db.documents.get("institutes/a/batch_students/e").customFeePolicyTimeline, undefined);
});

test("future batch-fee restore preserves the earlier custom policy in its timeline", async () => {
  const current = billingPeriod();
  const next = billingPeriod(1);
  const db = customFeeFixture();
  const handler = createFinancialLedgerHandler({ db });
  await handler({ auth: { uid: "owner" }, data: {
    instituteId: "a", enrollmentId: "e", studentId: "s", batchId: "b",
    action: "set_custom_monthly_fee", customMonthlyFeeAmount: 700,
    customFeeReason: "Scholarship", effectiveFromPeriod: current,
    operationId: "custom-fee-start-00001",
  } });
  const result = await handler({ auth: { uid: "owner" }, data: {
    instituteId: "a", enrollmentId: "e", studentId: "s", batchId: "b",
    action: "set_custom_monthly_fee", customMonthlyFeeAmount: null,
    customFeeReason: null, effectiveFromPeriod: next,
    operationId: "custom-fee-reset-00001",
  } });
  assert.equal(result.metadata.customFeePolicy.customFeePolicyTimeline, `${current}=700|${next}=BATCH`);
});

test("reconciliation cancels only fully unpaid months before the contractual start", async () => {
  const now = Date.now();
  const db = new Db({
    "institutes/institute-a": {
      isActive: true, currentPlanId: "plan_spark", subscriptionStatus: "active",
      currentPeriodEndMs: now + 31 * 24 * 60 * 60 * 1000,
    },
    "app_users/owner-a": { instituteId: "institute-a", role: "InstituteOwner", status: "active" },
    "institutes/institute-a/students/student-a": { admissionDateMs: timestamp(2026, 5, 1) },
    "institutes/institute-a/batch_students/enrollment-a": {
      studentId: "student-a", batchId: "batch-a", status: "active", joinedAtMs: timestamp(2026, 6, 25),
    },
    "institutes/institute-a/fees/apr": { studentId: "student-a", batchId: "batch-a", feePeriod: "Apr 2026", feeType: "monthly_fee", totalAmount: 1000, dueAmount: 1000 },
    "institutes/institute-a/fees/may": { studentId: "student-a", batchId: "batch-a", feePeriod: "May 2026", feeType: "monthly_fee", totalAmount: 1000, dueAmount: 1000 },
    "institutes/institute-a/fees/jun": { studentId: "student-a", batchId: "batch-a", feePeriod: "Jun 2026", feeType: "monthly_fee", totalAmount: 1000, dueAmount: 1000 },
    "institutes/institute-a/fees/jul": { studentId: "student-a", batchId: "batch-a", feePeriod: "Jul 2026", feeType: "monthly_fee", totalAmount: 1000, dueAmount: 1000 },
    "institutes/institute-a/fees/paid-apr": { studentId: "student-a", batchId: "batch-a", feePeriod: "Apr 2026", feeType: "monthly_fee", totalAmount: 1000, dueAmount: 0 },
    "institutes/institute-a/payments/payment-a": { feeId: "paid-apr", amount: 1000, status: "completed" },
  });

  const handler = createFinancialLedgerHandler({ db });
  const result = await handler({
    auth: { uid: "owner-a" },
    data: {
      instituteId: "institute-a",
      studentId: "student-a",
      action: "reconcile_invalid_monthly_fees",
      operationId: "reconcile-invalid-months-0001",
    },
  });

  assert.deepEqual(result.fees.map((fee) => fee.id).sort(), ["apr", "may"]);
  assert.equal(db.documents.get("institutes/institute-a/fees/apr").status, "cancelled");
  assert.equal(db.documents.get("institutes/institute-a/fees/may").dueAmount, 0);
  assert.equal(db.documents.get("institutes/institute-a/fees/jun").status, undefined);
  assert.equal(db.documents.get("institutes/institute-a/fees/jul").status, undefined);
  assert.equal(db.documents.get("institutes/institute-a/fees/paid-apr").cancelledAtMs, undefined);
});

test("admission date edit updates its linked enrollment but preserves a shifted batch start", async () => {
  const now = Date.now();
  const originalAdmission = timestamp(2026, 5, 1);
  const correctedAdmission = timestamp(2026, 4, 10);
  const originalJoined = timestamp(2026, 5, 20);
  const shiftedJoined = timestamp(2026, 7, 15);
  const db = new Db({
    "institutes/institute-a": {
      isActive: true, currentPlanId: "plan_spark", subscriptionStatus: "active",
      currentPeriodEndMs: now + 31 * 24 * 60 * 60 * 1000,
    },
    "app_users/owner-a": { instituteId: "institute-a", role: "InstituteOwner", status: "active" },
    "institutes/institute-a/students/student-a": { admissionDateMs: originalAdmission },
    "institutes/institute-a/batches/batch-a": { monthlyFeeAmount: 1000 },
    "institutes/institute-a/batch_students/enrollment-a": {
      studentId: "student-a", batchId: "batch-a", status: "active",
      joinedAtMs: originalJoined, firstMonthFeePeriod: "Jun 2026", firstMonthFeeAmount: 1000,
      admissionDateLinked: true,
    },
    "institutes/institute-a/batch_students/enrollment-shifted": {
      studentId: "student-a", batchId: "batch-b", status: "active",
      joinedAtMs: shiftedJoined, firstMonthFeePeriod: "Aug 2026", firstMonthFeeAmount: 567,
    },
  });

  const handler = createFinancialLedgerHandler({ db });
  await handler({
    auth: { uid: "owner-a" },
    data: {
      instituteId: "institute-a",
      studentId: "student-a",
      admissionDateMs: correctedAdmission,
      action: "update_student_admission_date",
      operationId: "update-admission-date-0001",
    },
  });

  const linked = db.documents.get("institutes/institute-a/batch_students/enrollment-a");
  const shifted = db.documents.get("institutes/institute-a/batch_students/enrollment-shifted");
  assert.equal(db.documents.get("institutes/institute-a/students/student-a").admissionDateMs, correctedAdmission);
  assert.equal(linked.firstMonthFeePeriod, "May 2026");
  assert.equal(linked.joinedAtMs, originalJoined);
  assert.equal(shifted.firstMonthFeePeriod, "Aug 2026");
  assert.equal(shifted.joinedAtMs, shiftedJoined);
});
