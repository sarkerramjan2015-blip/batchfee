"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  FREE_TRIAL_DURATION_MS,
  FREE_TRIAL_STUDENT_LIMIT,
  hasCurrentSubscription,
  hasUnlimitedTrialStudents,
} = require("../src/subscriptionPolicy");

test("public registration is available only for a current subscription", () => {
  const now = Date.UTC(2026, 7, 30);
  assert.equal(hasCurrentSubscription({
    subscriptionStatus: "trial",
    currentPeriodEndMs: now + 1,
    isActive: true,
  }, now), true);
  assert.equal(hasCurrentSubscription({
    subscriptionStatus: "active",
    currentPeriodEndMs: now + 1,
  }, now), true);
  assert.equal(hasCurrentSubscription({
    subscriptionStatus: "active",
    currentPeriodEndMs: now,
  }, now), false);
  assert.equal(hasCurrentSubscription({
    subscriptionStatus: "active",
    currentPeriodEndMs: now + 1,
    deletionState: "retained",
  }, now), false);
  assert.equal(hasCurrentSubscription({
    currentPeriodEndMs: now + 1,
    isActive: true,
  }, now), false);
});

test("only a live Free Trial has unlimited student seats", () => {
  const now = Date.UTC(2026, 7, 14);
  assert.equal(FREE_TRIAL_DURATION_MS, 30 * 24 * 60 * 60 * 1000);
  assert.equal(FREE_TRIAL_STUDENT_LIMIT, 0);
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
