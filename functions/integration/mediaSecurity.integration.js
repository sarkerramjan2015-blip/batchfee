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

class FakeCloudinary {
  constructor() {
    this.resources = new Map();
    this.uploader = {
      upload: async (_dataUri, options) => {
        if (this.resources.has(options.public_id)) {
          const error = new Error("already exists");
          error.http_code = 409;
          throw error;
        }
        const result = {
          public_id: options.public_id,
          asset_id: `cloud-${this.resources.size + 1}`,
          version: this.resources.size + 1,
          format: "jpg",
          bytes: 9,
          type: options.type,
          secure_url: `https://res.cloudinary.com/test/image/${options.type}/${options.public_id}.jpg`,
        };
        this.resources.set(options.public_id, result);
        return result;
      },
    };
    this.api = {
      resource: async (publicId) => this.resources.get(publicId),
    };
    this.utils = {
      private_download_url: (publicId, format, options) => {
        assert.equal(options.type, "authenticated");
        assert.equal(options.attachment, false);
        return `https://api.cloudinary.test/download/${publicId}.${format}?expires_at=${options.expires_at}&signature=test`;
      },
    };
  }
}

const cloudinary = new FakeCloudinary();
const handlers = createMediaSecurityHandlers({ db, getCloudinary: () => cloudinary });
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
  assert.equal(firstAsset.get("deliveryType"), "authenticated");
  assert.equal(firstAsset.get("uploadedByUid"), instituteA);
  assert.equal(firstAsset.get("status"), "active");

  const idempotent = await handlers.uploadSecureMedia(ownerRequest(
    instituteA,
    "media-operation-private-0001",
    "student_photo",
    { subjectId: studentA },
  ));
  assert.deepEqual(idempotent, first);
  assert.equal(cloudinary.resources.size, 1);

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
  assert.match(delivery.url, /^https:\/\/api\.cloudinary\.test\/download\//);
  assert.ok(delivery.expiresAtMs > Date.now());

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
  assert.match(logo.reference, /^https:\/\/res\.cloudinary\.com\//);
  assert.equal(logo.private, false);

  const assetWriteProbe = await db.collection("institutes").doc(instituteA)
    .collection("media_assets").get();
  assert.equal(assetWriteProbe.size, 3);
  console.log("P0-07 media integration: PASS");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
