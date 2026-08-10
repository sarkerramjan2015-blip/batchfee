"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  hasPermission,
  hashPassword,
  normalizeIdentifier,
  studentLoginDocumentId,
  validatePassword,
  verifyPassword,
} = require("../src/studentAuthCore");

test("login keys are normalized, deterministic, and institute-scoped", () => {
  const first = studentLoginDocumentId(" INST-A ", " Student-01 ");
  assert.equal(first, studentLoginDocumentId("inst-a", "student-01"));
  assert.notEqual(first, studentLoginDocumentId("inst-b", "student-01"));
  assert.match(first, /^[a-f0-9]{64}$/);
  assert.equal(normalizeIdentifier("  ABC-12  "), "abc-12");
});

test("password verifier is salted and rejects wrong credentials", async () => {
  const first = await hashPassword("strong-pass-1");
  const second = await hashPassword("strong-pass-1");
  assert.notEqual(first.salt, second.salt);
  assert.notEqual(first.hash, second.hash);
  assert.equal(await verifyPassword("strong-pass-1", first.salt, first.hash), true);
  assert.equal(await verifyPassword("wrong-pass", first.salt, first.hash), false);
  assert.equal(await verifyPassword("short", first.salt, first.hash), false);
});

test("password and permission validation fail closed", () => {
  assert.equal(validatePassword("123456"), true);
  assert.equal(validatePassword("12345"), false);
  assert.equal(validatePassword("x".repeat(129)), false);
  assert.equal(hasPermission("view_student,manage_student", "manage_student"), true);
  assert.equal(hasPermission("manage_student_extra", "manage_student"), false);
  assert.equal(hasPermission(["manage_student"], "manage_student"), true);
  assert.equal(hasPermission(null, "manage_student"), false);
});
