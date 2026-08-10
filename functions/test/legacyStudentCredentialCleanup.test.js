"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  LEGACY_STUDENT_CREDENTIAL_FIELDS,
  hasLegacyStudentCredentialFields,
  isLegacyStudentEmail,
} = require("../src/legacyStudentCredentialCleanup");

test("detects every blocked legacy credential field, including null-valued fields", () => {
  for (const field of LEGACY_STUDENT_CREDENTIAL_FIELDS) {
    assert.equal(hasLegacyStudentCredentialFields({ [field]: null }), true, field);
  }
  assert.equal(hasLegacyStudentCredentialFields({ fullName: "Safe Student" }), false);
  assert.equal(hasLegacyStudentCredentialFields(null), false);
});

test("legacy virtual-email detection is domain-scoped", () => {
  assert.equal(isLegacyStudentEmail("student-01@s.batchfee.app"), true);
  assert.equal(isLegacyStudentEmail(" STUDENT-01@S.BATCHFEE.APP "), true);
  assert.equal(isLegacyStudentEmail("owner@batchfee.app"), false);
  assert.equal(isLegacyStudentEmail("attacker@s.batchfee.app.example"), false);
  assert.equal(isLegacyStudentEmail(null), false);
});

test("apply mode refuses partial cleanup without legacy Auth revocation", async () => {
  const { cleanupLegacyStudentCredentials } = require("../src/legacyStudentCredentialCleanup");
  await assert.rejects(
    cleanupLegacyStudentCredentials({ db: {}, auth: {}, dryRun: false }),
    /requires legacy virtual-email Auth users to be disabled and revoked/,
  );
});
