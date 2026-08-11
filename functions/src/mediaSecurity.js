"use strict";

const { HttpsError } = require("firebase-functions/v2/https");
const { hasPermission } = require("./studentAuthCore");
const { hasCurrentSubscription } = require("./subscriptionPolicy");
const {
  MEDIA_RETENTION_MS,
  buildMediaReference,
  canonicalUploadRequest,
  cloudinaryPublicId,
  parseMediaReference,
} = require("./mediaSecurityCore");

const STUDENT_READ_PERMISSIONS = [
  "view_student", "manage_student", "view_batch", "manage_batch",
  "view_fee_summary", "collect_fee", "send_due_message", "take_attendance",
  "view_attendance_reports", "view_reports", "manage_exams", "generate_id_cards",
  "birthday_reminders",
];

function active(data) {
  return data && data.status === "active" && data.archivedAtMs == null;
}

function superAdmin(data) {
  return data && (["SuperAdmin", "superAdmin", "super_admin"].includes(data.role) || data.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
}

function managedAdmin(data, instituteId) {
  return data && data.instituteId === instituteId &&
    ["InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(data.role) &&
    (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
}

async function principalFor(db, auth, instituteId) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const [instituteSnap, appUserSnap, staffSnap] = await Promise.all([
    instituteRef.get(),
    db.collection("app_users").doc(auth.uid).get(),
    instituteRef.collection("staffs").doc(auth.uid).get(),
  ]);
  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  if (superAdmin(appUser)) return { kind: "super", instituteRef, institute: instituteSnap.data() || null };
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");
  const institute = instituteSnap.data();
  if (!institute || institute.isActive === false || institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "Institute is inactive.");
  }
  if (!hasCurrentSubscription(institute)) {
    throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
  }
  if (auth.uid === instituteId || managedAdmin(appUser, instituteId)) {
    return { kind: "principal", instituteRef, institute };
  }
  const staff = staffSnap.exists ? staffSnap.data() : null;
  if (active(staff)) return { kind: "staff", instituteRef, institute, staff };
  const claims = auth.token || {};
  const sessionExpiry = Number(claims.studentSessionExpiresAt || 0);
  if (claims.studentManaged === true && claims.student === true &&
      claims.instituteId === instituteId && typeof claims.studentId === "string" &&
      sessionExpiry > Date.now()) {
    return { kind: "student", instituteRef, institute, studentId: claims.studentId };
  }
  throw new HttpsError("permission-denied", "Active institute membership is required.");
}

function staffHas(staff, permission) {
  return !!staff && hasPermission(staff.permissions, permission);
}

function staffCanReadStudent(staff) {
  return STUDENT_READ_PERMISSIONS.some((permission) => staffHas(staff, permission));
}

function assertCanUpload(principal, purpose, subjectId, actorUid) {
  if (principal.kind === "super" || principal.kind === "principal") return;
  if (principal.kind === "student") {
    if (purpose === "student_photo" && subjectId === principal.studentId) return;
    throw new HttpsError("permission-denied", "Student media upload is not allowed.");
  }
  if (purpose === "student_photo" && staffHas(principal.staff, "manage_student")) return;
  if (purpose === "staff_photo" &&
      (staffHas(principal.staff, "manage_staff") || subjectId === actorUid)) return;
  throw new HttpsError("permission-denied", "Media upload is not allowed.");
}

function safeCloudinaryError(error, fallback) {
  const code = Number(error && (error.http_code || error.statusCode) || 0);
  if (code === 400 || code === 413) return new HttpsError("invalid-argument", fallback);
  if (code === 401 || code === 403) return new HttpsError("failed-precondition", "Media service is not configured.");
  return new HttpsError("unavailable", fallback);
}

async function uploadOrRecover(cloudinary, parsed, publicId) {
  const options = {
    public_id: publicId,
    resource_type: "image",
    type: parsed.isPrivate ? "authenticated" : "upload",
    overwrite: false,
    invalidate: true,
    unique_filename: false,
    use_filename: false,
    tags: ["batchfee", parsed.purpose, parsed.isPrivate ? "private" : "public"],
  };
  try {
    return await cloudinary.uploader.upload(
      `data:image/jpeg;base64,${parsed.imageBase64}`,
      options,
    );
  } catch (error) {
    if (Number(error && error.http_code) === 409) {
      return cloudinary.api.resource(publicId, {
        resource_type: "image",
        type: options.type,
      });
    }
    throw error;
  }
}

function createMediaSecurityHandlers({ db, getCloudinary }) {
  async function uploadSecureMedia(request) {
    let parsed;
    try {
      parsed = canonicalUploadRequest(request.data || {});
    } catch (error) {
      throw new HttpsError("invalid-argument", error.message);
    }
    const actorUid = request.auth && request.auth.uid;
    const principal = await principalFor(db, request.auth, parsed.instituteId);
    assertCanUpload(principal, parsed.purpose, parsed.subjectId, actorUid);

    const operationRef = principal.instituteRef.collection("media_upload_operations")
      .doc(parsed.operationId);
    const existing = await db.runTransaction(async (transaction) => {
      const operationSnap = await transaction.get(operationRef);
      if (operationSnap.exists) {
        if (operationSnap.get("actorUid") !== actorUid ||
            operationSnap.get("requestHash") !== parsed.requestHash) {
          throw new HttpsError("already-exists", "Operation ID belongs to another upload.");
        }
        if (operationSnap.get("status") === "complete") return operationSnap.get("result");
        transaction.update(operationRef, { status: "uploading", updatedAtMs: Date.now() });
        return null;
      }
      transaction.create(operationRef, {
        instituteId: parsed.instituteId,
        operationId: parsed.operationId,
        actorUid,
        requestHash: parsed.requestHash,
        purpose: parsed.purpose,
        subjectId: parsed.subjectId,
        status: "uploading",
        createdAtMs: Date.now(),
        updatedAtMs: Date.now(),
      });
      return null;
    });
    if (existing) return existing;

    const cloudinary = getCloudinary();
    const publicId = cloudinaryPublicId(
      parsed.instituteId,
      parsed.purpose,
      parsed.assetId,
      parsed.isPrivate,
    );
    let uploaded;
    try {
      uploaded = await uploadOrRecover(cloudinary, parsed, publicId);
    } catch (error) {
      await operationRef.update({
        status: "failed",
        errorCode: Number(error && error.http_code || 0) || null,
        updatedAtMs: Date.now(),
      }).catch(() => {});
      throw safeCloudinaryError(error, "Secure image upload failed. Try again.");
    }
    if (!uploaded || uploaded.public_id !== publicId || !uploaded.format || !uploaded.version) {
      throw new HttpsError("internal", "Media service returned an invalid asset record.");
    }

    const now = Date.now();
    const reference = parsed.isPrivate
      ? buildMediaReference(parsed.instituteId, parsed.assetId)
      : uploaded.secure_url;
    if (!reference || (parsed.isPrivate === false && !/^https:\/\//.test(reference))) {
      throw new HttpsError("internal", "Media service did not return a secure reference.");
    }
    const result = {
      reference,
      assetId: parsed.assetId,
      purpose: parsed.purpose,
      private: parsed.isPrivate,
    };
    const assetRef = principal.instituteRef.collection("media_assets").doc(parsed.assetId);
    const auditRef = principal.instituteRef.collection("media_audit").doc(parsed.operationId);
    const replaced = parseMediaReference(parsed.replacesReference);
    const replacedRef = replaced && replaced.instituteId === parsed.instituteId &&
      replaced.assetId !== parsed.assetId
      ? principal.instituteRef.collection("media_assets").doc(replaced.assetId) : null;

    await db.runTransaction(async (transaction) => {
      const reads = [transaction.get(operationRef), transaction.get(assetRef)];
      if (replacedRef) reads.push(transaction.get(replacedRef));
      const [operationSnap, assetSnap, replacedSnap] = await Promise.all(reads);
      if (!operationSnap.exists || operationSnap.get("actorUid") !== actorUid ||
          operationSnap.get("requestHash") !== parsed.requestHash) {
        throw new HttpsError("failed-precondition", "Upload operation ownership changed.");
      }
      if (operationSnap.get("status") === "complete") return;
      if (assetSnap.exists && assetSnap.get("operationId") !== parsed.operationId) {
        throw new HttpsError("already-exists", "Media asset ID collision.");
      }
      transaction.set(assetRef, {
        instituteId: parsed.instituteId,
        assetId: parsed.assetId,
        operationId: parsed.operationId,
        purpose: parsed.purpose,
        subjectId: parsed.subjectId,
        uploadedByUid: actorUid,
        reference,
        deliveryType: parsed.isPrivate ? "authenticated" : "upload",
        cloudinaryPublicId: publicId,
        cloudinaryAssetId: uploaded.asset_id || null,
        version: Number(uploaded.version),
        format: String(uploaded.format),
        bytes: Number(uploaded.bytes || parsed.byteLength),
        sha256: parsed.sha256,
        status: "active",
        cleanupState: "retained",
        createdAtMs: assetSnap.exists ? assetSnap.get("createdAtMs") || now : now,
        updatedAtMs: now,
      });
      if (replacedRef && replacedSnap && replacedSnap.exists &&
          replacedSnap.get("instituteId") === parsed.instituteId) {
        transaction.update(replacedRef, {
          status: "superseded",
          cleanupState: "retained",
          supersededByAssetId: parsed.assetId,
          retentionUntilMs: now + MEDIA_RETENTION_MS,
          updatedAtMs: now,
        });
      }
      transaction.set(auditRef, {
        instituteId: parsed.instituteId,
        operationId: parsed.operationId,
        assetId: parsed.assetId,
        action: "upload",
        purpose: parsed.purpose,
        subjectId: parsed.subjectId,
        actorUid,
        replacedAssetId: replacedRef ? replaced.assetId : null,
        occurredAtMs: now,
      });
      transaction.update(operationRef, { status: "complete", result, updatedAtMs: now });
    });
    return result;
  }

  async function getSecureMediaUrl(request) {
    const reference = request.data && typeof request.data.reference === "string"
      ? request.data.reference.trim() : "";
    const parsed = parseMediaReference(reference);
    if (!parsed) throw new HttpsError("invalid-argument", "Invalid secure media reference.");
    const principal = await principalFor(db, request.auth, parsed.instituteId);
    const assetSnap = await principal.instituteRef.collection("media_assets").doc(parsed.assetId).get();
    if (!assetSnap.exists) throw new HttpsError("not-found", "Media asset not found.");
    const asset = assetSnap.data();
    if (asset.instituteId !== parsed.instituteId || asset.reference !== reference ||
        asset.deliveryType !== "authenticated" || asset.status === "deleted") {
      throw new HttpsError("permission-denied", "Media asset is unavailable.");
    }

    let allowed = principal.kind === "super" || principal.kind === "principal";
    if (!allowed && principal.kind === "staff") {
      if (asset.purpose === "institute_logo") allowed = true;
      if (asset.purpose === "student_photo") allowed = staffCanReadStudent(principal.staff);
      if (asset.purpose === "staff_photo") {
        allowed = asset.subjectId === request.auth.uid || staffHas(principal.staff, "manage_staff");
        if (!allowed) {
          const ownStaffSnap = await principal.instituteRef.collection("staffs")
            .doc(request.auth.uid).get();
          allowed = ownStaffSnap.exists && active(ownStaffSnap.data()) &&
            ownStaffSnap.get("photoUri") === reference;
        }
      }
    }
    if (!allowed && principal.kind === "student") {
      if (asset.purpose === "institute_logo") {
        allowed = true;
      } else if (asset.purpose === "student_photo") {
        const studentSnap = await principal.instituteRef.collection("students")
          .doc(principal.studentId).get();
        allowed = asset.subjectId === principal.studentId &&
          studentSnap.exists && active(studentSnap.data()) &&
          studentSnap.get("photoUri") === reference;
      }
    }
    if (!allowed) throw new HttpsError("permission-denied", "Media access is not allowed.");
    if (asset.status === "retained" && principal.kind !== "super" && principal.kind !== "principal") {
      throw new HttpsError("permission-denied", "Retained media requires principal review.");
    }

    const expiresAtSeconds = Math.floor(Date.now() / 1000) + 5 * 60;
    const cloudinary = getCloudinary();
    const url = cloudinary.utils.private_download_url(
      asset.cloudinaryPublicId,
      asset.format,
      {
        resource_type: "image",
        type: "authenticated",
        attachment: false,
        expires_at: expiresAtSeconds,
      },
    );
    return { url, expiresAtMs: expiresAtSeconds * 1000 };
  }

  return { uploadSecureMedia, getSecureMediaUrl };
}

module.exports = { createMediaSecurityHandlers };
