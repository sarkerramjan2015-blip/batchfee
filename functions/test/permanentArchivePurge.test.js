"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  createPermanentBatchPurgeHandler,
  createPermanentStaffPurgeHandler,
} = require("../src/permanentArchivePurge");

class Snapshot {
  constructor(ref, data) {
    this.ref = ref;
    this.id = ref.path.split("/").at(-1);
    this.exists = data !== undefined;
    this._data = data === undefined ? undefined : structuredClone(data);
  }
  data() { return this._data === undefined ? undefined : structuredClone(this._data); }
  get(field) { return this._data && this._data[field]; }
}

class Query {
  constructor(db, path, filters = [], maxResults = Infinity) {
    this.db = db; this.path = path; this.filters = filters; this.maxResults = maxResults;
  }
  where(field, operator, value) { return new Query(this.db, this.path, [...this.filters, [field, operator, value]], this.maxResults); }
  limit(maxResults) { return new Query(this.db, this.path, this.filters, maxResults); }
  async get() {
    const depth = this.path.split("/").length + 1;
    const docs = [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value))
      .slice(0, this.maxResults)
      .map(([path, data]) => new Snapshot(new Document(this.db, path), data));
    return { docs, empty: docs.length === 0 };
  }
}

class Collection extends Query { doc(id) { return new Document(this.db, `${this.path}/${id}`); } }
class Document {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new Collection(this.db, `${this.path}/${name}`); }
  async get() { return new Snapshot(this, this.db.documents.get(this.path)); }
  async delete() { this.db.documents.delete(this.path); }
}
class FakeDb {
  constructor(documents) { this.documents = new Map(Object.entries(documents)); }
  collection(name) { return new Collection(this, name); }
  batch() {
    const deleted = [];
    return { delete: (ref) => deleted.push(ref.path), commit: async () => deleted.forEach((path) => this.documents.delete(path)) };
  }
}

function liveInstitute() {
  return { currentPlanId: "plan_free_trial", subscriptionStatus: "trial", currentPeriodEndMs: Date.now() + 60_000, isActive: true };
}

test("batch purge clears only the archived batch's linked records", async () => {
  const db = new FakeDb({
    "institutes/institute-a": liveInstitute(),
    "institutes/institute-a/batches/batch-a": { name: "HSC 2027", status: "archived", archivedAtMs: 1 },
    "institutes/institute-a/students/student-a": { fullName: "Still Here", status: "active" },
    "institutes/institute-a/fees/fee-a": { batchId: "batch-a", studentId: "student-a" },
    "institutes/institute-a/payments/payment-a": { feeId: "fee-a" },
    "institutes/institute-a/receipts/receipt-a": { feeId: "fee-a" },
    "institutes/institute-a/payment_reversals/reversal-a": { feeId: "fee-a" },
    "institutes/institute-a/attendance/attendance-a": { batchId: "batch-a" },
    "institutes/institute-a/homework/homework-a": { batchId: "batch-a" },
    "institutes/institute-a/homework_submissions/submission-a": { homeworkId: "homework-a" },
    "institutes/institute-a/batches/batch-b": { name: "SSC 2027", status: "active" },
    "institutes/institute-a/fees/fee-b": { batchId: "batch-b", studentId: "student-a" },
  });
  const handler = createPermanentBatchPurgeHandler({
    db, bucket: { name: "bucket", file: () => ({ delete: async () => {} }) },
  });

  const result = await handler({ auth: { uid: "institute-a" }, data: { instituteId: "institute-a", batchId: "batch-a" } });
  assert.deepEqual(result, { batchId: "batch-a", permanentlyDeleted: true });
  [
    "institutes/institute-a/batches/batch-a", "institutes/institute-a/fees/fee-a",
    "institutes/institute-a/payments/payment-a", "institutes/institute-a/receipts/receipt-a",
    "institutes/institute-a/payment_reversals/reversal-a", "institutes/institute-a/attendance/attendance-a",
    "institutes/institute-a/homework/homework-a", "institutes/institute-a/homework_submissions/submission-a",
  ].forEach((path) => assert.equal(db.documents.has(path), false, path));
  [
    "institutes/institute-a/students/student-a", "institutes/institute-a/batches/batch-b", "institutes/institute-a/fees/fee-b",
  ].forEach((path) => assert.equal(db.documents.has(path), true, path));
});

test("managed institute owner can purge when the institute document uses a different ID", async () => {
  const db = new FakeDb({
    "institutes/institute-a": liveInstitute(),
    "institutes/institute-a/batches/batch-a": { name: "HSC 2027", status: "archived", archivedAtMs: 1 },
    "app_users/owner-auth-uid": { role: "InstituteOwner", instituteId: "institute-a", status: "active" },
  });
  const handler = createPermanentBatchPurgeHandler({
    db, bucket: { name: "bucket", file: () => ({ delete: async () => {} }) },
  });

  const result = await handler({
    auth: { uid: "owner-auth-uid" },
    data: { instituteId: "institute-a", batchId: "batch-a" },
  });

  assert.deepEqual(result, { batchId: "batch-a", permanentlyDeleted: true });
  assert.equal(db.documents.has("institutes/institute-a/batches/batch-a"), false);
});

test("staff purge deletes the linked Firebase account without a typed name", async () => {
  const db = new FakeDb({
    "institutes/institute-a": liveInstitute(),
    "institutes/institute-a/staffs/staff-a": { fullName: "Archive Staff", status: "archived", archivedAtMs: 1 },
    "institutes/institute-a/staff_attendance/attendance-a": { staffId: "staff-a" },
    "institutes/institute-a/salaries/salary-a": { staffId: "staff-a" },
    "institutes/institute-a/audit_logs/log-a": { userId: "staff-a" },
    "app_users/staff-a": { role: "Staff" },
  });
  const deletedUsers = [];
  const handler = createPermanentStaffPurgeHandler({
    db,
    adminAuth: { deleteUser: async (uid) => deletedUsers.push(uid) },
    bucket: { name: "bucket", file: () => ({ delete: async () => {} }) },
  });
  const baseRequest = { auth: { uid: "institute-a" }, data: { instituteId: "institute-a", staffId: "staff-a" } };
  const result = await handler(baseRequest);
  assert.deepEqual(result, { staffId: "staff-a", permanentlyDeleted: true });
  [
    "institutes/institute-a/staffs/staff-a", "institutes/institute-a/staff_attendance/attendance-a",
    "institutes/institute-a/salaries/salary-a", "institutes/institute-a/audit_logs/log-a", "app_users/staff-a",
  ].forEach((path) => assert.equal(db.documents.has(path), false, path));
  assert.deepEqual(deletedUsers, ["staff-a"]);
});
