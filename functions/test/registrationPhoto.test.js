"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  assetIdForRegistrationPhoto,
  canonicalRegistrationPhoto,
  cleanupPendingRegistrationPhoto,
  materializeRegistrationStudentPhoto,
  parsePendingRegistrationPhoto,
  registrationPhotoObjectPath,
  uploadPendingRegistrationPhoto,
} = require("../src/registrationPhoto");

const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]);
const instituteId = "tenant-a";
const requestId = "reg_0123456789abcdef0123456789abcdef";

function memoryBucket() {
  const objects = new Map();
  return {
    name: "bucket-name",
    objects,
    file(path) {
      return {
        save: async (bytes, options) => {
          if (options.preconditionOpts && options.preconditionOpts.ifGenerationMatch === 0 && objects.has(path)) {
            const error = new Error("already exists");
            error.code = 412;
            throw error;
          }
          objects.set(path, {
            bytes: Buffer.from(bytes),
            metadata: {
              name: path,
              size: String(bytes.length),
              generation: "1",
              contentType: options.metadata.contentType,
              metadata: options.metadata.metadata,
            },
          });
        },
        getMetadata: async () => {
          const item = objects.get(path);
          if (!item) {
            const error = new Error("not found");
            error.code = 404;
            throw error;
          }
          return [item.metadata];
        },
        download: async () => {
          const item = objects.get(path);
          if (!item) {
            const error = new Error("not found");
            error.code = 404;
            throw error;
          }
          return [Buffer.from(item.bytes)];
        },
        delete: async () => { objects.delete(path); },
      };
    },
  };
}

test("public registration photo accepts only a bounded JPEG data URL", () => {
  const photo = canonicalRegistrationPhoto(`data:image/jpeg;base64,${jpeg.toString("base64")}`);
  assert.equal(photo.byteLength, jpeg.length);
  assert.match(photo.sha256, /^[a-f0-9]{64}$/);
  assert.equal(canonicalRegistrationPhoto(null), null);
  assert.throws(() => canonicalRegistrationPhoto("data:image/png;base64,AA=="), /JPEG/);
  assert.throws(() => canonicalRegistrationPhoto("data:image/jpeg;base64,bm90IGFuIGltYWdl"), /optimized JPEG/);
});

test("pending registration photo paths and final asset ids are deterministic and opaque", () => {
  const photo = canonicalRegistrationPhoto(`data:image/jpeg;base64,${jpeg.toString("base64")}`);
  const path = registrationPhotoObjectPath(instituteId, requestId);
  assert.match(path, /^batchfee-registration\/v1\/[a-f0-9]{20}\/reg_[a-f0-9]{32}\.jpg$/);
  const assetId = assetIdForRegistrationPhoto(instituteId, requestId, photo.sha256);
  assert.match(assetId, /^[a-f0-9]{32}$/);
  assert.equal(assetId, assetIdForRegistrationPhoto(instituteId, requestId, photo.sha256));
  assert.deepEqual(parsePendingRegistrationPhoto({
    storageBucket: "bucket-name",
    storageObjectPath: path,
    contentType: "image/jpeg",
    byteLength: photo.byteLength,
    sha256: photo.sha256,
  }, instituteId, requestId, "bucket-name"), {
    storageObjectPath: path,
    byteLength: photo.byteLength,
    sha256: photo.sha256,
  });
  assert.throws(() => parsePendingRegistrationPhoto({
    storageBucket: "bucket-name",
    storageObjectPath: "outside/the/registration/path.jpg",
    contentType: "image/jpeg",
    byteLength: photo.byteLength,
    sha256: photo.sha256,
  }, instituteId, requestId, "bucket-name"), /invalid/);
});

test("a pending public photo is promoted to private student media and then cleaned up", async () => {
  const bucket = memoryBucket();
  const photo = canonicalRegistrationPhoto(`data:image/jpeg;base64,${jpeg.toString("base64")}`);
  const pending = await uploadPendingRegistrationPhoto({ bucket, instituteId, requestId, photo });
  assert.equal(bucket.objects.has(pending.storageObjectPath), true);
  const result = await materializeRegistrationStudentPhoto({
    bucket,
    instituteId,
    requestId,
    studentId: "student-1",
    pendingPhoto: pending,
  });
  assert.match(result.reference, /^batchfee-media:\/\/v1\/tenant-a\/[a-f0-9]{32}$/);
  assert.equal(bucket.objects.has(result.storageObjectPath), true);
  await cleanupPendingRegistrationPhoto({ bucket, instituteId, requestId });
  assert.equal(bucket.objects.has(pending.storageObjectPath), false);
});
