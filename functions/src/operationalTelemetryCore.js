"use strict";

const EXPECTED_REJECTION_CODES = new Set([
  "already-exists",
  "failed-precondition",
  "invalid-argument",
  "not-found",
]);

function boundedIdentifier(value, maxLength = 128) {
  return typeof value === "string" && value.trim()
    ? value.trim().slice(0, maxLength)
    : null;
}

function callableTelemetryContext({ request, operation, correlationId, durationMs }) {
  return {
    operation: boundedIdentifier(operation, 96) || "trusted_operation",
    correlationId: boundedIdentifier(correlationId, 96),
    durationMs: Number.isFinite(durationMs) ? Math.max(0, Math.round(durationMs)) : null,
    authenticated: Boolean(request && request.auth && request.auth.uid),
    actorUid: boundedIdentifier(request && request.auth && request.auth.uid, 128),
    instituteId: boundedIdentifier(request && request.data && request.data.instituteId, 128),
    registrationRequestId: boundedIdentifier(
      request && request.data && request.data.registrationRequestId,
      128,
    ),
    operationId: boundedIdentifier(request && request.data && request.data.operationId, 128),
  };
}

function rejectionLogLevel(errorCode) {
  return EXPECTED_REJECTION_CODES.has(errorCode) ? "info" : "warn";
}

function isSlowCallable(durationMs, thresholdMs = 2500) {
  return Number.isFinite(durationMs) && durationMs >= thresholdMs;
}

function scheduledHealthPatch({ status, startedAtMs, finishedAtMs, metrics = null, error = null }) {
  const safeStartedAt = Number.isFinite(startedAtMs) ? Math.round(startedAtMs) : Date.now();
  const safeFinishedAt = Number.isFinite(finishedAtMs) ? Math.round(finishedAtMs) : null;
  const patch = {
    status,
    lastStartedAtMs: safeStartedAt,
    updatedAtMs: safeFinishedAt || safeStartedAt,
  };
  if (safeFinishedAt) {
    patch.durationMs = Math.max(0, safeFinishedAt - safeStartedAt);
    if (status === "healthy") patch.lastCompletedAtMs = safeFinishedAt;
    if (status === "failed") patch.lastFailedAtMs = safeFinishedAt;
  }
  if (metrics && typeof metrics === "object" && !Array.isArray(metrics)) {
    patch.metrics = metrics;
  }
  if (error) {
    patch.lastErrorCode = boundedIdentifier(error.code, 96);
    patch.lastErrorName = boundedIdentifier(error.name, 96);
  }
  return patch;
}

module.exports = {
  boundedIdentifier,
  callableTelemetryContext,
  isSlowCallable,
  rejectionLogLevel,
  scheduledHealthPatch,
};
