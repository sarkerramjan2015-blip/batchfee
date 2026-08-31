"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  hasPermission,
  hashPassword,
  legacyStudentLoginDocumentId,
  normalizeIdentifier,
  staffLoginDocumentId,
  studentLoginDocumentId,
  validatePassword,
  verifyPassword,
} = require("../src/studentAuthCore");

test("login keys are normalized, deterministic, and globally student-scoped", () => {
  const first = studentLoginDocumentId(" Student-01 ");
  assert.equal(first, studentLoginDocumentId("student-01"));
  assert.match(first, /^[a-f0-9]{64}$/);
  assert.notEqual(first, studentLoginDocumentId("student-02"));
  assert.notEqual(first, legacyStudentLoginDocumentId("inst-a", "student-01"));
  assert.equal(normalizeIdentifier("  ABC-12  "), "abc-12");
});

test("staff login keys are normalized, deterministic, and separated from student keys", () => {
  const first = staffLoginDocumentId(" STF-250274 ");
  assert.equal(first, staffLoginDocumentId("stf-250274"));
  assert.match(first, /^[a-f0-9]{64}$/);
  assert.notEqual(first, staffLoginDocumentId("stf-250275"));
  assert.notEqual(first, studentLoginDocumentId("stf-250274"));
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
