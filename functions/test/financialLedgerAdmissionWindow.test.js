"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  hasEligibleCoveredMonth,
  enrollmentBillingStartPeriodKey,
} = require("../src/financialLedger");

const period = (year, monthIndex) => year * 12 + monthIndex;

test("legacy enrollment starts from admission rather than a later sync timestamp", () => {
  const admission = period(2026, 5); // Jun 2026
  const start = enrollmentBillingStartPeriodKey({ joinedAtMs: 123 }, admission);
  const window = [{ startPeriodKey: start, endPeriodKey: null }];

  assert.equal(hasEligibleCoveredMonth([period(2026, 3)], window), false); // Apr
  assert.equal(hasEligibleCoveredMonth([period(2026, 4)], window), false); // May
  assert.equal(hasEligibleCoveredMonth([period(2026, 5)], window), true); // Jun
  assert.equal(hasEligibleCoveredMonth([period(2026, 6)], window), true); // Jul
});

test("a shifted enrollment keeps its explicit frozen first month", () => {
  const start = enrollmentBillingStartPeriodKey(
    { firstMonthFeePeriod: "Aug 2026" },
    period(2026, 5),
  );
  const window = [{ startPeriodKey: start, endPeriodKey: null }];

  assert.equal(hasEligibleCoveredMonth([period(2026, 6)], window), false); // Jul
  assert.equal(hasEligibleCoveredMonth([period(2026, 7)], window), true); // Aug
});

test("a mixed legacy range is never cancelled when it still contains one valid month", () => {
  const window = [{ startPeriodKey: period(2026, 6), endPeriodKey: null }];

  assert.equal(
    hasEligibleCoveredMonth([period(2026, 5), period(2026, 6)], window),
    true,
  );
});

test("a removed batch stops billing before its departure month", () => {
  const window = [{ startPeriodKey: period(2026, 5), endPeriodKey: period(2026, 8) }]; // Jun through Aug

  assert.equal(hasEligibleCoveredMonth([period(2026, 7)], window), true); // Aug
  assert.equal(hasEligibleCoveredMonth([period(2026, 8)], window), false); // Sep
});
