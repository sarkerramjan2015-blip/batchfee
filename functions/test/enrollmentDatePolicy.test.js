"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { isAdmissionLinkedEnrollment } = require("../src/enrollmentDatePolicy");

test("explicit later assignment never follows admission, even on the same day", () => {
  assert.equal(isAdmissionLinkedEnrollment({ admissionDateLinked: false, joinedAtMs: 100 }, 100), false);
});
test("explicit first assignment follows admission corrections", () => {
  assert.equal(isAdmissionLinkedEnrollment({ admissionDateLinked: true, joinedAtMs: 200 }, 100), true);
});
test("legacy frozen assignments are not linked by matching month alone", () => {
  assert.equal(isAdmissionLinkedEnrollment({ firstMonthFeePeriod: "Sep 2026", joinedAtMs: 200 }, 100), false);
  assert.equal(isAdmissionLinkedEnrollment({ firstMonthFeePeriod: "Sep 2026", joinedAtMs: 100 }, 100), true);
  assert.equal(isAdmissionLinkedEnrollment({ joinedAtMs: 200 }, 100), true);
});
