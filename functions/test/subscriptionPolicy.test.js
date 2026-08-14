"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { FREE_TRIAL_DURATION_MS, hasUnlimitedTrialStudents } = require("../src/subscriptionPolicy");

test("only a live Free Trial has unlimited student seats", () => {
  const now = Date.UTC(2026, 7, 14);
  assert.equal(FREE_TRIAL_DURATION_MS, 30 * 24 * 60 * 60 * 1000);
  assert.equal(hasUnlimitedTrialStudents({
    currentPlanId: "plan_free_trial",
    subscriptionStatus: "trial",
    currentPeriodEndMs: now + FREE_TRIAL_DURATION_MS,
    isActive: true,
  }, now), true);
  assert.equal(hasUnlimitedTrialStudents({
    currentPlanId: "plan_growth",
    subscriptionStatus: "trial",
    currentPeriodEndMs: now + FREE_TRIAL_DURATION_MS,
    isActive: true,
  }, now), false);
  assert.equal(hasUnlimitedTrialStudents({
    currentPlanId: "plan_free_trial",
    subscriptionStatus: "trial",
    currentPeriodEndMs: now,
    isActive: true,
  }, now), false);
});
