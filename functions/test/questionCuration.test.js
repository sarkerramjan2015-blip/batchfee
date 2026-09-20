"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createQuestionCurationHandler, questionFingerprint } = require("../src/questionCuration");

function memoryDb() {
  const records = new Map();
  const ref = (path) => ({
    path,
    collection: (name) => ({ doc: (id) => ref(`${path}/${name}/${id}`) }),
  });
  const snapshot = (path) => ({
    exists: records.has(path),
    data: () => records.get(path),
    get: (field) => records.get(path) && records.get(path)[field],
  });
  const query = () => {
    let status = null;
    let max = 50;
    return {
      where: (field, operator, value) => {
        if (field === "moderationStatus" && operator === "==") status = value;
        return queryWith(status, max);
      },
      limit: (value) => queryWith(status, value),
    };
  };
  const queryWith = (status, max) => ({
    where: (field, operator, value) => {
      if (field === "moderationStatus" && operator === "==") return queryWith(value, max);
      return queryWith(status, max);
    },
    limit: (value) => queryWith(status, value),
    get: async () => ({
      docs: [...records.entries()]
        .filter(([path, data]) => path.startsWith("global_pending_review/") &&
          !path.slice("global_pending_review/".length).includes("/") &&
          (status == null || data.moderationStatus === status))
        .slice(0, max)
        .map(([path, data]) => ({ id: path.split("/").at(-1), data: () => data })),
    }),
  });
  return {
    records,
    collection: (name) => ({
      doc: (id) => ref(`${name}/${id}`),
      where: (field, operator, value) => query().where(field, operator, value),
    }),
    runTransaction: async (work) => work({
      get: async (reference) => snapshot(reference.path),
      create: (reference, data) => {
        if (records.has(reference.path)) throw new Error(`Duplicate ${reference.path}`);
        records.set(reference.path, data);
      },
      update: (reference, patch) => {
        if (!records.has(reference.path)) throw new Error(`Missing ${reference.path}`);
        records.set(reference.path, { ...records.get(reference.path), ...patch });
      },
    }),
  };
}

function pendingQuestion(overrides = {}) {
  return {
    moderationStatus: "pending",
    anonymous: true,
    className: "Class 8",
    subject: "Science",
    chapter: "Light",
    topic: "Reflection",
    type: "mcq",
    language: "bn",
    difficulty: "medium",
    questionText: "Which object produces its own light?",
    options: ["Moon", "Sun", "Mirror", "Book"],
    correctAnswer: "Sun",
    explanation: "The Sun is luminous.",
    marks: 1,
    syncedAtMs: 5_000,
    // These simulate an accidentally over-populated old queue document. They
    // must never enter either the moderator DTO or the global question bank.
    instituteId: "secret-school",
    createdBy: "teacher-a",
    ...overrides,
  };
}

function request(action, extra = {}) {
  return {
    auth: { uid: "root-admin" },
    data: { action, ...extra },
  };
}

test("root can list only allow-listed anonymous pending questions", async () => {
  const db = memoryDb();
  db.records.set("global_pending_review/pending_0001", pendingQuestion());
  db.records.set("global_pending_review/pending_0002", pendingQuestion({ moderationStatus: "rejected" }));
  const handler = createQuestionCurationHandler({ db, authorizeRoot: async () => {} });
  const result = await handler(request("list_pending", { limit: 25 }));
  assert.equal(result.questions.length, 1);
  assert.equal(result.questions[0].id, "pending_0001");
  assert.equal(result.questions[0].instituteId, undefined);
  assert.equal(result.questions[0].createdBy, undefined);
});

test("approval atomically publishes anonymous content and replays safely", async () => {
  const db = memoryDb();
  db.records.set("global_pending_review/pending_0001", pendingQuestion());
  const handler = createQuestionCurationHandler({ db, authorizeRoot: async () => {}, now: () => 9_000 });
  const first = await handler(request("approve", {
    pendingId: "pending_0001", operationId: "curation_0001",
  }));
  assert.deepEqual(first, {
    action: "approve", pendingId: "pending_0001", publishedId: "pending_0001",
    status: "approved", reviewedAtMs: 9_000,
  });
  const published = db.records.get("global_question_bank/pending_0001");
  assert.equal(published.status, "curated");
  assert.equal(published.questionText, "Which object produces its own light?");
  assert.equal(published.instituteId, undefined);
  assert.equal(published.createdBy, undefined);
  assert.equal(typeof published.questionFingerprint, "string");
  assert.equal(db.records.get(`global_question_dedup/${published.questionFingerprint}`).globalQuestionId, "pending_0001");
  assert.deepEqual(await handler(request("approve", {
    pendingId: "pending_0001", operationId: "curation_0001",
  })), first);
});

test("approval blocks an identical academic question without revealing its source", async () => {
  const db = memoryDb();
  db.records.set("global_pending_review/pending_0002", pendingQuestion());
  db.records.set(`global_question_dedup/${questionFingerprint(pendingQuestion())}`, {
    globalQuestionId: "some-other-question",
  });
  const handler = createQuestionCurationHandler({ db, authorizeRoot: async () => {} });
  await assert.rejects(handler(request("approve", {
    pendingId: "pending_0002", operationId: "curation_0002",
  })), (error) => error.code === "already-exists" && !error.message.includes("some-other-question"));
  assert.equal(db.records.get("global_pending_review/pending_0002").moderationStatus, "pending");
});

test("rejection is audited and malformed content cannot be approved", async () => {
  const db = memoryDb();
  db.records.set("global_pending_review/pending_0001", pendingQuestion());
  db.records.set("global_pending_review/pending_0002", pendingQuestion({ options: ["Only one"] }));
  const handler = createQuestionCurationHandler({ db, authorizeRoot: async () => {}, now: () => 10_000 });
  const rejected = await handler(request("reject", {
    pendingId: "pending_0001", operationId: "curation_0002", reason: "Duplicate concept.",
  }));
  assert.equal(rejected.status, "rejected");
  assert.equal(db.records.get("global_pending_review/pending_0001").rejectionReason, "Duplicate concept.");
  await assert.rejects(handler(request("approve", {
    pendingId: "pending_0002", operationId: "curation_0003",
  })), { code: "failed-precondition" });
});

test("non-root callers cannot inspect or curate questions", async () => {
  const db = memoryDb();
  const handler = createQuestionCurationHandler({
    db,
    authorizeRoot: async () => { throw Object.assign(new Error("Denied"), { code: "permission-denied" }); },
  });
  await assert.rejects(handler(request("list_pending")), { code: "permission-denied" });
});
