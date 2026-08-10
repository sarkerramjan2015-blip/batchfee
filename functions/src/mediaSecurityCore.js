"use strict";

const { createHash } = require("node:crypto");

const MEDIA_REFERENCE_PREFIX = "batchfee-media://v1/";
const MEDIA_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const PURPOSES = Object.freeze({
  institute_logo: { private: false, maxBytes: 600 * 1024 },
  student_photo: { private: true, maxBytes: 400 * 1024 },
  staff_photo: { private: true, maxBytes: 400 * 1024 },
});

function requiredString(data, field, maxLength) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) throw new Error(`Invalid ${field}.`);
  return value;
}

function optionalString(data, field, maxLength) {
  if (!data || data[field] == null || data[field] === "") return null;
  if (typeof data[field] !== "string") throw new Error(`Invalid ${field}.`);
  const value = data[field].trim();
  if (!value || value.length > maxLength) throw new Error(`Invalid ${field}.`);
  return value;
}

function buildMediaReference(instituteId, assetId) {
  return `${MEDIA_REFERENCE_PREFIX}${encodeURIComponent(instituteId)}/${assetId}`;
}

function parseMediaReference(value) {
  if (typeof value !== "string" || !value.startsWith(MEDIA_REFERENCE_PREFIX)) return null;
  const remainder = value.slice(MEDIA_REFERENCE_PREFIX.length);
  const slash = remainder.indexOf("/");
  if (slash <= 0 || slash === remainder.length - 1 || remainder.indexOf("/", slash + 1) !== -1) {
    return null;
  }
  let instituteId;
  try {
    instituteId = decodeURIComponent(remainder.slice(0, slash));
  } catch (_) {
    return null;
  }
  const assetId = remainder.slice(slash + 1);
  if (!instituteId || instituteId.includes("/") || instituteId.length > 128 ||
      !/^[a-f0-9]{32}$/.test(assetId)) return null;
  return { instituteId, assetId };
}

function canonicalUploadRequest(data) {
  const instituteId = requiredString(data, "instituteId", 128);
  if (instituteId.includes("/")) throw new Error("Invalid instituteId.");
  const purpose = requiredString(data, "purpose", 32);
  const policy = PURPOSES[purpose];
  if (!policy) throw new Error("Invalid purpose.");
  const operationId = requiredString(data, "operationId", 64);
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(operationId)) throw new Error("Invalid operationId.");
  const subjectId = optionalString(data, "subjectId", 128);
  if (subjectId && subjectId.includes("/")) throw new Error("Invalid subjectId.");
  if (purpose === "student_photo" && !subjectId) {
    throw new Error("Student photo ownership is required.");
  }
  const replacesReference = optionalString(data, "replacesReference", 512);
  if (replacesReference && !parseMediaReference(replacesReference) &&
      !/^https:\/\//i.test(replacesReference)) {
    throw new Error("Invalid replacesReference.");
  }
  const imageBase64 = requiredString(data, "imageBase64", Math.ceil(policy.maxBytes * 4 / 3) + 8);
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(imageBase64) || imageBase64.length % 4 !== 0) {
    throw new Error("Invalid image data.");
  }
  const bytes = Buffer.from(imageBase64, "base64");
  if (bytes.length < 4 || bytes.length > policy.maxBytes ||
      bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) {
    throw new Error("Upload must be an optimized JPEG within the allowed size.");
  }
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const requestHash = createHash("sha256").update(JSON.stringify({
    instituteId,
    purpose,
    operationId,
    subjectId,
    replacesReference,
    sha256,
  })).digest("hex");
  const assetId = createHash("sha256")
    .update(`${instituteId}:${operationId}`)
    .digest("hex")
    .slice(0, 32);
  return {
    instituteId,
    purpose,
    operationId,
    subjectId,
    replacesReference,
    imageBase64,
    bytes,
    byteLength: bytes.length,
    sha256,
    requestHash,
    assetId,
    isPrivate: policy.private,
  };
}

function cloudinaryPublicId(instituteId, purpose, assetId, isPrivate) {
  const tenantKey = createHash("sha256").update(instituteId).digest("hex").slice(0, 20);
  return `batchfee/${isPrivate ? "private" : "public"}/${tenantKey}/${purpose}/${assetId}`;
}

module.exports = {
  MEDIA_REFERENCE_PREFIX,
  MEDIA_RETENTION_MS,
  PURPOSES,
  buildMediaReference,
  canonicalUploadRequest,
  cloudinaryPublicId,
  parseMediaReference,
};
