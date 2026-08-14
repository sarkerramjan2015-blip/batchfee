"use strict";

const { describe, test } = require("node:test");
const assert = require("node:assert/strict");
const {
  buildMediaReference,
  canonicalUploadRequest,
  parseMediaReference,
  storageObjectPath,
} = require("../src/mediaSecurityCore");

const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]);

describe("P0-07 media security core", () => {
  test("private references round-trip without exposing a public URL", () => {
    const reference = buildMediaReference("tenant/unsafe".replace("/", "-"), "a".repeat(32));
    assert.deepEqual(parseMediaReference(reference), {
      instituteId: "tenant-unsafe",
      assetId: "a".repeat(32),
    });
    assert.equal(parseMediaReference("https://firebasestorage.googleapis.com/v0/b/demo/o/logo.jpg"), null);
    assert.equal(parseMediaReference("batchfee-media://v1/tenant/../../secret"), null);
  });

  test("upload request accepts bounded JPEG data and derives an idempotent asset id", () => {
    const request = {
      instituteId: "tenant-a",
      purpose: "student_photo",
      operationId: "operation_1234567890",
      subjectId: "student-1",
      imageBase64: jpeg.toString("base64"),
    };
    const first = canonicalUploadRequest(request);
    const second = canonicalUploadRequest(request);
    assert.equal(first.assetId, second.assetId);
    assert.equal(first.sha256, second.sha256);
    assert.equal(first.isPrivate, true);
    assert.equal(first.byteLength, jpeg.length);
  });

  test("logo gets an opaque Firebase Storage path but still requires a trusted upload", () => {
    const parsed = canonicalUploadRequest({
      instituteId: "tenant-a",
      purpose: "institute_logo",
      operationId: "operation_abcdefghij",
      imageBase64: jpeg.toString("base64"),
    });
    assert.equal(parsed.isPrivate, false);
    assert.match(storageObjectPath("tenant-a", parsed.purpose, parsed.assetId, false),
      /^batchfee-media\/v1\/public\/[a-f0-9]{20}\/institute_logo\/[a-f0-9]{32}\.jpg$/);
  });

  test("rejects unsupported purposes, malformed bytes, oversized identifiers, and unsafe replacements", () => {
    const base = {
      instituteId: "tenant-a",
      purpose: "student_photo",
      operationId: "operation_1234567890",
      subjectId: "student-1",
      imageBase64: jpeg.toString("base64"),
    };
    assert.throws(() => canonicalUploadRequest({ ...base, purpose: "receipt" }), /purpose/);
    assert.throws(() => canonicalUploadRequest({ ...base, subjectId: null }), /ownership/);
    assert.throws(() => canonicalUploadRequest({
      ...base,
      imageBase64: Buffer.from("not an image").toString("base64"),
    }), /optimized JPEG/);
    assert.throws(() => canonicalUploadRequest({ ...base, instituteId: "x/y" }), /instituteId/);
    assert.throws(() => canonicalUploadRequest({ ...base, replacesReference: "http://unsafe.test/a" }),
      /replacesReference/);
  });
});
