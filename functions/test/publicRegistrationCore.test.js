"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { canonicalRegistrationPayload, stableHash } = require("../src/publicRegistrationCore");

test("public registration validation normalizes a valid Bangladesh submission", () => {
  const result = canonicalRegistrationPayload({
    slug: "bright-coaching-abc123",
    fullName: "  Rahat  Hossain ",
    phone: "01712-345678",
    guardianName: "Md. Hossain",
    whatsappNumber: "+880 1812 345678",
    gender: "Male",
    dateOfBirth: "2010-02-18",
    schoolName: "Dhaka Model School",
    className: "Class 10",
    address: "Mirpur, Dhaka",
  });
  assert.equal(result.phone, "+8801712345678");
  assert.equal(result.whatsappNumber, "+8801812345678");
  assert.equal(result.fullName, "Rahat Hossain");
  assert.equal(result.dateOfBirthMs, Date.UTC(2010, 1, 18));
});

test("public registration validation rejects unsafe or malformed input", () => {
  assert.throws(() => canonicalRegistrationPayload({
    slug: "../private-institute",
    fullName: "Rahat",
    phone: "01712345678",
  }));
  assert.throws(() => canonicalRegistrationPayload({
    slug: "bright-coaching-abc123",
    fullName: "<script>",
    phone: "01712345678",
  }));
  assert.throws(() => canonicalRegistrationPayload({
    slug: "bright-coaching-abc123",
    fullName: "Rahat",
    phone: "0171234567",
  }));
});

test("rate-limit keys are deterministic but require a substantial secret", () => {
  assert.equal(stableHash("a secure local testing secret that is long enough", "source"),
    stableHash("a secure local testing secret that is long enough", "source"));
  assert.throws(() => stableHash("short", "source"));
});
