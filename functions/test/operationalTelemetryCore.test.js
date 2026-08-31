"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  callableTelemetryContext,
  isSlowCallable,
  rejectionLogLevel,
  scheduledHealthPatch,
} = require("../src/operationalTelemetryCore");

test("callable telemetry keeps only bounded non-secret identifiers", () => {
  const context = callableTelemetryContext({
    operation: "staff_update",
    correlationId: "correlation-1",
    durationMs: 123.4,
    request: {
      auth: { uid: "owner-1" },
      data: {
        instituteId: "institute-1",
        registrationRequestId: "registration-1",
        operationId: "operation-1",
        password: "must-not-be-logged",
        phone: "+8801000000000",
      },
    },
  });

  assert.deepEqual(context, {
    operation: "staff_update",
    correlationId: "correlation-1",
    durationMs: 123,
    authenticated: true,
    actorUid: "owner-1",
    instituteId: "institute-1",
    registrationRequestId: "registration-1",
    operationId: "operation-1",
  });
  assert.equal("password" in context, false);
  assert.equal("phone" in context, false);
});
test("unexpected authorization and service rejections are warning-level", () => {
  assert.equal(rejectionLogLevel("invalid-argument"), "info");
  assert.equal(rejectionLogLevel("not-found"), "info");
  assert.equal(rejectionLogLevel("permission-denied"), "warn");
  assert.equal(rejectionLogLevel("unauthenticated"), "warn");
  assert.equal(rejectionLogLevel("resource-exhausted"), "warn");
});

test("slow callable threshold is deterministic", () => {
  assert.equal(isSlowCallable(2499), false);
  assert.equal(isSlowCallable(2500), true);
});

test("scheduled health patch records completion without an unbounded history", () => {
  assert.deepEqual(scheduledHealthPatch({
    status: "healthy",
    startedAtMs: 1000,
    finishedAtMs: 1450,
    metrics: { scanned: 10, changed: 2 },
  }), {
    status: "healthy",
    lastStartedAtMs: 1000,
    updatedAtMs: 1450,
    durationMs: 450,
    lastCompletedAtMs: 1450,
    metrics: { scanned: 10, changed: 2 },
  });
});
