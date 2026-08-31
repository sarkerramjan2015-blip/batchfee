"use strict";

const crypto = require("node:crypto");

const STUDENT_ID_PATTERN = /^[A-Z0-9]+(?:-[A-Z0-9]+)*$/;
const LEGACY_AUTO_STUDENT_ID_PATTERN = /^BF-(?:[0-9A-F]{4}-){4}[0-9A-F]{4}$/;

function normalizeStudentId(value) {
  if (typeof value !== "string") return "";
  return value.normalize("NFKC").trim().toLocaleUpperCase("en-US");
}

function isValidStudentId(value) {
  const normalized = normalizeStudentId(value);
  return normalized.length >= 3 && normalized.length <= 20 && STUDENT_ID_PATTERN.test(normalized);
}

function isLegacyAutoStudentId(value) {
  return LEGACY_AUTO_STUDENT_ID_PATTERN.test(normalizeStudentId(value));
}

function generateShortStudentId(randomInt = crypto.randomInt) {
  return `ST-${randomInt(100000, 1000000)}`;
}

function studentIdClaimDocumentId(studentCode) {
  return crypto.createHash("sha256")
    .update("student-id\u0000")
    .update(normalizeStudentId(studentCode))
    .digest("hex");
}

module.exports = {
  generateShortStudentId,
  isLegacyAutoStudentId,
  isValidStudentId,
  normalizeStudentId,
  studentIdClaimDocumentId,
};
