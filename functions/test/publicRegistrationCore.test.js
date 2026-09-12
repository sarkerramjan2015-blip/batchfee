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
    bloodGroup: "ab-",
    schoolName: "Dhaka Model School",
    className: "Class 10",
    address: "Mirpur, Dhaka",
  });
  assert.equal(result.phone, "+8801712345678");
  assert.equal(result.whatsappNumber, "+8801812345678");
  assert.equal(result.fullName, "Rahat Hossain");
  assert.equal(result.dateOfBirthMs, Date.UTC(2010, 1, 18));
  assert.equal(result.bloodGroup, "AB-");
});

test("public registration accepts the Bengali slugs created for Bengali institute names", () => {
  const result = canonicalRegistrationPayload({
    slug: "\u09b8\u09be\u09ab\u09b2\u09cd\u09af-\u098f\u0995\u09be\u09a1\u09c7\u09ae\u09bf-abc123",
    fullName: "Karim",
    phone: "01540140464",
    guardianName: "Din Islam",
    whatsappNumber: "01540140464",
  });

  assert.equal(result.slug, "\u09b8\u09be\u09ab\u09b2\u09cd\u09af-\u098f\u0995\u09be\u09a1\u09c7\u09ae\u09bf-abc123");
  assert.equal(result.phone, "+8801540140464");
  assert.equal(result.whatsappNumber, "+8801540140464");
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
  assert.throws(() => canonicalRegistrationPayload({
    slug: "bright-coaching-abc123",
    fullName: "Rahat",
    phone: "01712345678",
    bloodGroup: "A++",
  }));
});

test("rate-limit keys are deterministic but require a substantial secret", () => {
  assert.equal(stableHash("a secure local testing secret that is long enough", "source"),
    stableHash("a secure local testing secret that is long enough", "source"));
  assert.throws(() => stableHash("short", "source"));
});
