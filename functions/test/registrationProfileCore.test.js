"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { buildRegistrationSlug, registrationFormUrl } = require("../src/registrationProfileCore");

test("registration slug is deterministic, readable and tenant-specific", () => {
  assert.equal(buildRegistrationSlug(" ICT Toppers ", "owner-ABC123"), "ict-toppers-abc123");
  assert.equal(buildRegistrationSlug("নাজমুল টিউটোরিয়াল হোম", "owner-ABC123"),
    buildRegistrationSlug("নাজমুল টিউটোরিয়াল হোম", "owner-ABC123"));
  assert.notEqual(buildRegistrationSlug("ICT Toppers", "owner-ABC123"),
    buildRegistrationSlug("ICT Toppers", "owner-XYZ999"));
});

test("registration URL encodes the slug as one safe path segment", () => {
  assert.equal(
    registrationFormUrl("ict toppers/abc"),
    "https://batchfee-477b8.web.app/register/ict%20toppers%2Fabc",
  );
});
