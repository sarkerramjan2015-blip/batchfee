"use strict";

const assert = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { createMediaSecurityHandlers } = require("../src/mediaSecurity");

const projectId = process.env.GCLOUD_PROJECT || "demo-batchfee-media";
if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run this test through the Firestore emulator.");
}

initializeApp({ projectId });
const db = getFirestore();

class FakeStorageFile {
  constructor(bucket, name) {
    this.bucket = bucket;
    this.name = name;
  }

  async save(bytes, options) {
    if (this.bucket.objects.has(this.name)) {
      const error = new Error("precondition failed");
      error.code = 412;
      throw error;
    }
    this.bucket.objects.set(this.name, {
      metadata: {
        name: this.name,
        bucket: this.bucket.name,
        generation: String(this.bucket.objects.size + 1),
        size: String(bytes.length),
        contentType: options.metadata.contentType,
        metadata: { ...options.metadata.metadata },
      },
    });
  }

  async getMetadata() {
    const object = this.bucket.objects.get(this.name);
    if (!object) {
      const error = new Error("not found");
      error.code = 404;
      throw error;
    }
    return [object.metadata];
  }

  async getSignedUrl(options) {
    assert.equal(options.version, "v4");
    assert.equal(options.action, "read");
    assert.equal(options.responseDisposition, "inline");
    return [`https://storage.googleapis.test/${this.bucket.name}/${this.name}?signed=1`];
  }
}

class FakeStorageBucket {
  constructor() {
    this.name = "batchfee-media-test.firebasestorage.app";
    this.objects = new Map();
  }

  file(name) {
    return new FakeStorageFile(this, name);
  }
}

const bucket = new FakeStorageBucket();
const handlers = createMediaSecurityHandlers({
  db,
  bucket,
});
const jpegBase64 = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]).toString("base64");
const instituteA = "media-owner-a";
const instituteB = "media-owner-b";
const studentA = "media-student-a";

function ownerRequest(instituteId, operationId, purpose, extra = {}) {
  return {
    auth: { uid: instituteId, token: {} },
    data: { instituteId, operationId, purpose, imageBase64: jpegBase64, ...extra },
  };
}

async function expectCode(code, work) {
  let caught;
  try {
    await work();
  } catch (error) {
    caught = error;
  }
  assert.equal(caught && caught.code, code, caught && caught.stack);
}

async function main() {
  for (const instituteId of [instituteA, instituteB]) {
    await db.collection("institutes").doc(instituteId).set({
      instituteName: instituteId,
      isActive: true,
      status: "active",
    });
  }
  await db.collection("institutes").doc(instituteA).collection("students").doc(studentA).set({
    instituteId: instituteA,
    status: "active",
    archivedAtMs: null,
  });

  const first = await handlers.uploadSecureMedia(ownerRequest(
    instituteA,
    "media-operation-private-0001",
    "student_photo",
    { subjectId: studentA },
  ));
  assert.match(first.reference, /^batchfee-media:\/\/v1\/media-owner-a\/[a-f0-9]{32}$/);
  assert.equal(first.private, true);
  const firstAsset = await db.collection("institutes").doc(instituteA)
    .collection("media_assets").doc(first.assetId).get();
  assert.equal(firstAsset.get("deliveryType"), "firebase_storage_signed_url");
  assert.equal(firstAsset.get("uploadedByUid"), instituteA);
  assert.equal(firstAsset.get("status"), "active");
  assert.match(firstAsset.get("storageObjectPath"), /^batchfee-media\/v1\/private\//);

  const idempotent = await handlers.uploadSecureMedia(ownerRequest(
    instituteA,
    "media-operation-private-0001",
    "student_photo",
    { subjectId: studentA },
  ));
  assert.deepEqual(idempotent, first);
  assert.equal(bucket.objects.size, 1);

  await db.collection("institutes").doc(instituteA).collection("students").doc(studentA)
    .update({ photoUri: first.reference });
  const studentAuth = {
    uid: "firebase-student-a",
    token: {
      studentManaged: true,
      student: true,
      instituteId: instituteA,
      studentId: studentA,
      studentSessionExpiresAt: Date.now() + 60_000,
    },
  };
  const delivery = await handlers.getSecureMediaUrl({
    auth: studentAuth,
    data: { reference: first.reference },
  });
  assert.match(delivery.url, /^https:\/\/storage\.googleapis\.test\//);
  assert.ok(delivery.expiresAtMs > Date.now());

  const unsupportedAssetId = "b".repeat(32);
  const unsupportedReference = `batchfee-media://v1/${instituteA}/${unsupportedAssetId}`;
  await db.collection("institutes").doc(instituteA).collection("media_assets").doc(unsupportedAssetId).set({
    instituteId: instituteA,
    assetId: unsupportedAssetId,
    reference: unsupportedReference,
    deliveryType: "unsupported_legacy_private",
    format: "jpg",
    purpose: "student_photo",
    subjectId: studentA,
    status: "active",
  });
  await db.collection("institutes").doc(instituteA).collection("students").doc(studentA)
    .update({ photoUri: unsupportedReference });
  await expectCode("permission-denied", () => handlers.getSecureMediaUrl({
    auth: studentAuth,
    data: { reference: unsupportedReference },
  }));
  await db.collection("institutes").doc(instituteA).collection("students").doc(studentA)
    .update({ photoUri: first.reference });

  await db.collection("institutes").doc(instituteA).collection("students").doc("another-student")
    .set({ instituteId: instituteA, status: "active", archivedAtMs: null, photoUri: first.reference });

  await expectCode("permission-denied", () => handlers.getSecureMediaUrl({
    auth: { uid: instituteB, token: {} },
    data: { reference: first.reference },
  }));
  await expectCode("permission-denied", () => handlers.getSecureMediaUrl({
    auth: {
      uid: "another-student",
      token: { ...studentAuth.token, studentId: "another-student" },
    },
    data: { reference: first.reference },
  }));

  const replacement = await handlers.uploadSecureMedia(ownerRequest(
    instituteA,
    "media-operation-private-0002",
    "student_photo",
    { subjectId: studentA, replacesReference: first.reference },
  ));
  assert.notEqual(replacement.assetId, first.assetId);
  const retainedOld = await db.collection("institutes").doc(instituteA)
    .collection("media_assets").doc(first.assetId).get();
  assert.equal(retainedOld.get("status"), "superseded");
  assert.equal(retainedOld.get("cleanupState"), "retained");
  assert.ok(retainedOld.get("retentionUntilMs") > Date.now());

  const logo = await handlers.uploadSecureMedia(ownerRequest(
    instituteA,
    "media-operation-public-0001",
    "institute_logo",
  ));
  assert.match(logo.reference, /^https:\/\/firebasestorage\.googleapis\.com\/v0\/b\//);
  assert.equal(logo.private, false);
  assert.equal(bucket.objects.size, 3);
  console.log("Firebase Storage media integration: PASS");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
