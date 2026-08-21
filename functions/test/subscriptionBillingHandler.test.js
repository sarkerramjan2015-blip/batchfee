"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createSubscriptionBillingHandler } = require("../src/subscriptionBilling");

const DELETE = Symbol("delete");

class FakeSnapshot {
  constructor(ref, data) {
    this.ref = ref;
    this.exists = data !== undefined;
    this._data = data === undefined ? undefined : structuredClone(data);
  }

  data() { return this._data === undefined ? undefined : structuredClone(this._data); }
  get(field) { return this._data && this._data[field]; }
}

class FakeQuery {
  constructor(db, path, filters = [], maxResults = Infinity) {
    this.db = db;
    this.path = path;
    this.filters = filters;
    this.maxResults = maxResults;
  }

  where(field, operator, value) {
    return new FakeQuery(this.db, this.path, [...this.filters, [field, operator, value]], this.maxResults);
  }

  limit(maxResults) { return new FakeQuery(this.db, this.path, this.filters, maxResults); }
  count() { return new FakeAggregateQuery(this); }

  snapshots() {
    const pathDepth = this.path.split("/").length + 1;
    return [...this.db.documents.entries()]
      .filter(([path, data]) => path.startsWith(`${this.path}/`) && path.split("/").length === pathDepth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) =>
        operator === "==" && data[field] === value,
      ))
      .slice(0, this.maxResults)
      .map(([path, data]) => new FakeSnapshot(new FakeDocument(this.db, path), data));
  }
}

class FakeAggregateQuery {
  constructor(query) { this.query = query; }
  snapshot() { return { data: () => ({ count: this.query.snapshots().length }) }; }
}

class FakeCollection extends FakeQuery {
  doc(id) { return new FakeDocument(this.db, `${this.path}/${id}`); }
}

class FakeDocument {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new FakeCollection(this.db, `${this.path}/${name}`); }
}

function applyUpdate(current, update) {
  const next = { ...current };
  Object.entries(update).forEach(([key, value]) => {
    if (value === DELETE) delete next[key];
    else next[key] = structuredClone(value);
  });
  return next;
}

class FakeDb {
  constructor(seed) { this.documents = new Map(Object.entries(seed).map(([path, data]) => [path, structuredClone(data)])); }
  collection(name) { return new FakeCollection(this, name); }
  async runTransaction(callback) {
    const transaction = {
      get: async (target) => {
        if (target instanceof FakeAggregateQuery) return target.snapshot();
        if (target instanceof FakeQuery) {
          return { docs: target.snapshots(), empty: target.snapshots().length === 0 };
        }
        return new FakeSnapshot(target, this.documents.get(target.path));
      },
      create: (ref, data) => {
        if (this.documents.has(ref.path)) throw new Error(`Document exists: ${ref.path}`);
        this.documents.set(ref.path, structuredClone(data));
      },
      update: (ref, data) => {
        const current = this.documents.get(ref.path);
        if (!current) throw new Error(`Missing document: ${ref.path}`);
        this.documents.set(ref.path, applyUpdate(current, data));
      },
    };
    return callback(transaction);
  }
}

function seededDb(now) {
  return new FakeDb({
    "institutes/institute-a": {
      instituteName: "Institute A",
      ownerName: "Owner A",
      email: "owner-a@example.test",
      phone: "+8801700000000",
      isActive: true,
      currentPlanId: "plan_growth",
      subscriptionStatus: "active",
      trialEndDate: now + 5 * 24 * 60 * 60 * 1000,
      currentPeriodEndMs: now + 40 * 24 * 60 * 60 * 1000,
      studentLimit: 500,
      staffLimit: 13,
    },
    "app_users/super-admin": { role: "SuperAdmin", status: "active" },
    "subscription_plans/plan_growth": { name: "Growth", priceBdt: 999, maxStudents: 500, maxUsers: 13 },
  });
}

function handlerFor(db) {
  return createSubscriptionBillingHandler({
    db,
    FieldValue: { delete: () => DELETE },
  });
}

test("server stores a canonical sender number and keeps one pending request per institute", async () => {
  const db = seededDb(Date.now());
  const handler = handlerFor(db);
  const result = await handler({
    auth: { uid: "institute-a" },
    data: {
      action: "submit_request",
      instituteId: "institute-a",
      operationId: "sub_operation_00000001",
      requestedPlanId: "plan_growth",
      durationMonths: 6,
      paymentMethod: "bKash",
      senderPhone: "01710000000",
    },
  });
  assert.equal(result.request.amountPaid, 5394.6);
  assert.equal(result.request.senderPhone, "+8801710000000");
  assert.equal(result.request.studentLimitAtRequest, 500);
  const storedRequest = db.documents.get(`subscriptionRequests/${result.request.requestId}`);
  assert.equal(storedRequest.transactionReference, undefined);
  assert.equal(storedRequest.paymentReferenceHash, undefined);

  await assert.rejects(
    handler({
      auth: { uid: "institute-a" },
      data: {
        action: "submit_request",
        instituteId: "institute-a",
        operationId: "sub_operation_00000002",
        requestedPlanId: "plan_growth",
        durationMonths: 6,
        paymentMethod: "bkash",
        senderPhone: "+8801710000000",
      },
    }),
    (error) => error.code === "failed-precondition" && error.message.includes("waiting for review"),
  );

  await handler({
    auth: { uid: "super-admin" },
    data: {
      action: "reject_request",
      instituteId: "institute-a",
      operationId: "sub_operation_00000012",
      requestId: result.request.requestId,
    },
  });
  const renewal = await handler({
    auth: { uid: "institute-a" },
    data: {
      action: "submit_request",
      instituteId: "institute-a",
      operationId: "sub_operation_00000013",
      requestedPlanId: "plan_growth",
      durationMonths: 6,
      paymentMethod: "bkash",
      senderPhone: "01710000000",
    },
  });
  assert.equal(renewal.request.senderPhone, "+8801710000000");
});

test("approval preserves trial history, starts after paid access, and writes no student-fee receipt", async () => {
  const now = Date.now();
  const db = seededDb(now);
  const handler = handlerFor(db);
  const request = await handler({
    auth: { uid: "institute-a" },
    data: {
      action: "submit_request",
      instituteId: "institute-a",
      operationId: "sub_operation_00000003",
      requestedPlanId: "plan_growth",
      durationMonths: 1,
      paymentMethod: "nagad",
      senderPhone: "01810000000",
    },
  });
  const result = await handler({
    auth: { uid: "super-admin" },
    data: {
      action: "approve_request",
      instituteId: "institute-a",
      operationId: "sub_operation_00000004",
      requestId: request.request.requestId,
    },
  });
  const institute = db.documents.get("institutes/institute-a");
  assert.equal(institute.trialEndDate, now + 5 * 24 * 60 * 60 * 1000);
  assert.ok(institute.currentPeriodEndMs > now + 40 * 24 * 60 * 60 * 1000);
  assert.equal(institute.subscriptionStatus, "active");
  assert.equal(institute.studentLimit, 500);
  assert.equal(institute.staffLimit, 13);
  assert.ok(db.documents.has(`institutes/institute-a/subscription_receipts/${result.receipt.receiptId}`));
  assert.equal(
    [...db.documents.keys()].some((path) => path.startsWith("institutes/institute-a/receipts/")),
    false,
  );
});

test("request rejects a plan that cannot support legacy active students without an archive field", async () => {
  const db = seededDb(Date.now());
  for (let index = 0; index < 501; index += 1) {
    db.documents.set(`institutes/institute-a/students/student-${index}`, { status: "active" });
  }
  db.documents.set("institutes/institute-a/students/archived", { status: "archived", archivedAtMs: Date.now() });
  const handler = handlerFor(db);

  await assert.rejects(
    handler({
      auth: { uid: "institute-a" },
      data: {
        action: "submit_request",
        instituteId: "institute-a",
        operationId: "sub_operation_00000005",
        requestedPlanId: "plan_growth",
        durationMonths: 1,
        paymentMethod: "bkash",
        senderPhone: "01910000000",
      },
    }),
    (error) => error.code === "failed-precondition" && error.message.includes("501 active students"),
  );
});

test("a lower plan cannot reduce a live paid institute before its current period ends", async () => {
  const now = Date.now();
  const db = seededDb(now);
  db.documents.set("subscription_plans/plan_basic", {
    name: "Basic", priceBdt: 199, maxStudents: 50, maxUsers: 3,
  });
  const handler = handlerFor(db);

  const assertEarlyDowngrade = (error) => error.code === "failed-precondition" &&
    error.message.includes("current 500-student plan is still active");

  await assert.rejects(
    handler({
      auth: { uid: "institute-a" },
      data: {
        action: "submit_request",
        instituteId: "institute-a",
        operationId: "sub_operation_00000009",
        requestedPlanId: "plan_basic",
        durationMonths: 1,
        paymentMethod: "bkash",
        senderPhone: "01710000001",
      },
    }),
    assertEarlyDowngrade,
  );

  db.documents.set("subscriptionRequests/legacy-lower-plan", {
    instituteId: "institute-a",
    requestedPlanId: "plan_basic",
    planName: "Basic",
    durationMonths: 1,
    amountPaid: 199,
    quote: { monthlyPriceBdt: 199, amountBdt: 199, durationMonths: 1 },
    status: "pending",
  });
  await assert.rejects(
    handler({
      auth: { uid: "super-admin" },
      data: {
        action: "approve_request",
        instituteId: "institute-a",
        operationId: "sub_operation_00000010",
        requestId: "legacy-lower-plan",
      },
    }),
    assertEarlyDowngrade,
  );

  await assert.rejects(
    handler({
      auth: { uid: "super-admin" },
      data: {
        action: "manage_institute_subscription",
        instituteId: "institute-a",
        operationId: "sub_operation_00000011",
        newExpiryMs: now + 70 * 24 * 60 * 60 * 1000,
        studentLimit: 50,
        staffLimit: 3,
        planId: "plan_basic",
        isActive: true,
      },
    }),
    assertEarlyDowngrade,
  );
});

test("a Free Trial cannot be submitted as a paid subscription request", async () => {
  const db = seededDb(Date.now());
  const handler = handlerFor(db);

  await assert.rejects(
    handler({
      auth: { uid: "institute-a" },
      data: {
        action: "submit_request",
        instituteId: "institute-a",
        operationId: "sub_operation_00000006",
        requestedPlanId: "plan_free_trial",
        durationMonths: 1,
        paymentMethod: "bkash",
        senderPhone: "01610000000",
      },
    }),
    (error) => error.code === "failed-precondition" && error.message.includes("Free Trial"),
  );
});

test("platform trial management always persists the unlimited student sentinel", async () => {
  const now = Date.now();
  const db = seededDb(now);
  const handler = handlerFor(db);

  const result = await handler({
    auth: { uid: "super-admin" },
    data: {
      action: "manage_institute_subscription",
      instituteId: "institute-a",
      operationId: "sub_operation_00000007",
      newExpiryMs: now + 30 * 24 * 60 * 60 * 1000,
      studentLimit: 999999,
      staffLimit: 1,
      planId: "plan_free_trial",
      isActive: true,
    },
  });

  assert.equal(result.institute.studentLimit, 0);
  assert.equal(db.documents.get("institutes/institute-a").studentLimit, 0);
  assert.equal(db.documents.get("institutes/institute-a").subscriptionStatus, "trial");
});

test("super admin removes legacy and orphaned requests from the pending queue", async () => {
  const db = seededDb(Date.now());
  db.documents.set("subscriptionRequests/legacy-request", {
    instituteId: "institute-a",
    status: "pending",
    durationMonths: 1,
    amountPaid: 999,
  });
  db.documents.set("subscriptionRequests/orphan-request", {
    instituteId: "missing-institute",
    status: "pending",
    durationMonths: 1,
    amountPaid: 999,
    quote: { monthlyPriceBdt: 999, amountBdt: 999 },
  });
  db.documents.set("subscriptionRequests/current-request", {
    instituteId: "institute-a",
    status: "pending",
    durationMonths: 1,
    amountPaid: 999,
    quote: { monthlyPriceBdt: 999, amountBdt: 999 },
  });

  const handler = handlerFor(db);
  const request = {
    auth: { uid: "super-admin" },
    data: {
      action: "cleanup_invalid_requests",
      operationId: "sub_operation_00000008",
    },
  };
  const result = await handler(request);

  assert.equal(result.removedCount, 2);
  assert.equal(db.documents.get("subscriptionRequests/legacy-request").status, "invalid");
  assert.equal(db.documents.get("subscriptionRequests/orphan-request").status, "invalid");
  assert.equal(db.documents.get("subscriptionRequests/current-request").status, "pending");
  assert.deepEqual(await handler(request), result);
});
