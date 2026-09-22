"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  CONTRIBUTION_POLICY_VERSION,
  createQuestionBankFoundationHandler,
} = require("../src/questionBankFoundation");
const { createQuestionBankAdminHandler } = require("../src/questionBankAdmin");

// Minimal in-memory Firestore supporting equality/range where, orderBy, offset,
// limit, transactions, and collectionGroup scans for equality filters.
function memoryDb() {
  const records = new Map();
  const ref = (path) => ({
    path,
    collection: (name) => ({
      doc: (id) => ref(`${path}/${name}/${id}`),
      ...query(`collection:${path}/${name}`, []),
    }),
    get: async () => snapshot(path),
    set: async (data) => records.set(path, data),
    update: async (patch) => {
      if (!records.has(path)) throw new Error(`Missing document ${path}`);
      records.set(path, { ...records.get(path), ...patch });
    },
    delete: async () => records.delete(path),
  });
  const snapshot = (path) => ({
    exists: records.has(path),
    data: () => records.get(path),
    get: (field) => records.get(path) && records.get(path)[field],
  });

  function applyWhere(docs, field, operator, value) {
    return docs.filter((doc) => {
      const actual = doc.data[field];
      if (operator === "==") return actual === value;
      if (operator === "<=") return actual <= value;
      if (operator === "<") return actual < value;
      return true;
    });
  }

  // source: "collection:/path" for tenant collections or "group:name" for
  // collectionGroup scans across every institute path.
  const query = (source, clauses = []) => ({
    where: (field, operator, value) =>
      query(source, [...clauses, { type: "where", field, operator, value }]),
    orderBy: (field, direction) =>
      query(source, [...clauses, { type: "orderBy", field, direction }]),
    offset: (count) => query(source, [...clauses, { type: "offset", count }]),
    limit: (count) => query(source, [...clauses, { type: "limit", count }]),
    get: async () => {
      let docs;
      if (source.startsWith("collection:")) {
        const collectionPath = source.slice("collection:".length);
        docs = [...records.entries()]
          .filter(([path]) => path.startsWith(`${collectionPath}/`))
          .map(([path, data]) => ({ id: path.slice(path.lastIndexOf("/") + 1), data }));
      } else {
        const name = source.slice("group:".length);
        docs = [...records.entries()]
          .filter(([path]) => path.includes(`/${name}/`))
          .map(([path, data]) => ({ id: path.slice(path.lastIndexOf("/") + 1), data }));
      }
      for (const clause of clauses) {
        if (clause.type === "where") {
          docs = applyWhere(docs, clause.field, clause.operator, clause.value);
        }
      }
      const orderBy = clauses.filter((clause) => clause.type === "orderBy");
      if (orderBy.length) {
        const { field, direction } = orderBy[orderBy.length - 1];
        docs.sort((left, right) => {
          const result = (left.data[field] || 0) - (right.data[field] || 0);
          return direction === "desc" ? -result : result;
        });
      }
      let offset = 0;
      for (const clause of clauses) if (clause.type === "offset") offset = clause.count;
      let limit = docs.length;
      for (const clause of clauses) if (clause.type === "limit") limit = clause.count;
      docs = docs.slice(offset, offset + limit);
      return { docs: docs.map((doc) => ({ id: doc.id, data: () => doc.data, get: (field) => doc.data[field] })) };
    },
  });

  return {
    records,
    collection: (name) => ({ doc: (id) => ref(`${name}/${id}`) }),
    collectionGroup: (name) => query(`group:${name}`, []),
    runTransaction: async (work) => work({
      get: async (reference) => snapshot(reference.path),
      create: (reference, data) => {
        if (records.has(reference.path)) throw new Error(`Duplicate document ${reference.path}`);
        records.set(reference.path, data);
      },
      set: (reference, data) => records.set(reference.path, data),
      update: (reference, patch) => {
        if (!records.has(reference.path)) throw new Error(`Missing document ${reference.path}`);
        records.set(reference.path, { ...records.get(reference.path), ...patch });
      },
    }),
  };
}

function consentRecord(uid = "teacher-a") {
  return { aiTncAccepted: true, policyVersion: CONTRIBUTION_POLICY_VERSION };
}

function foundationHandler(db) {
  return createQuestionBankFoundationHandler({ db, authorize: async () => {}, now: () => 100_000 });
}

function adminHandler(db) {
  return createQuestionBankAdminHandler({
    db,
    authorizeRoot: async () => {},
    now: () => 200_000,
    randomId: () => "audit-token",
  });
}

function foundationRequest(data = {}) {
  return { auth: { uid: "teacher-a" }, data: { instituteId: "institute-a", ...data } };
}

function topupData(overrides = {}) {
  return {
    action: "request_topup",
    operationId: "topup_0001",
    amountPoisha: 100_00,
    paymentMethod: "bkash",
    senderNumber: "01712345678",
    ...overrides,
  };
}

test("top-up request enforces the BDT 50 minimum, the 1.8% fee, and a valid sender number", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_contribution_consents/teacher-a", consentRecord());
  const handler = foundationHandler(db);
  await assert.rejects(
    handler(foundationRequest(topupData({ amountPoisha: 4900 }))),
    { code: "invalid-argument" },
  );
  await assert.rejects(
    handler(foundationRequest(topupData({ senderNumber: "999999" }))),
    { code: "invalid-argument" },
  );
  await assert.rejects(
    handler(foundationRequest(topupData({ paymentMethod: "rocket" }))),
    { code: "invalid-argument" },
  );
  const result = await handler(foundationRequest(topupData()));
  assert.equal(result.topupPolicy.pendingRequest.status, "pending");
  assert.equal(result.topupPolicy.pendingRequest.amountPoisha, 100_00);
  assert.equal(result.topupPolicy.pendingRequest.feePoisha, 180);
  assert.equal(result.topupPolicy.pendingRequest.payablePoisha, 101_80);
  assert.equal(result.topupPolicy.pendingRequest.paymentMethod, "bkash");
  assert.equal(result.topupPolicy.pendingRequest.senderNumber, "+8801712345678");
  const stored = db.records.get("institutes/institute-a/question_bank_topup_requests/topup_0001");
  assert.equal(stored.status, "pending");
  assert.equal(stored.kind, "history");
  assert.equal(stored.senderNumber, "+8801712345678");
  // Only one pending request at a time.
  await assert.rejects(
    handler(foundationRequest(topupData({ operationId: "topup_0002", amountPoisha: 50_00 }))),
    { code: "failed-precondition" },
  );
  // Replaying the same operation is idempotent.
  const replay = await handler(foundationRequest(topupData()));
  assert.equal(replay.topupPolicy.pendingRequest.status, "pending");
});

test("foundation load exposes the pending top-up request with method and sender", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_contribution_consents/teacher-a", consentRecord());
  db.records.set("institutes/institute-a/question_bank_topup_requests/pending", {
    status: "pending",
    amountPoisha: 75_00,
    feePoisha: 135,
    payablePoisha: 76_35,
    paymentMethod: "nagad",
    senderNumber: "+8801512345678",
    requestedAtMs: 90_000,
  });
  const result = await foundationHandler(db)(
    foundationRequest({ action: "get_foundation" }),
  );
  assert.equal(result.topupPolicy.pendingRequest.amountPoisha, 75_00);
  assert.equal(result.topupPolicy.pendingRequest.paymentMethod, "nagad");
  assert.equal(result.topupPolicy.pendingRequest.senderNumber, "+8801512345678");
});

test("previous questions list returns newest-first finalized questions with pagination", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_contribution_consents/teacher-a", consentRecord());
  for (let index = 1; index <= 3; index += 1) {
    db.records.set(`institutes/institute-a/question_bank/finalized_op_00${index}_q1`, {
      status: "finalized",
      type: "mcq",
      questionText: `Question ${index}`,
      options: ["A", "B", "C", "D"],
      correctAnswer: "A",
      explanation: "Reason",
      difficulty: "medium",
      marks: 1,
      className: "Class 8",
      subject: "Science",
      chapter: "Light",
      chapterName: "Optics",
      topic: "Reflection",
      examName: `Exam ${index}`,
      finalizedAtMs: 1000 * index,
    });
  }
  db.records.set("institutes/institute-a/question_bank/draft_note", {
    status: "draft",
    questionText: "Hidden draft",
  });
  const handler = foundationHandler(db);
  const page0 = await handler(foundationRequest({ action: "list_previous_questions", limit: 2, page: 0 }));
  assert.equal(page0.questions.length, 2);
  assert.equal(page0.questions[0].questionText, "Question 3");
  assert.equal(page0.questions[1].questionText, "Question 2");
  assert.equal(page0.hasMore, true);
  const page1 = await handler(foundationRequest({ action: "list_previous_questions", limit: 2, page: 1 }));
  assert.equal(page1.questions.length, 1);
  assert.equal(page1.questions[0].questionText, "Question 1");
  assert.equal(page1.hasMore, false);
});

test("super admin approves a top-up, credits the wallet once, and records platform revenue", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_bank_topup_requests/topup_0001", {
    kind: "history",
    status: "pending",
    instituteId: "institute-a",
    actorUid: "teacher-a",
    operationId: "topup_0001",
    amountPoisha: 100_00,
    feePoisha: 180,
    payablePoisha: 101_80,
    paymentMethod: "bkash",
    senderNumber: "+8801712345678",
    requestedAtMs: 100_000,
  });
  db.records.set("institutes/institute-a/question_bank_topup_requests/pending", {
    kind: "pending",
    status: "pending",
    instituteId: "institute-a",
    actorUid: "teacher-a",
    operationId: "topup_0001",
    amountPoisha: 100_00,
    feePoisha: 180,
    payablePoisha: 101_80,
    paymentMethod: "bkash",
    senderNumber: "+8801712345678",
    requestedAtMs: 100_000,
  });
  const handler = adminHandler(db);
  const result = await handler({
    auth: { uid: "root" },
    data: {
      action: "approve_topup",
      operationId: "admin_0001",
      instituteId: "institute-a",
      requestId: "topup_0001",
    },
  });
  assert.equal(result.status, "approved");
  assert.equal(result.balancePoisha, 100_00);
  const wallet = db.records.get("institutes/institute-a/question_bank_wallet/default");
  assert.equal(wallet.balancePoisha, 100_00);
  assert.equal(wallet.totalCreditedPoisha, 100_00);
  const ledger = db.records.get("institutes/institute-a/question_bank_wallet_ledger/topup_topup_0001");
  assert.equal(ledger.type, "credit");
  assert.equal(ledger.amountPoisha, 100_00);
  assert.equal(ledger.feePoisha, 180);
  assert.equal(db.records.get("institutes/institute-a/question_bank_topup_requests/topup_0001").status, "approved");
  assert.equal(db.records.get("institutes/institute-a/question_bank_topup_requests/pending").status, "approved");
  const revenue = db.records.get("platform_question_revenue/topup_topup_0001");
  assert.equal(revenue.kind, "topup_fee");
  assert.equal(revenue.feePoisha, 180);
  const summary = db.records.get("platform_question_revenue/_summary");
  assert.equal(summary.totalTopupFeePoisha, 180);
  assert.equal(summary.totalTopupCreditPoisha, 100_00);
  assert.equal(summary.topupCount, 1);
  // Idempotent replay does not double-credit.
  const replay = await handler({
    auth: { uid: "root" },
    data: {
      action: "approve_topup",
      operationId: "admin_0001",
      instituteId: "institute-a",
      requestId: "topup_0001",
    },
  });
  assert.deepEqual(replay, result);
  assert.equal(db.records.get("institutes/institute-a/question_bank_wallet/default").balancePoisha, 100_00);
  const revenueSummary = await handler({
    auth: { uid: "root" },
    data: { action: "get_revenue_summary" },
  });
  assert.equal(revenueSummary.totalTopupFeePoisha, 180);
});

test("pending top-ups list exposes payment method and sender number for verification", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_bank_topup_requests/topup_0003", {
    kind: "history",
    status: "pending",
    instituteId: "institute-a",
    amountPoisha: 60_00,
    feePoisha: 108,
    payablePoisha: 61_08,
    paymentMethod: "nagad",
    senderNumber: "+8801512345678",
    requestedAtMs: 100_000,
  });
  const result = await adminHandler(db)({
    auth: { uid: "root" },
    data: { action: "list_pending_topups" },
  });
  assert.equal(result.requests.length, 1);
  assert.equal(result.requests[0].paymentMethod, "nagad");
  assert.equal(result.requests[0].senderNumber, "+8801512345678");
});

test("super admin rejects a top-up without touching the wallet or revenue", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_bank_topup_requests/topup_0002", {
    kind: "history",
    status: "pending",
    instituteId: "institute-a",
    amountPoisha: 60_00,
    feePoisha: 108,
    payablePoisha: 61_08,
    paymentMethod: "bkash",
    senderNumber: "+8801712345678",
    requestedAtMs: 100_000,
  });
  const handler = adminHandler(db);
  const result = await handler({
    auth: { uid: "root" },
    data: {
      action: "reject_topup",
      operationId: "admin_0002",
      instituteId: "institute-a",
      requestId: "topup_0002",
    },
  });
  assert.equal(result.status, "rejected");
  assert.equal(db.records.has("institutes/institute-a/question_bank_wallet/default"), false);
  assert.equal(db.records.has("platform_question_revenue/topup_topup_0002"), false);
  assert.equal(db.records.get("institutes/institute-a/question_bank_topup_requests/topup_0002").status, "rejected");
  // Rejection cannot be replayed as an approval.
  await assert.rejects(handler({
    auth: { uid: "root" },
    data: {
      action: "approve_topup",
      operationId: "admin_0003",
      instituteId: "institute-a",
      requestId: "topup_0002",
    },
  }), { code: "failed-precondition" });
});
