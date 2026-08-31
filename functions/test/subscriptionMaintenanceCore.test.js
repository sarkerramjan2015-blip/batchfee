"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  mapWithConcurrency,
  subscriptionEntitlementPatch,
} = require("../src/subscriptionMaintenanceCore");

const DAY_MS = 24 * 60 * 60 * 1000;

test("subscription repair preserves existing semantics while filling missing entitlements", () => {
  const now = 2_000_000;
  const result = subscriptionEntitlementPatch({
    institute: { createdAtMs: now - DAY_MS },
    plan: { maxStudents: 25, maxUsers: 2 },
    now,
    freeTrialDurationMs: 30 * DAY_MS,
    freeTrialStudentLimit: -1,
  });
  assert.deepEqual(result, {
    planId: "plan_free_trial",
    patch: {
      isActive: true,
      currentPeriodEndMs: now - DAY_MS + (30 * DAY_MS),
      currentPlanId: "plan_free_trial",
      subscriptionStatus: "trial",
      studentLimit: -1,
      staffLimit: 2,
    },
  });
});

test("subscription repair keeps an explicit blocked status and paid seat limit", () => {
  const result = subscriptionEntitlementPatch({
    institute: {
      currentPlanId: "plan_paid",
      currentPeriodEndMs: 10_000,
      subscriptionStatus: "blocked",
      isActive: false,
      studentLimit: 75,
      staffLimit: 4,
    },
    plan: { maxStudents: 100, maxUsers: 5 },
    now: 5_000,
    freeTrialDurationMs: 30 * DAY_MS,
    freeTrialStudentLimit: -1,
  });
  assert.deepEqual(result.patch, {});
});

test("maintenance workers never exceed the requested concurrency", async () => {
  let active = 0;
  let peak = 0;
  const results = await mapWithConcurrency([1, 2, 3, 4, 5, 6], 2, async (value) => {
    active += 1;
    peak = Math.max(peak, active);
    await new Promise((resolve) => setTimeout(resolve, 2));
    active -= 1;
    return value * 2;
  });
  assert.equal(peak, 2);
  assert.deepEqual(results, [2, 4, 6, 8, 10, 12]);
});

test("maintenance workers process a thousand records exactly once with bounded concurrency", async () => {
  const items = Array.from({ length: 1000 }, (_, index) => index);
  const visits = new Uint8Array(items.length);
  let active = 0;
  let peak = 0;
  const results = await mapWithConcurrency(items, 10, async (value, index) => {
    active += 1;
    peak = Math.max(peak, active);
    visits[index] += 1;
    if (index % 25 === 0) await new Promise((resolve) => setImmediate(resolve));
    active -= 1;
    return value + 1;
  });

  assert.equal(peak <= 10, true);
  assert.equal(visits.every((count) => count === 1), true);
  assert.equal(results.length, 1000);
  assert.equal(results[0], 1);
  assert.equal(results[999], 1000);
});
