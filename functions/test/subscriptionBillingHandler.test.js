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

test("server creates a canonical quote and rejects a duplicate payment reference", async () => {
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
      transactionReference: "ABCD-123456",
    },
  });
  assert.equal(result.request.amountPaid, 5394.6);
  assert.equal(result.request.transactionLast4, "3456");
  const storedRequest = db.documents.get(`subscriptionRequests/${result.request.requestId}`);
  assert.equal(storedRequest.transactionReference, undefined);
  assert.equal(typeof storedRequest.paymentReferenceHash, "string");

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
        transactionReference: "ABCD123456",
      },
    }),
    (error) => error.code === "already-exists",
  );
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
      transactionReference: "NAGAD-ABC12345",
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
        transactionReference: "COUNT-ABC12345",
      },
    }),
    (error) => error.code === "failed-precondition" && error.message.includes("501 active students"),
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
        transactionReference: "FREE-ABC12345",
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
