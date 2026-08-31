"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  generateShortStudentId,
  isLegacyAutoStudentId,
  isValidStudentId,
  normalizeStudentId,
  studentIdClaimDocumentId,
} = require("../src/studentIdCore");

test("short student IDs use the readable numeric format", () => {
  assert.equal(generateShortStudentId(() => 102514), "ST-102514");
  assert.equal(generateShortStudentId(() => 999999), "ST-999999");
});

test("manual student IDs are normalized and safely validated", () => {
  assert.equal(normalizeStudentId(" st-1025 "), "ST-1025");
  assert.equal(isValidStudentId("STD-1214"), true);
  assert.equal(isValidStudentId("ST 1025"), false);
  assert.equal(isValidStudentId("ST-@1025"), false);
  assert.equal(isValidStudentId("A"), false);
});

test("only the exact pre-v1.7 auto format is accepted for rollout compatibility", () => {
  assert.equal(isLegacyAutoStudentId("BF-3AE5-B3B4-E811-86E0-D93F"), true);
  assert.equal(isLegacyAutoStudentId("BF-CUSTOM-LONG-ID"), false);
  assert.equal(isLegacyAutoStudentId("ST-102514"), false);
});

test("claim IDs are case-insensitive and stable", () => {
  assert.equal(studentIdClaimDocumentId("st-1025"), studentIdClaimDocumentId(" ST-1025 "));
  assert.notEqual(studentIdClaimDocumentId("ST-1025"), studentIdClaimDocumentId("ST-1026"));
});
