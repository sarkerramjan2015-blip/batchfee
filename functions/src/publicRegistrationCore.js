"use strict";

const crypto = require("node:crypto");

const MAX = {
  slug: 80,
  fullName: 120,
  guardianName: 120,
  schoolName: 160,
  className: 80,
  address: 300,
};

const BANGLADESH_MOBILE = /^1[3-9][0-9]{8}$/;
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const BLOOD_GROUPS = new Set(["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"]);
const NAME = /^[\p{L}\p{M}][\p{L}\p{M}\p{N} .,'’-]*$/u;

function cleanText(value, field, { required = false, max, pattern = null } = {}) {
  if (value == null || value === "") {
    if (required) throw new Error(`${field} is required.`);
    return null;
  }
  if (typeof value !== "string") throw new Error(`Invalid ${field}.`);
  const clean = value.normalize("NFKC").trim().replace(/\s+/g, " ");
  if (!clean || clean.length > max || /[\u0000-\u001F\u007F<>]/.test(clean)) {
    throw new Error(`Invalid ${field}.`);
  }
  if (pattern && !pattern.test(clean)) throw new Error(`Invalid ${field}.`);
  return clean;
}

function normalizeBangladeshMobile(value, field, required = false) {
  if (value == null || value === "") {
    if (required) throw new Error(`${field} is required.`);
    return null;
  }
  if (typeof value !== "string") throw new Error(`Invalid ${field}.`);
  const compact = value.normalize("NFKC").trim().replace(/[\s()-]/g, "");
  const local = compact.startsWith("+880") ? compact.slice(4) :
    compact.startsWith("880") ? compact.slice(3) :
      compact.startsWith("0") ? compact.slice(1) : compact;
  if (!BANGLADESH_MOBILE.test(local)) throw new Error(`Invalid ${field}.`);
  return `+880${local}`;
}

function parseBirthDate(value) {
  if (value == null || value === "") return null;
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new Error("Invalid date of birth.");
  }
  const [year, month, day] = value.split("-").map(Number);
  const timestamp = Date.UTC(year, month - 1, day);
  const date = new Date(timestamp);
  const today = new Date();
  const latest = Date.UTC(today.getUTCFullYear() - 3, today.getUTCMonth(), today.getUTCDate());
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 ||
      date.getUTCDate() !== day || year < 1900 || timestamp > latest) {
    throw new Error("Invalid date of birth.");
  }
  return timestamp;
}

function canonicalBloodGroup(value) {
  if (value == null || value === "") return null;
  if (typeof value !== "string") throw new Error("Invalid blood group.");
  const clean = value.normalize("NFKC").trim().toUpperCase().replace("−", "-");
  if (!BLOOD_GROUPS.has(clean)) throw new Error("Invalid blood group.");
  return clean;
}

function canonicalRegistrationPayload(input) {
  const raw = input && typeof input === "object" ? input : {};
  const slug = cleanText(raw.slug, "registration link", {
    required: true,
    max: MAX.slug,
    pattern: SLUG,
  });
  const fullName = cleanText(raw.fullName, "student name", {
    required: true,
    max: MAX.fullName,
    pattern: NAME,
  });
  if (fullName.length < 2) throw new Error("Invalid student name.");

  const guardianName = cleanText(raw.guardianName, "guardian name", {
    max: MAX.guardianName,
    pattern: NAME,
  });
  const schoolName = cleanText(raw.schoolName, "school name", { max: MAX.schoolName });
  const className = cleanText(raw.className, "class", { max: MAX.className });
  const address = cleanText(raw.address, "address", { max: MAX.address });
  const gender = raw.gender == null || raw.gender === "" ? null : raw.gender;
  if (gender != null && !["Male", "Female", "Other"].includes(gender)) {
    throw new Error("Invalid gender.");
  }

  return {
    slug,
    fullName,
    phone: normalizeBangladeshMobile(raw.phone, "phone number", true),
    guardianName,
    whatsappNumber: normalizeBangladeshMobile(raw.whatsappNumber, "WhatsApp number"),
    gender,
    dateOfBirthMs: parseBirthDate(raw.dateOfBirth),
    bloodGroup: canonicalBloodGroup(raw.bloodGroup),
    schoolName,
    className,
    address,
  };
}

function stableHash(secret, value) {
  if (typeof secret !== "string" || secret.length < 24) {
    throw new Error("Registration protection is not configured.");
  }
  return crypto.createHmac("sha256", secret).update(value).digest("hex");
}

module.exports = { canonicalBloodGroup, canonicalRegistrationPayload, stableHash };
