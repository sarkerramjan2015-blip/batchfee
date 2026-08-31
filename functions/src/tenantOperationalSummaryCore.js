"use strict";

const OPERATIONAL_COLLECTIONS = Object.freeze({
  students: "studentCount",
  batches: "batchCount",
  staffs: "staffCount",
});

function snapshotData(snapshot) {
  if (!snapshot || snapshot.exists !== true || typeof snapshot.data !== "function") return null;
  return snapshot.data() || null;
}

function activeContribution(snapshot) {
  const data = snapshotData(snapshot);
  return data && data.status === "active" && data.archivedAtMs == null ? 1 : 0;
}

function shouldRefreshOperationalSummary(before, after) {
  return activeContribution(before) !== activeContribution(after);
}

function eventTimeKey(event) {
  if (typeof event?.time === "string" && event.time) return event.time;
  return new Date(0).toISOString();
}

function isStaleSummaryEvent(summary, event) {
  const previous = typeof summary?.sourceEventTime === "string" ? summary.sourceEventTime : "";
  return previous !== "" && previous > eventTimeKey(event);
}

function normalizeCount(value) {
  const count = Number(value);
  return Number.isSafeInteger(count) && count >= 0 ? count : 0;
}

function summaryPayload({ studentCount, batchCount, staffCount, event, updatedAtMs }) {
  return {
    studentCount: normalizeCount(studentCount),
    batchCount: normalizeCount(batchCount),
    staffCount: normalizeCount(staffCount),
    sourceEventId: typeof event?.id === "string" ? event.id : "",
    sourceEventTime: eventTimeKey(event),
    updatedAtMs: normalizeCount(updatedAtMs),
  };
}

module.exports = {
  OPERATIONAL_COLLECTIONS,
  activeContribution,
  eventTimeKey,
  isStaleSummaryEvent,
  normalizeCount,
  shouldRefreshOperationalSummary,
  summaryPayload,
};
