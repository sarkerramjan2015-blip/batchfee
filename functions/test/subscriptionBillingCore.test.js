"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  addCalendarMonths,
  maskedTransactionReference,
  quoteForPlan,
  subscriptionStartMs,
  subscriptionStatusFor,
  transactionReferenceHash,
} = require("../src/subscriptionBillingCore");

test("server quote uses the supported durations and the published discounts", () => {
  assert.equal(quoteForPlan(500, 1), 500);
  assert.equal(quoteForPlan(500, 6), 2700);
  assert.equal(quoteForPlan(500, 12), 4800);
  assert.throws(() => quoteForPlan(500, 2), /eligible/);
  assert.throws(() => quoteForPlan(0, 1), /eligible/);
});

test("renewals start after existing paid access and use calendar months", () => {
  const now = Date.UTC(2026, 0, 15, 10, 0, 0);
  const existingEnd = Date.UTC(2026, 2, 31, 10, 0, 0);
  assert.equal(subscriptionStartMs(existingEnd, now), existingEnd);
  assert.equal(subscriptionStartMs(Date.UTC(2025, 11, 1), now), now);
  assert.equal(
    addCalendarMonths(Date.UTC(2026, 0, 31, 10, 0, 0), 1),
    Date.UTC(2026, 1, 28, 10, 0, 0),
  );
});

test("payment references are normalized, hashed, and only their last four characters are retained", () => {
  assert.equal(maskedTransactionReference(" ab-12_cd34 "), "CD34");
  assert.equal(
    transactionReferenceHash("ab-12_cd34"),
    transactionReferenceHash("AB12CD34"),
  );
  assert.throws(() => transactionReferenceHash("short"), /Invalid transaction reference/);
});

test("paid expiry controls the effective status", () => {
  const now = Date.UTC(2026, 5, 1);
  assert.equal(subscriptionStatusFor("plan_pro", now + 1, now), "active");
  assert.equal(subscriptionStatusFor("plan_free_trial", now + 1, now), "trial");
  assert.equal(subscriptionStatusFor("plan_pro", now, now), "expired");
});
