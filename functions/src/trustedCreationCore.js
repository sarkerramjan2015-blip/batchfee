"use strict";

const { createHash } = require("node:crypto");

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.keys(value).sort().reduce((output, key) => {
      output[key] = canonicalize(value[key]);
      return output;
    }, {});
  }
  return value;
}

function trustedCreationHash(value) {
  return createHash("sha256").update(JSON.stringify(canonicalize(value))).digest("hex");
}

function resolveTrustedCreationReplay(operation, actorUid, requestHash) {
  if (!operation) return { kind: "new" };
  if (operation.actorUid !== actorUid || operation.requestHash !== requestHash) {
    return { kind: "conflict" };
  }
  if (operation.status !== "completed" || !operation.result) {
    return { kind: "incomplete" };
  }
  return { kind: "replay", result: operation.result };
}

module.exports = { canonicalize, resolveTrustedCreationReplay, trustedCreationHash };
