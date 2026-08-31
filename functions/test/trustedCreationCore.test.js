"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  resolveTrustedCreationReplay,
  trustedCreationHash,
} = require("../src/trustedCreationCore");

test("trusted creation hashes are stable across object key ordering", () => {
  assert.equal(
    trustedCreationHash({ kind: "batch", entity: { name: "A", fee: 100 } }),
    trustedCreationHash({ entity: { fee: 100, name: "A" }, kind: "batch" }),
  );
});

test("only the same actor and payload can replay a completed creation", () => {
  const operation = {
    actorUid: "owner-a",
    requestHash: "hash-a",
    status: "completed",
    result: { batchId: "batch-a" },
  };
  assert.deepEqual(resolveTrustedCreationReplay(operation, "owner-a", "hash-a"), {
    kind: "replay",
    result: { batchId: "batch-a" },
  });
  assert.equal(resolveTrustedCreationReplay(operation, "owner-b", "hash-a").kind, "conflict");
  assert.equal(resolveTrustedCreationReplay(operation, "owner-a", "hash-b").kind, "conflict");
  assert.equal(resolveTrustedCreationReplay(null, "owner-a", "hash-a").kind, "new");
});

test("an unfinished operation fails closed instead of creating a duplicate", () => {
  assert.equal(resolveTrustedCreationReplay({
    actorUid: "owner-a",
    requestHash: "hash-a",
    status: "pending",
  }, "owner-a", "hash-a").kind, "incomplete");
});
