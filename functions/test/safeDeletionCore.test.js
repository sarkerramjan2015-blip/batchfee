"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  RETENTION_MS,
  canonicalDeletionRequest,
  deletionStateId,
  retainedUntil,
  validateDeletionRequest,
} = require("../src/safeDeletionCore");

const valid = {
  operationId: "operation-safe-delete-0001",
  instituteId: "institute-a",
  entityType: "student",
  entityId: "student-a",
  action: "archive",
  reason: "Student left the institute",
};

test("safe deletion accepts only archive and restore for scoped entity types", () => {
  assert.deepEqual(validateDeletionRequest(valid), valid);
  assert.throws(() => validateDeletionRequest({ ...valid, action: "purge" }), /Unsupported/);
  assert.throws(() => validateDeletionRequest({ ...valid, entityType: "payment" }), /Unsupported/);
  assert.throws(() => validateDeletionRequest({ ...valid, reason: "x" }), /reason/);
});

test("institute target must be its own canonical institute document", () => {
  assert.throws(() => validateDeletionRequest({
    ...valid,
    entityType: "institute",
    entityId: "another-institute",
  }), /target/);
});

test("operation hash is stable and retention never authorizes automatic purge", () => {
  const first = canonicalDeletionRequest(valid);
  const second = canonicalDeletionRequest({
    reason: valid.reason,
    action: valid.action,
    entityId: valid.entityId,
    entityType: valid.entityType,
    instituteId: valid.instituteId,
    operationId: valid.operationId,
  });
  assert.equal(first.requestHash, second.requestHash);
  assert.equal(retainedUntil(1_000), 1_000 + RETENTION_MS);
  assert.equal(deletionStateId("student", "student-a"), "student_student-a");
});
