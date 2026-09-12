"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { createBatchEnrollmentHandler } = require("../src/batchEnrollment");

class Ref {
  constructor(path) { this.path = path; }
  collection(name) { return new Query(`${this.path}/${name}`); }
}
class Query extends Ref {
  constructor(path, filters = []) { super(path); this.filters = filters; }
  doc(id) { return new Ref(`${this.path}/${id}`); }
  where(key, op, value) { assert.equal(op, "=="); return new Query(this.path, [...this.filters, [key, value]]); }
}
function snapshot(ref, data) {
  return { ref, id: ref.path.split("/").at(-1), exists: data !== undefined,
    data: () => structuredClone(data), get: key => data?.[key] };
}
class Db {
  constructor(seed) { this.rows = new Map(Object.entries(seed)); this.queue = Promise.resolve(); }
  collection(name) { return new Query(name); }
  runTransaction(callback) {
    const run = this.queue.then(async () => {
      const draft = new Map(structuredClone([...this.rows]));
      let writing = false;
      const result = await callback({
        get: async ref => {
          assert.equal(writing, false, "all reads must precede writes");
          if (!(ref instanceof Query)) return snapshot(ref, draft.get(ref.path));
          const docs = [...draft].filter(([path, data]) => path.startsWith(`${ref.path}/`) &&
            path.split("/").length === ref.path.split("/").length + 1 && ref.filters.every(([key, value]) => data[key] === value))
            .map(([path, data]) => snapshot(new Ref(path), data));
          return { docs, empty: docs.length === 0 };
        },
        create: (ref, value) => { writing = true; assert.equal(draft.has(ref.path), false); draft.set(ref.path, structuredClone(value)); },
        update: (ref, value) => { writing = true; assert.equal(draft.has(ref.path), true); draft.set(ref.path, { ...draft.get(ref.path), ...value }); },
      });
      this.rows = draft;
      return result;
    });
    this.queue = run.catch(() => {});
    return run;
  }
}
const date = Date.UTC(2026, 7, 1);
function fixture(mode = "course") {
  const db = new Db({
    "institutes/i": { isActive: true, subscriptionStatus: "active", currentPeriodEndMs: Date.now() + 86400000 },
    "institutes/i/students/s": { status: "active", admissionDateMs: date },
    "institutes/i/batches/b": { status: "active", billingMode: mode, monthlyFeeAmount: mode === "monthly" ? 1000 : 0, courseFeeAmount: 5000 },
  });
  const handler = createBatchEnrollmentHandler({ db,
    authorize: async (auth, tenant, permission) => { assert.equal(auth.uid, "owner"); assert.equal(tenant, "i"); assert.equal(permission, "manage_batch"); },
    proratedMonthlyTerms: () => ({ firstMonthFeePeriod: "Aug 2026", firstMonthFeeAmount: 1000 }),
  });
  const request = { auth: { uid: "owner" }, data: { instituteId: "i", studentId: "s", batchId: "b", enrollmentId: "e", operationId: "op", enrollmentStartMs: date, admissionDateLinked: true } };
  return { db, handler, request };
}
test("course assignment atomically returns enrollment and one canonical charge", async () => {
  const { db, handler, request } = fixture();
  const result = await handler(request);
  assert.equal(result.fee.totalAmount, 5000);
  assert.equal(result.enrollment.admissionDateLinked, true);
  assert.equal(db.rows.has(`institutes/i/fees/${result.fee.id}`), true);
  assert.equal(db.rows.has("institutes/i/batch_students/e"), true);
  const activityPaths = [...db.rows.keys()].filter((path) => path.startsWith("institutes/i/platform_activity_events/"));
  assert.equal(activityPaths.length, 1);
  const activity = db.rows.get(activityPaths[0]);
  assert.equal(activity.action, "student_assigned_to_batch");
  assert.equal(activity.actorUid, "owner");
  assert.equal(activity.targetType, "student");
  assert.equal(activity.targetId, "s");
  assert.equal(activity.summary.includes("assigned"), true);
});
test("monthly assignment freezes terms without creating a course charge", async () => {
  const { handler, request } = fixture("monthly");
  const result = await handler(request);
  assert.equal(result.fee, null);
  assert.equal(result.enrollment.firstMonthFeePeriod, "Aug 2026");
});
test("invalid course never leaves a partial enrollment", async () => {
  const { db, handler, request } = fixture();
  db.rows.get("institutes/i/batches/b").courseFeeAmount = 0;
  await assert.rejects(handler(request));
  assert.equal(db.rows.has("institutes/i/batch_students/e"), false);
});
test("retry returns current paid fee, not the stale original response", async () => {
  const { db, handler, request } = fixture();
  const first = await handler(request);
  Object.assign(db.rows.get(`institutes/i/fees/${first.fee.id}`), { paidAmount: 5000, dueAmount: 0, status: "paid" });
  const retry = await handler(request);
  assert.equal(retry.fee.paidAmount, 5000);
  assert.equal([...db.rows.keys()].filter(x => x.startsWith("institutes/i/fees/")).length, 1);
});
test("same request after restart reconciles rather than making another enrollment", async () => {
  const { db, handler, request } = fixture();
  await handler(request);
  const second = await handler({ ...request, data: { ...request.data, operationId: "new-op", enrollmentId: "new-e" } });
  assert.equal(second.enrollment.id, "e");
  assert.equal(db.rows.has("institutes/i/batch_students/new-e"), false);
  const activityCount = [...db.rows.keys()].filter((path) => path.startsWith("institutes/i/platform_activity_events/")).length;
  assert.equal(activityCount, 1);
});
test("concurrent duplicate intentions produce only one active membership in serialized transactions", async () => {
  const { db, handler, request } = fixture();
  await Promise.all([handler(request), handler({ ...request, data: { ...request.data, operationId: "op2", enrollmentId: "e2" } })]);
  assert.equal([...db.rows.keys()].filter(x => x.startsWith("institutes/i/batch_students/")).length, 1);
});
test("later assignment cannot claim admission linkage", async () => {
  const { db, handler, request } = fixture();
  db.rows.set("institutes/i/batch_students/old", { studentId: "s", batchId: "old", status: "removed" });
  await assert.rejects(handler(request), { code: "failed-precondition" });
  const result = await handler({ ...request, data: { ...request.data, admissionDateLinked: false } });
  assert.equal(result.enrollment.admissionDateLinked, false);
});
test("expired institute and future date fail closed", async () => {
  const { db, handler, request } = fixture();
  await assert.rejects(handler({ ...request, data: { ...request.data, enrollmentStartMs: Date.now() + 86400000 } }), { code: "invalid-argument" });
  db.rows.get("institutes/i").currentPeriodEndMs = 1;
  await assert.rejects(handler(request), { code: "failed-precondition" });
});

test("assignment date cannot precede the student admission date", async () => {
  const { handler, request } = fixture("monthly");
  await assert.rejects(handler({
    ...request,
    data: { ...request.data, enrollmentStartMs: date - 86400000, admissionDateLinked: false },
  }), { code: "failed-precondition" });
});
