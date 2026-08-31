"use strict";

const crypto = require("node:crypto");
const { promisify } = require("node:util");

const scryptAsync = promisify(crypto.scrypt);
const PASSWORD_BYTES = 64;

function normalizeIdentifier(value) {
  if (typeof value !== "string") return "";
  return value.normalize("NFKC").trim().toLocaleLowerCase("en-US");
}

function studentLoginDocumentId(studentCode) {
  const normalizedStudent = normalizeIdentifier(studentCode);
  return crypto
    .createHash("sha256")
    .update(normalizedStudent)
    .digest("hex");
}

function staffLoginDocumentId(staffCode) {
  const normalizedStaff = normalizeIdentifier(staffCode);
  return crypto
    .createHash("sha256")
    .update("staff\u0000")
    .update(normalizedStaff)
    .digest("hex");
}

// Kept only so a successful login can migrate accounts provisioned by older
// app versions. New accounts never use an institute-scoped login key.
function legacyStudentLoginDocumentId(instituteCode, studentCode) {
  const normalizedInstitute = normalizeIdentifier(instituteCode);
  const normalizedStudent = normalizeIdentifier(studentCode);
  return crypto
    .createHash("sha256")
    .update(normalizedInstitute)
    .update("\u0000")
    .update(normalizedStudent)
    .digest("hex");
}

function validatePassword(password) {
  return typeof password === "string" && password.length >= 6 && password.length <= 128;
}

async function hashPassword(password, saltBase64) {
  if (!validatePassword(password)) throw new Error("INVALID_PASSWORD_FORMAT");
  const salt = saltBase64 ? Buffer.from(saltBase64, "base64") : crypto.randomBytes(16);
  if (salt.length !== 16) throw new Error("INVALID_PASSWORD_SALT");
  const derived = await scryptAsync(password, salt, PASSWORD_BYTES, { maxmem: 64 * 1024 * 1024 });
  return {
    salt: salt.toString("base64"),
    hash: Buffer.from(derived).toString("base64"),
  };
}

async function verifyPassword(password, saltBase64, expectedHashBase64) {
  if (!validatePassword(password)) return false;
  try {
    const actual = await hashPassword(password, saltBase64);
    const actualBytes = Buffer.from(actual.hash, "base64");
    const expectedBytes = Buffer.from(expectedHashBase64 || "", "base64");
    return actualBytes.length === expectedBytes.length &&
      crypto.timingSafeEqual(actualBytes, expectedBytes);
  } catch (_) {
    return false;
  }
}

function hasPermission(permissionValue, requiredPermission) {
  if (Array.isArray(permissionValue)) {
    return permissionValue.some((value) => value === requiredPermission);
  }
  if (typeof permissionValue !== "string") return false;
  return permissionValue
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean)
    .includes(requiredPermission);
}

module.exports = {
  hasPermission,
  hashPassword,
  legacyStudentLoginDocumentId,
  normalizeIdentifier,
  staffLoginDocumentId,
  studentLoginDocumentId,
  validatePassword,
  verifyPassword,
};
