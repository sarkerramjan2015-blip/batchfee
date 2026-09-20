"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createQuestionBankAdminHandler } = require("../src/questionBankAdmin");

function memoryDb() {
  const records = new Map();
  const ref = (path) => ({
    path,
    get: async () => snapshot(path),
    collection: (name) => ({ doc: (id) => ref(`${path}/${name}/${id}`) }),
  });
  const snapshot = (path) => ({
    exists: records.has(path),
    data: () => records.get(path),
    get: (field) => records.get(path) && records.get(path)[field],
  });
  return {
    records,
    collection: (name) => ({
      doc: (id) => ref(`${name}/${id}`),
      where: (field, operator, value) => ({
        limit: (limit) => ({
          get: async () => ({
            docs: [...records.entries()]
              .filter(([path, data]) => path.startsWith(`${name}/`) && !path.slice(name.length + 1).includes("/") &&
                operator === "==" && data[field] === value)
              .slice(0, limit)
              .map(([path, data]) => ({ id: path.split("/").at(-1), data: () => data })),
          }),
        }),
      }),
    }),
    runTransaction: async (work) => work({
      get: async (reference) => snapshot(reference.path),
      set: (reference, value) => records.set(reference.path, value),
      create: (reference, value) => {
        if (records.has(reference.path)) throw new Error(`Duplicate ${reference.path}`);
        records.set(reference.path, value);
      },
      update: (reference, patch) => {
        if (!records.has(reference.path)) throw new Error(`Missing ${reference.path}`);
        records.set(reference.path, { ...records.get(reference.path), ...patch });
      },
      delete: (reference) => records.delete(reference.path),
    }),
  };
}

function question(overrides = {}) {
  return {
    status: "curated",
    questionFingerprint: "f".repeat(64),
    className: "Class 8",
    subject: "Science",
    chapter: "Light",
    topic: "Reflection",
    type: "mcq",
    language: "bn",
    difficulty: "easy",
    questionText: "Which object produces light?",
    options: ["Moon", "Sun", "Book", "Mirror"],
    correctAnswer: "Sun",
    explanation: "The Sun is luminous.",
    marks: 1,
    curatedAtMs: 100,
    instituteId: "must-not-leak",
    ...overrides,
  };
}

function request(action, extra = {}) {
  return { auth: { uid: "root-admin" }, data: { action, ...extra } };
}

test("root reads defaults then writes idempotent platform question controls", async () => {
  const db = memoryDb();
  const handler = createQuestionBankAdminHandler({ db, authorizeRoot: async () => {}, now: () => 500, randomId: () => "audit" });
  const defaults = await handler(request("get_settings"));
  assert.equal(defaults.settings.generationEnabled, true);
  const input = request("update_settings", {
    operationId: "question_admin_0001",
    settings: {
      generationEnabled: false,
      contributionEnabled: true,
      actorDailyPreviewLimit: 4,
      instituteDailyPreviewLimit: 40,
      platformDailyPreviewLimit: 400,
      maxQuestionsPerRequest: 20,
    },
  });
  const result = await handler(input);
  assert.equal(result.settings.generationEnabled, false);
  assert.equal(db.records.get("platform_question_bank_settings/default").updatedBy, "root-admin");
  assert.deepEqual(await handler(input), result);
});

test("root can retire and restore one curated question while maintaining duplicate protection", async () => {
  const db = memoryDb();
  db.records.set("global_question_bank/question_0001", question());
  db.records.set(`global_question_dedup/${"f".repeat(64)}`, { globalQuestionId: "question_0001" });
  const handler = createQuestionBankAdminHandler({ db, authorizeRoot: async () => {}, now: () => 800 });
  const retired = await handler(request("retire_question", {
    operationId: "question_admin_0002", questionId: "question_0001",
  }));
  assert.equal(retired.status, "retired");
  assert.equal(db.records.get("global_question_bank/question_0001").status, "retired");
  assert.equal(db.records.has(`global_question_dedup/${"f".repeat(64)}`), false);
  const restored = await handler(request("restore_question", {
    operationId: "question_admin_0003", questionId: "question_0001",
  }));
  assert.equal(restored.status, "curated");
  assert.equal(db.records.get(`global_question_dedup/${"f".repeat(64)}`).globalQuestionId, "question_0001");
});

test("root can credit an institute question wallet once with an audited ledger", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-1", { instituteName: "Test Institute" });
  const handler = createQuestionBankAdminHandler({
    db, authorizeRoot: async () => {}, now: () => 900, randomId: () => "wallet-audit",
  });
  const input = request("credit_institute_wallet", {
    operationId: "question_wallet_0001",
    instituteId: "institute-1",
    amountPoisha: 1_000,
    reason: "Manual payment TXN-1",
  });
  const first = await handler(input);
  assert.equal(first.balancePoisha, 1_000);
  assert.equal(db.records.get("institutes/institute-1/question_bank_wallet/default").balancePoisha, 1_000);
  assert.equal(
    db.records.get("institutes/institute-1/question_bank_wallet_ledger/credit_question_wallet_0001").amountPoisha,
    1_000,
  );
  assert.deepEqual(await handler(input), first);
  assert.equal(db.records.get("institutes/institute-1/question_bank_wallet/default").balancePoisha, 1_000);
});

test("approved list is academic-only and non-root calls fail closed", async () => {
  const db = memoryDb();
  db.records.set("global_question_bank/question_0001", question());
  const handler = createQuestionBankAdminHandler({ db, authorizeRoot: async () => {} });
  const result = await handler(request("list_questions"));
  assert.equal(result.questions[0].instituteId, undefined);
  const denied = createQuestionBankAdminHandler({
    db,
    authorizeRoot: async () => { throw Object.assign(new Error("Denied"), { code: "permission-denied" }); },
  });
  await assert.rejects(denied(request("get_settings")), { code: "permission-denied" });
});
