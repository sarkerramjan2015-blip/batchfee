"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { CONTRIBUTION_POLICY_VERSION } = require("../src/questionBankFoundation");
const {
  createQuestionFinalizationHandler,
  proposedCostPoisha,
} = require("../src/questionFinalization");

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
  return {
    records,
    collection: (name) => ({ doc: (id) => ref(`${name}/${id}`) }),
    runTransaction: async (work) => work({
      get: async (reference) => snapshot(reference.path),
      create: (reference, data) => {
        if (records.has(reference.path)) throw new Error(`Duplicate document ${reference.path}`);
        records.set(reference.path, data);
      },
      update: (reference, patch) => {
        if (!records.has(reference.path)) throw new Error(`Missing document ${reference.path}`);
        records.set(reference.path, { ...records.get(reference.path), ...patch });
      },
    }),
  };
}

function seedPreview(db, actorUid = "teacher-a") {
  db.records.set("institutes/institute-a/question_contribution_consents/teacher-a", {
    aiTncAccepted: true,
    policyVersion: CONTRIBUTION_POLICY_VERSION,
  });
  db.records.set("institutes/institute-a/question_generation_jobs/generation_0001", {
    actorUid,
    status: "complete",
    questionType: "mcq",
    setup: {
      examName: "Weekly science test",
      totalMarks: 10,
      durationMinutes: 30,
      className: "Class 8",
      subject: "Science",
      chapter: "Light",
      language: "bn",
    },
    result: { questions: [{ id: "generated_01" }, { id: "generated_02" }] },
  });
}

function request(questions, extra = {}) {
  return {
    auth: { uid: "teacher-a" },
    data: {
      instituteId: "institute-a",
      generationOperationId: "generation_0001",
      operationId: "finalize_0001",
      questionType: "mcq",
      questions,
      ...extra,
    },
  };
}

function mcq(sourceQuestionId = "generated_01") {
  return {
    sourceQuestionId,
    questionText: "Which object gives light?",
    options: ["Moon", "Sun", "Mirror", "Book"],
    correctAnswer: "Sun",
    explanation: "The Sun is a natural luminous object.",
    difficulty: "easy",
    marks: 1,
  };
}

test("finalization saves reviewed private questions with an idempotent operation", async () => {
  const db = memoryDb();
  seedPreview(db);
  const handler = createQuestionFinalizationHandler({
    db,
    authorize: async () => {},
    now: () => 42_000,
  });

  const first = await handler(request([mcq(), mcq("generated_02")]));
  assert.deepEqual(first, {
    operationId: "finalize_0001",
    questionCount: 2,
    costPoisha: 50,
    billingStatus: "pricing_not_configured_no_debit",
  });
  const privateQuestion = db.records.get(
    "institutes/institute-a/question_bank/finalized_finalize_0001_generated_01",
  );
  assert.equal(privateQuestion.status, "finalized");
  assert.equal(privateQuestion.createdBy, "teacher-a");
  assert.equal(privateQuestion.subject, "Science");
  assert.equal(privateQuestion.pricing.quotedCostPoisha, 25);
  assert.equal(
    db.records.get("institutes/institute-a/question_generation_jobs/generation_0001").finalizationOperationId,
    "finalize_0001",
  );
  assert.deepEqual(await handler(request([mcq(), mcq("generated_02")])), first);
  assert.equal(proposedCostPoisha("creative", 2), 150);
});

test("finalization rejects injected source IDs, over-total marks, and duplicate operations", async () => {
  const db = memoryDb();
  seedPreview(db);
  const handler = createQuestionFinalizationHandler({ db, authorize: async () => {} });
  await assert.rejects(handler(request([mcq("generated_99")])), { code: "invalid-argument" });
  await assert.rejects(handler(request([{ ...mcq(), marks: 11 }])), { code: "invalid-argument" });
  await handler(request([mcq()]));
  await assert.rejects(handler(request([mcq()], { operationId: "finalize_0002" })), {
    code: "failed-precondition",
  });
});

test("finalization never accepts a generation preview owned by another actor", async () => {
  const db = memoryDb();
  seedPreview(db, "teacher-b");
  const handler = createQuestionFinalizationHandler({ db, authorize: async () => {} });
  await assert.rejects(handler(request([mcq()])), { code: "failed-precondition" });
});
