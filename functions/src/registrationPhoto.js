"use strict";

const { createHash } = require("node:crypto");
const {
  PURPOSES,
  buildMediaReference,
  storageObjectPath,
} = require("./mediaSecurityCore");

const MAX_STUDENT_PHOTO_BYTES = PURPOSES.student_photo.maxBytes;
const DATA_URL_PATTERN = /^data:image\/jpeg;base64,([A-Za-z0-9+/]+={0,2})$/;
const REGISTRATION_REQUEST_ID = /^reg_[a-f0-9]{32}$/;

function storageErrorCode(error) {
  return Number(error && (error.code || error.statusCode) || 0);
}

function instituteStorageHash(instituteId) {
  return createHash("sha256").update(instituteId).digest("hex").slice(0, 20);
}

function assertRegistrationIdentity(instituteId, requestId) {
  if (typeof instituteId !== "string" || !instituteId || instituteId.length > 128 || instituteId.includes("/")) {
    throw new Error("Invalid registration institute.");
  }
  if (typeof requestId !== "string" || !REGISTRATION_REQUEST_ID.test(requestId)) {
    throw new Error("Invalid registration request.");
  }
}

function assertStudentPhotoBytes(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 4 || bytes.length > MAX_STUDENT_PHOTO_BYTES ||
      bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) {
    throw new Error("Student photo must be an optimized JPEG within the allowed size.");
  }
}

/**
 * The public form submits an optimized JPEG data URL.  The image remains
 * private: this value is immediately written by trusted code to a temporary
 * Storage object and is never stored in Firestore or returned to the browser.
 */
function canonicalRegistrationPhoto(value) {
  if (value == null || value === "") return null;
  if (typeof value !== "string" || value.length > Math.ceil(MAX_STUDENT_PHOTO_BYTES * 4 / 3) + 32) {
    throw new Error("Invalid student photo.");
  }
  const match = DATA_URL_PATTERN.exec(value);
  if (!match || match[1].length % 4 !== 0) throw new Error("Student photo must be a JPEG image.");
  const bytes = Buffer.from(match[1], "base64");
  assertStudentPhotoBytes(bytes);
  return {
    bytes,
    byteLength: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

function registrationPhotoObjectPath(instituteId, requestId) {
  assertRegistrationIdentity(instituteId, requestId);
  return `batchfee-registration/v1/${instituteStorageHash(instituteId)}/${requestId}.jpg`;
}

function assetIdForRegistrationPhoto(instituteId, requestId, sha256) {
  assertRegistrationIdentity(instituteId, requestId);
  if (typeof sha256 !== "string" || !/^[a-f0-9]{64}$/.test(sha256)) {
    throw new Error("Invalid registration photo checksum.");
  }
  return createHash("sha256")
    .update(`registration-photo:v1:${instituteId}:${requestId}:${sha256}`)
    .digest("hex")
    .slice(0, 32);
}

function pendingPhotoMetadata(instituteId, requestId, photo) {
  return {
    contentType: "image/jpeg",
    cacheControl: "private, max-age=0, no-transform",
    metadata: {
      batchfeeAccess: "registration-pending",
      batchfeeInstituteHash: instituteStorageHash(instituteId),
      batchfeePurpose: "student_photo",
      batchfeeRegistrationRequestId: requestId,
      batchfeeSha256: photo.sha256,
    },
  };
}

function assertPendingObject(metadata, instituteId, requestId, photo) {
  const expectedPath = registrationPhotoObjectPath(instituteId, requestId);
  const custom = metadata && metadata.metadata && typeof metadata.metadata === "object" ? metadata.metadata : {};
  if (!metadata || metadata.name !== expectedPath || metadata.contentType !== "image/jpeg" ||
      custom.batchfeeAccess !== "registration-pending" ||
      custom.batchfeeInstituteHash !== instituteStorageHash(instituteId) ||
      custom.batchfeePurpose !== "student_photo" ||
      custom.batchfeeRegistrationRequestId !== requestId ||
      custom.batchfeeSha256 !== photo.sha256) {
    throw new Error("Registration photo storage collision detected.");
  }
}

async function uploadPendingRegistrationPhoto({ bucket, instituteId, requestId, photo }) {
  if (!bucket || typeof bucket.file !== "function") throw new Error("Registration photo storage is unavailable.");
  assertRegistrationIdentity(instituteId, requestId);
  assertStudentPhotoBytes(photo && photo.bytes);
  const path = registrationPhotoObjectPath(instituteId, requestId);
  const file = bucket.file(path);
  try {
    await file.save(photo.bytes, {
      resumable: false,
      validation: "md5",
      preconditionOpts: { ifGenerationMatch: 0 },
      metadata: pendingPhotoMetadata(instituteId, requestId, photo),
    });
  } catch (error) {
    if (storageErrorCode(error) !== 409 && storageErrorCode(error) !== 412) throw error;
  }
  const [metadata] = await file.getMetadata();
  assertPendingObject(metadata, instituteId, requestId, photo);
  return {
    storageBucket: bucket.name,
    storageObjectPath: path,
    contentType: "image/jpeg",
    byteLength: Number(metadata.size || photo.byteLength),
    sha256: photo.sha256,
    uploadedAtMs: Date.now(),
  };
}

function parsePendingRegistrationPhoto(value, instituteId, requestId, bucketName) {
  if (value == null) return null;
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      value.storageBucket !== bucketName ||
      value.storageObjectPath !== registrationPhotoObjectPath(instituteId, requestId) ||
      value.contentType !== "image/jpeg" ||
      !Number.isSafeInteger(value.byteLength) || value.byteLength < 4 || value.byteLength > MAX_STUDENT_PHOTO_BYTES ||
      typeof value.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(value.sha256)) {
    throw new Error("Registration photo data is invalid.");
  }
  return {
    storageObjectPath: value.storageObjectPath,
    byteLength: value.byteLength,
    sha256: value.sha256,
  };
}

function finalPhotoMetadata(instituteId, requestId, assetId, sha256) {
  const objectPath = storageObjectPath(instituteId, "student_photo", assetId, true);
  return {
    contentType: "image/jpeg",
    cacheControl: "private, max-age=300",
    metadata: {
      batchfeeAccess: "signed",
      batchfeeAssetId: assetId,
      batchfeeInstituteHash: objectPath.split("/")[3],
      batchfeePurpose: "student_photo",
      batchfeeRegistrationRequestId: requestId,
      batchfeeSha256: sha256,
    },
  };
}

function assertFinalObject(metadata, instituteId, requestId, assetId, sha256) {
  const objectPath = storageObjectPath(instituteId, "student_photo", assetId, true);
  const custom = metadata && metadata.metadata && typeof metadata.metadata === "object" ? metadata.metadata : {};
  if (!metadata || metadata.name !== objectPath || metadata.contentType !== "image/jpeg" ||
      custom.batchfeeAccess !== "signed" || custom.batchfeeAssetId !== assetId ||
      custom.batchfeeInstituteHash !== objectPath.split("/")[3] ||
      custom.batchfeePurpose !== "student_photo" || custom.batchfeeRegistrationRequestId !== requestId ||
      custom.batchfeeSha256 !== sha256) {
    throw new Error("Student photo storage collision detected.");
  }
}

/**
 * Promotes the private pending upload to BatchFee's managed student-media
 * storage. The destination is deterministic, making a retry safe.
 */
async function materializeRegistrationStudentPhoto({
  bucket, instituteId, requestId, studentId, pendingPhoto,
}) {
  if (!bucket || typeof bucket.file !== "function") throw new Error("Registration photo storage is unavailable.");
  assertRegistrationIdentity(instituteId, requestId);
  if (typeof studentId !== "string" || !studentId || studentId.length > 128 || studentId.includes("/")) {
    throw new Error("Invalid student for registration photo.");
  }
  const photo = parsePendingRegistrationPhoto(pendingPhoto, instituteId, requestId, bucket.name);
  if (!photo) return null;
  const assetId = assetIdForRegistrationPhoto(instituteId, requestId, photo.sha256);
  const objectPath = storageObjectPath(instituteId, "student_photo", assetId, true);
  const target = bucket.file(objectPath);
  let metadata;
  try {
    [metadata] = await target.getMetadata();
    assertFinalObject(metadata, instituteId, requestId, assetId, photo.sha256);
  } catch (error) {
    if (storageErrorCode(error) !== 404) throw error;
    const [bytes] = await bucket.file(photo.storageObjectPath).download();
    assertStudentPhotoBytes(bytes);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    if (sha256 !== photo.sha256 || bytes.length !== photo.byteLength) {
      throw new Error("Registration photo integrity check failed.");
    }
    try {
      await target.save(bytes, {
        resumable: false,
        validation: "md5",
        preconditionOpts: { ifGenerationMatch: 0 },
        metadata: finalPhotoMetadata(instituteId, requestId, assetId, photo.sha256),
      });
    } catch (saveError) {
      if (storageErrorCode(saveError) !== 409 && storageErrorCode(saveError) !== 412) throw saveError;
    }
    [metadata] = await target.getMetadata();
    assertFinalObject(metadata, instituteId, requestId, assetId, photo.sha256);
  }
  return {
    assetId,
    reference: buildMediaReference(instituteId, assetId),
    operationId: `registration_${requestId}`,
    storageObjectPath: objectPath,
    storageGeneration: String(metadata.generation || ""),
    byteLength: Number(metadata.size || photo.byteLength),
    sha256: photo.sha256,
    temporaryObjectPath: photo.storageObjectPath,
    studentId,
  };
}

async function cleanupPendingRegistrationPhoto({ bucket, instituteId, requestId }) {
  if (!bucket || typeof bucket.file !== "function") return;
  const path = registrationPhotoObjectPath(instituteId, requestId);
  try {
    await bucket.file(path).delete({ ignoreNotFound: true });
  } catch (_) {
    // The source is private. A later retention cleanup can remove a rare orphan.
  }
}

module.exports = {
  MAX_STUDENT_PHOTO_BYTES,
  assetIdForRegistrationPhoto,
  canonicalRegistrationPhoto,
  cleanupPendingRegistrationPhoto,
  materializeRegistrationStudentPhoto,
  parsePendingRegistrationPhoto,
  registrationPhotoObjectPath,
  uploadPendingRegistrationPhoto,
};
