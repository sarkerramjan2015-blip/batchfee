"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  CONTRIBUTION_POLICY_VERSION,
  createQuestionBankFoundationHandler,
} = require("../src/questionBankFoundation");

function memoryDb() {
  const records = new Map();
  const ref = (path) => ({
    path,
    collection: (name) => ({ doc: (id) => ref(`${path}/${name}/${id}`) }),
    get: async () => snapshot(path),
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

test("contribution starts off and billing remains disabled", async () => {
  const db = memoryDb();
  const authorization = [];
  const handler = createQuestionBankFoundationHandler({
    db,
    authorize: async (...args) => authorization.push(args),
  });
  const result = await handler(request("teacher-a", "get_foundation"));
  assert.equal(result.contribution.enabled, false);
  assert.equal(result.contribution.perQuestionApprovalRequired, true);
  assert.equal(result.aiBilling.enabled, false);
  assert.equal(result.aiBilling.walletSeparateFromSms, true);
  assert.deepEqual(result.taxonomy.questionTypes, ["mcq", "short", "creative"]);
  assert.equal(authorization[0][2], "manage_exams");
  assert.equal(db.records.size, 0);
});

test("only an authenticated, authorized actor can enable a current policy with rights acknowledgement", async () => {
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
  const base = { operationId: "operation_001", policyVersion: CONTRIBUTION_POLICY_VERSION, enabled: true };
  await assert.rejects(handler(request("teacher-a", "set_contribution_preference", base)), { code: "failed-precondition" });
  await assert.rejects(handler(request("teacher-a", "set_contribution_preference", {
    ...base, confirmedRights: true, policyVersion: "old",
  })), { code: "failed-precondition" });
  const enabled = await handler(request("teacher-a", "set_contribution_preference", {
    ...base, confirmedRights: true,
  }));
  assert.equal(enabled.contribution.enabled, true);
  assert.equal(enabled.contribution.acceptedAtMs, 1_234);
  assert.equal(db.records.size, 2);
  assert.equal(db.records.get("institutes/institute-a/question_contribution_consents/teacher-a").actorUid, "teacher-a");
});

test("replay cannot change actor or choice and revocation leaves an immutable event", async () => {
  const db = memoryDb();
  let timestamp = 1_000;
  const handler = createQuestionBankFoundationHandler({
    db, authorize: async () => {}, now: () => ++timestamp,
  });
  const grant = {
    operationId: "operation_002", policyVersion: CONTRIBUTION_POLICY_VERSION,
    enabled: true, confirmedRights: true,
  };
  await handler(request("teacher-a", "set_contribution_preference", grant));
  await assert.rejects(handler(request("teacher-b", "set_contribution_preference", grant)), { code: "already-exists" });
  await assert.rejects(handler(request("teacher-a", "set_contribution_preference", {
    ...grant, enabled: false,
  })), { code: "already-exists" });
  const revoked = await handler(request("teacher-a", "set_contribution_preference", {
    operationId: "operation_003", policyVersion: CONTRIBUTION_POLICY_VERSION,
    enabled: false, confirmedRights: false,
  }));
  assert.equal(revoked.contribution.enabled, false);
  const replay = await handler(request("teacher-a", "set_contribution_preference", grant));
  assert.equal(replay.contribution.enabled, true);
  assert.equal((await handler(request("teacher-a", "get_foundation"))).contribution.enabled, false);
  assert.equal(db.records.size, 3);
});
