"use strict";

const { requestHash } = require("./financialLedgerCore");

const RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const ALLOWED_ACTIONS = new Set(["archive", "restore"]);
const ALLOWED_ENTITY_TYPES = new Set(["student", "batch", "staff", "institute"]);

function validateDeletionRequest(data) {
  const value = data && typeof data === "object" ? data : {};
  const operationId = requiredString(value, "operationId", 128);
  const instituteId = requiredString(value, "instituteId", 128);
  const entityType = requiredString(value, "entityType", 20).toLowerCase();
  const entityId = requiredString(value, "entityId", 128);
  const action = requiredString(value, "action", 20).toLowerCase();
  const reason = requiredString(value, "reason", 500);

  if (!/^[A-Za-z0-9_-]{16,128}$/.test(operationId)) {
    throw new TypeError("Invalid operationId.");
  }
  if (!ALLOWED_ACTIONS.has(action) || !ALLOWED_ENTITY_TYPES.has(entityType)) {
    throw new TypeError("Unsupported deletion operation.");
  }
  if (entityType === "institute" && entityId !== instituteId) {
    throw new TypeError("Institute deletion target is invalid.");
  }
  if (reason.length < 3) throw new TypeError("A deletion or recovery reason is required.");

  return { operationId, instituteId, entityType, entityId, action, reason };
}

function requiredString(data, field, maxLength) {
  const value = typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) throw new TypeError(`Invalid ${field}.`);
  return value;
}

function deletionStateId(entityType, entityId) {
  return `${entityType}_${entityId}`;
}

function canonicalDeletionRequest(data) {
  const parsed = validateDeletionRequest(data);
  return { ...parsed, requestHash: requestHash(parsed) };
}

function retainedUntil(now) {
  if (!Number.isSafeInteger(now) || now < 0) throw new TypeError("Invalid deletion timestamp.");
  return now + RETENTION_MS;
}

module.exports = {
  ALLOWED_ACTIONS,
  ALLOWED_ENTITY_TYPES,
  RETENTION_MS,
  canonicalDeletionRequest,
  deletionStateId,
  retainedUntil,
  validateDeletionRequest,
};
