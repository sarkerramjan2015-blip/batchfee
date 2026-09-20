"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createQuestionBankLibraryHandler } = require("../src/questionBankLibrary");

function memoryDb() {
  const records = new Map();
  const ref = (path) => ({
    path,
    collection: (name) => ({ doc: (id) => ref(`${path}/${name}/${id}`) }),
  });
  const snapshot = (path) => ({
    id: path.split("/").at(-1),
    exists: records.has(path),
    data: () => records.get(path),
    get: (field) => records.get(path) && records.get(path)[field],
  });
  const query = (collection, clauses = [], cursor = null, limitValue = null) => ({
    where: (field, operator, value) => query(collection, [...clauses, [field, operator, value]], cursor, limitValue),
    orderBy: () => query(collection, clauses, cursor, limitValue),
    startAfter: (curatedAtMs, id) => query(collection, clauses, { curatedAtMs, id }, limitValue),
    limit: (value) => query(collection, clauses, cursor, value),
    get: async () => {
      const docs = [...records.entries()]
        .filter(([path, data]) => path.startsWith(`${collection}/`) && !path.slice(collection.length + 1).includes("/") &&
          clauses.every(([field, operator, value]) => operator === "==" && data[field] === value))
        .map(([path, data]) => ({ id: path.split("/").at(-1), data: () => data }))
        .sort((left, right) => Number(right.data().curatedAtMs || 0) - Number(left.data().curatedAtMs || 0) || left.id.localeCompare(right.id));
      const after = cursor ? docs.filter((document) =>
        Number(document.data().curatedAtMs || 0) < cursor.curatedAtMs ||
        (Number(document.data().curatedAtMs || 0) === cursor.curatedAtMs && document.id > cursor.id)) : docs;
      const paged = limitValue == null ? after : after.slice(0, limitValue);
      return { size: paged.length, docs: paged };
    },
  });
  return {
    records,
    collection: (name) => ({
      doc: (id) => ref(`${name}/${id}`),
      where: (field, operator, value) => query(name).where(field, operator, value),
    }),
    runTransaction: async (work) => work({
      get: async (reference) => snapshot(reference.path),
      create: (reference, data) => {
        if (records.has(reference.path)) throw new Error(`Duplicate ${reference.path}`);
        records.set(reference.path, data);
      },
    }),
  };
}

function curatedQuestion(overrides = {}) {
  return {
    status: "curated",
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
    curatedAtMs: 9000,
    instituteId: "never-returned",
    createdBy: "never-returned",
    ...overrides,
  };
}

function request(action, extra = {}) {
  return { auth: { uid: "teacher-1" }, data: { action, instituteId: "school_001", ...extra } };
}

test("library list returns only curated academic allow-list content", async () => {
  const db = memoryDb();
  db.records.set("global_question_bank/curated_0001", curatedQuestion());
  db.records.set("global_question_bank/curated_0002", curatedQuestion({ status: "retired", questionText: "Old" }));
  const handler = createQuestionBankLibraryHandler({ db, authorize: async () => {} });
  const result = await handler(request("list", { filters: { subject: "science", search: "light" } }));
  assert.equal(result.questions.length, 1);
  assert.equal(result.questions[0].id, "curated_0001");
  assert.equal(result.questions[0].instituteId, undefined);
  assert.equal(result.questions[0].createdBy, undefined);
});

test("library uses a stable opaque cursor and applies curriculum filters server-side", async () => {
  const db = memoryDb();
  db.records.set("global_question_bank/curated_0001", curatedQuestion({ curatedAtMs: 300, curriculum: "NCTB", syllabusYear: "2026" }));
  db.records.set("global_question_bank/curated_0002", curatedQuestion({ curatedAtMs: 200, curriculum: "NCTB", syllabusYear: "2026", questionText: "Second question" }));
  db.records.set("global_question_bank/curated_0003", curatedQuestion({ curatedAtMs: 100, curriculum: "Other", syllabusYear: "2026", questionText: "Other curriculum" }));
  const handler = createQuestionBankLibraryHandler({ db, authorize: async () => {} });
  const first = await handler(request("list", { limit: 1, filters: { curriculum: "nctb", syllabusYear: "2026" } }));
  assert.equal(first.questions[0].id, "curated_0001");
  assert.ok(first.nextPageToken);
  const second = await handler(request("list", {
    limit: 2,
    pageToken: first.nextPageToken,
    filters: { curriculum: "nctb", syllabusYear: "2026" },
  }));
  assert.deepEqual(second.questions.map((question) => question.id), ["curated_0002"]);
  assert.equal(second.nextPageToken, null);
  await assert.rejects(handler(request("list", { pageToken: "not-a-page-token" })), { code: "invalid-argument" });
});

test("prepare paper re-reads curated questions and records one idempotent private usage audit", async () => {
  const db = memoryDb();
  db.records.set("global_question_bank/curated_0001", curatedQuestion());
  const handler = createQuestionBankLibraryHandler({
    db, authorize: async () => {}, now: () => 10000, randomId: () => "audit-token",
  });
  const input = request("prepare_paper", { operationId: "paper_0001", questionIds: ["curated_0001"] });
  const first = await handler(input);
  assert.equal(first.questions.length, 1);
  assert.equal(first.questions[0].correctAnswer, "Sun");
  assert.equal(first.questions[0].instituteId, undefined);
  const usage = db.records.get("institutes/school_001/question_bank_usage/paper_0001");
  assert.equal(usage.source, "global_curated_question_bank");
  assert.equal(usage.actorUid, "teacher-1");
  assert.deepEqual(await handler(input), first);
});

test("prepare paper rejects retired selections and authorization fails closed", async () => {
  const db = memoryDb();
  db.records.set("global_question_bank/curated_0001", curatedQuestion({ status: "retired" }));
  const handler = createQuestionBankLibraryHandler({ db, authorize: async () => {} });
  await assert.rejects(handler(request("prepare_paper", {
    operationId: "paper_0002", questionIds: ["curated_0001"],
  })), { code: "failed-precondition" });
  const blocked = createQuestionBankLibraryHandler({
    db,
    authorize: async () => { throw Object.assign(new Error("Denied"), { code: "permission-denied" }); },
  });
  await assert.rejects(blocked(request("list")), { code: "permission-denied" });
});
