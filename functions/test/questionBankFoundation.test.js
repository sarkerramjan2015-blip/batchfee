"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  CONTRIBUTION_POLICY_VERSION,
  anonymousQuestionPayload,
  createAnonymousQuestionSyncHandler,
  createQuestionBankFoundationHandler,
  pendingQuestionId,
} = require("../src/questionBankFoundation");

function memoryDb() {
  const records = new Map();
  const ref = (path) => ({
    path,
    collection: (name) => ({ doc: (id) => ref(`${path}/${name}/${id}`) }),
    get: async () => snapshot(path),
    set: async (data) => { records.set(path, data); },
    delete: async () => { records.delete(path); },
  });
  const snapshot = (path) => ({
    exists: records.has(path),
    data: () => records.get(path),
  });
  return {
    records,
    collection: (name) => ({ doc: (id) => ref(`${name}/${id}`) }),
    runTransaction: async (work) => work({
      get: async (reference) => snapshot(reference.path),
      set: (reference, data) => { records.set(reference.path, data); },
      create: (reference, data) => {
        if (records.has(reference.path)) throw new Error("Duplicate event");
        records.set(reference.path, data);
      },
    }),
  };
}

function request(uid, action, extra = {}) {
  return { auth: uid ? { uid } : null, data: { instituteId: "institute-a", action, ...extra } };
}

test("AI terms start unaccepted with an empty server-owned wallet and five free attempts", async () => {
  const db = memoryDb();
  const authorization = [];
  const handler = createQuestionBankFoundationHandler({
    db,
    authorize: async (...args) => authorization.push(args),
  });
  const result = await handler(request("teacher-a", "get_foundation"));
  assert.equal(result.aiTerms.accepted, false);
  assert.equal(result.aiTerms.perQuestionApprovalRequired, false);
  assert.equal(result.aiTerms.automaticAnonymousSync, true);
  assert.equal(result.aiBilling.enabled, true);
  assert.equal(result.aiBilling.walletSeparateFromSms, true);
  assert.equal(result.aiBilling.balancePoisha, 0);
  assert.equal(result.aiBilling.freeAttemptsRemaining, 5);
  assert.equal(result.aiBilling.ratesPoisha.mcq, 25);
  assert.deepEqual(result.taxonomy.questionTypes, ["mcq", "short", "creative"]);
  assert.equal(authorization[0][2], "manage_exams");
  assert.equal(db.records.size, 0);
});

test("only an authenticated, authorized actor can accept current AI terms", async () => {
  const db = memoryDb();
  const handler = createQuestionBankFoundationHandler({
    db,
    authorize: async (auth) => {
      if (auth.uid !== "teacher-a") throw new Error("Forbidden actor");
    },
    now: () => 1_234,
  });
  await assert.rejects(handler(request(null, "get_foundation")), { code: "unauthenticated" });
  await assert.rejects(handler(request("teacher-b", "get_foundation")), /Forbidden actor/);
  const base = { operationId: "operation_001", policyVersion: CONTRIBUTION_POLICY_VERSION };
  await assert.rejects(handler(request("teacher-a", "accept_ai_tnc", base)), { code: "failed-precondition" });
  await assert.rejects(handler(request("teacher-a", "accept_ai_tnc", {
    ...base, confirmedRights: true, policyVersion: "old",
  })), { code: "failed-precondition" });
  const enabled = await handler(request("teacher-a", "accept_ai_tnc", {
    ...base, confirmedRights: true,
  }));
  assert.equal(enabled.aiTerms.accepted, true);
  assert.equal(enabled.aiTerms.acceptedAtMs, 1_234);
  assert.equal(db.records.size, 2);
  assert.equal(db.records.get("institutes/institute-a/question_contribution_consents/teacher-a").actorUid, "teacher-a");
});

test("one-time consent replay cannot change actor", async () => {
  const db = memoryDb();
  let timestamp = 1_000;
  const handler = createQuestionBankFoundationHandler({
    db, authorize: async () => {}, now: () => ++timestamp,
  });
  const grant = {
    operationId: "operation_002", policyVersion: CONTRIBUTION_POLICY_VERSION,
    confirmedRights: true,
  };
  await handler(request("teacher-a", "accept_ai_tnc", grant));
  await assert.rejects(handler(request("teacher-b", "accept_ai_tnc", grant)), { code: "already-exists" });
  const replay = await handler(request("teacher-a", "accept_ai_tnc", grant));
  assert.equal(replay.aiTerms.accepted, true);
  assert.equal((await handler(request("teacher-a", "get_foundation"))).aiTerms.accepted, true);
  assert.equal(db.records.size, 2);
});

test("anonymous payload strictly excludes tenant and teacher identity", () => {
  const payload = anonymousQuestionPayload({
    instituteId: "secret-school", instituteName: "Secret School", createdBy: "teacher-a",
    teacherName: "Teacher A", className: "Class 8", subject: "Science", chapter: "Light",
    chapterName: "Light reflection", topic: "Laws of reflection",
    type: "mcq", language: "en", questionText: "What is reflection?",
    options: ["A", "B"], correctAnswer: "A", sourceUrl: "private://book-page",
  }, 9_999);
  assert.equal(payload.questionText, "What is reflection?");
  assert.equal(payload.anonymous, true);
  assert.equal(payload.chapterName, "Light reflection");
  assert.equal(payload.topic, "Laws of reflection");
  assert.equal(payload.moderationStatus, "pending");
  for (const forbidden of ["instituteId", "instituteName", "createdBy", "teacherName", "sourceUrl"]) {
    assert.equal(Object.hasOwn(payload, forbidden), false);
  }
});

test("finalized consented question syncs once to an anonymous pending document", async () => {
  const db = memoryDb();
  db.records.set("institutes/institute-a/question_contribution_consents/teacher-a", {
    aiTncAccepted: true, policyVersion: CONTRIBUTION_POLICY_VERSION,
  });
  const handler = createAnonymousQuestionSyncHandler({ db, now: () => 7_777 });
  await handler({
    params: { instituteId: "institute-a", questionId: "question-1" },
    data: { after: { exists: true, data: () => ({
      status: "finalized", createdBy: "teacher-a", type: "short",
      className: "Class 9", subject: "Bangla", chapter: "Poetry",
      questionText: "Explain the central idea.", instituteName: "Never copy me",
    }) } },
  });
  const id = pendingQuestionId("institute-a", "question-1");
  const saved = db.records.get(`global_pending_review/${id}`);
  assert.equal(saved.questionText, "Explain the central idea.");
  assert.equal(saved.instituteName, undefined);
  assert.equal(saved.syncedAtMs, 7_777);
});

test("unconsented or non-finalized questions never remain in pending review", async () => {
  const db = memoryDb();
  const id = pendingQuestionId("institute-a", "question-2");
  db.records.set(`global_pending_review/${id}`, { stale: true });
  const handler = createAnonymousQuestionSyncHandler({ db });
  await handler({
    params: { instituteId: "institute-a", questionId: "question-2" },
    data: { after: { exists: true, data: () => ({
      status: "draft", createdBy: "teacher-a", type: "mcq", questionText: "Draft",
    }) } },
  });
  assert.equal(db.records.has(`global_pending_review/${id}`), false);
});
