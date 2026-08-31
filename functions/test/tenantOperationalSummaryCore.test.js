"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  activeContribution,
  isStaleSummaryEvent,
  shouldRefreshOperationalSummary,
  summaryPayload,
} = require("../src/tenantOperationalSummaryCore");

function snapshot(data) {
  return data == null
    ? { exists: false, data: () => undefined }
    : { exists: true, data: () => structuredClone(data) };
}

test("only active, non-archived records contribute to trusted counts", () => {
  assert.equal(activeContribution(snapshot({ status: "active", archivedAtMs: null })), 1);
  assert.equal(activeContribution(snapshot({ status: "active", archivedAtMs: 123 })), 0);
  assert.equal(activeContribution(snapshot({ status: "archived", archivedAtMs: 123 })), 0);
  assert.equal(activeContribution(snapshot(null)), 0);
});

test("profile-only writes do not trigger an aggregate recount", () => {
  const before = snapshot({ status: "active", archivedAtMs: null, fullName: "Before" });
  const after = snapshot({ status: "active", archivedAtMs: null, fullName: "After" });
  assert.equal(shouldRefreshOperationalSummary(before, after), false);
  assert.equal(shouldRefreshOperationalSummary(snapshot(null), after), true);
  assert.equal(shouldRefreshOperationalSummary(after, snapshot({ status: "archived", archivedAtMs: 1 })), true);
});

test("older CloudEvents cannot overwrite a newer trusted summary", () => {
  const summary = { sourceEventTime: "2026-08-31T12:00:01.000000Z" };
  assert.equal(isStaleSummaryEvent(summary, { time: "2026-08-31T12:00:00.000000Z" }), true);
  assert.equal(isStaleSummaryEvent(summary, { time: "2026-08-31T12:00:02.000000Z" }), false);
});

test("summary payload clamps invalid counts and records event ordering", () => {
  assert.deepEqual(summaryPayload({
    studentCount: 12,
    batchCount: -1,
    staffCount: "3",
    event: { id: "event-1", time: "2026-08-31T12:00:00.000000Z" },
    updatedAtMs: 1234,
  }), {
    studentCount: 12,
    batchCount: 0,
    staffCount: 3,
    sourceEventId: "event-1",
    sourceEventTime: "2026-08-31T12:00:00.000000Z",
    updatedAtMs: 1234,
  });
});
