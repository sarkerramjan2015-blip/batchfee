"use strict";

const { createHash, randomUUID } = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { FieldPath, FieldValue, getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { logger } = require("firebase-functions");
const { defineSecret } = require("firebase-functions/params");
const { HttpsError, onCall, onRequest } = require("firebase-functions/v2/https");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const {
  hasPermission,
  hashPassword,
  normalizeIdentifier,
  staffLoginDocumentId,
  studentLoginDocumentId,
  validatePassword,
  verifyPassword,
} = require("./studentAuthCore");
const {
  isLegacyAutoStudentId,
  isValidStudentId,
  normalizeStudentId,
  studentIdClaimDocumentId,
} = require("./studentIdCore");
const { createFinancialLedgerHandler } = require("./financialLedger");
const { createExamFeeBillingHandler } = require("./examFeeBilling");
const { createMediaSecurityHandlers } = require("./mediaSecurity");
const { createSafeDeletionHandler } = require("./safeDeletion");
const { createPermanentStudentPurgeHandler } = require("./permanentStudentPurge");
const {
  createPermanentBatchPurgeHandler,
  createPermanentStaffPurgeHandler,
} = require("./permanentArchivePurge");
const { createPermanentInstitutePurgeHandler } = require("./permanentInstitutePurge");
const { createPublicRegistrationHandler } = require("./publicRegistration");
const {
  cleanupPendingRegistrationPhoto,
  materializeRegistrationStudentPhoto,
} = require("./registrationPhoto");
const { resolveApprovalReplay } = require("./registrationApprovalCore");
const { buildRegistrationSlug, registrationFormUrl } = require("./registrationProfileCore");
const { createSubscriptionBillingHandler } = require("./subscriptionBilling");
const { createPlatformAdminHandler } = require("./platformAdmin");
const {
  createStudentActivityHandler,
  createStudentActivityFeedHandler,
  writeStudentActivity,
} = require("./studentActivity");
const {
  createInstituteOwnerLoginCleanupHandler,
  createInstituteOwnerLoginFeedHandler,
  createInstituteOwnerLoginRecorder,
} = require("./instituteOwnerLoginActivity");
const {
  FREE_TRIAL_DURATION_MS,
  FREE_TRIAL_STUDENT_LIMIT,
  hasCurrentSubscription,
  hasUnlimitedTrialStudents,
} = require("./subscriptionPolicy");
const { planFromSnapshot } = require("./defaultSubscriptionPlans");
const { createTenantOperationalSummaryHandler } = require("./tenantOperationalSummary");
const { resolveTrustedCreationReplay, trustedCreationHash } = require("./trustedCreationCore");
const {
  mapWithConcurrency,
  subscriptionEntitlementPatch,
} = require("./subscriptionMaintenanceCore");
const {
  callableTelemetryContext,
  isSlowCallable,
  rejectionLogLevel,
  scheduledHealthPatch,
} = require("./operationalTelemetryCore");

initializeApp();

const REGION = "asia-south1";
const SESSION_DURATION_MS = 12 * 60 * 60 * 1000;
const ATTEMPT_WINDOW_MS = 15 * 60 * 1000;
const LOCK_DURATION_MS = 15 * 60 * 1000;
const MAX_FAILED_ATTEMPTS = 5;
const MAINTENANCE_PAGE_SIZE = 100;
const MAINTENANCE_CONCURRENCY = 10;
const EXPIRY_MAX_PAGES_PER_RUN = 25;
const DUMMY_SALT = Buffer.alloc(16, 7).toString("base64");
const DUMMY_HASH = Buffer.alloc(64, 11).toString("base64");
const callableOptions = {
  region: REGION,
  timeoutSeconds: 30,
  memory: "256MiB",
  // Keep App Check tokens observable while the signed app is being validated
  // outside Google Play. Every callable still requires Firebase Auth and its
  // own tenant/role checks, so an invalid attestation cannot grant access.
  // Re-enable strict enforcement after the Play Console cloud-project link is
  // completed and verified with the Play-distributed build.
  enforceAppCheck: false,
};
const registrationRateLimitSecret = defineSecret("REGISTRATION_RATE_LIMIT_SECRET");

const db = getFirestore();
const adminAuth = getAuth();
function configuredMediaStorageBucketName() {
  if (process.env.FIREBASE_STORAGE_BUCKET) return process.env.FIREBASE_STORAGE_BUCKET;
  try {
    const runtimeConfig = JSON.parse(process.env.FIREBASE_CONFIG || "{}");
    if (typeof runtimeConfig.storageBucket === "string" && runtimeConfig.storageBucket) {
      return runtimeConfig.storageBucket;
    }
  } catch (_) {
    // Deploy-time Firebase config is optional for local checks; use the checked-in app bucket.
  }
  // Firebase projects created after October 2024 use the .firebasestorage.app default bucket.
  return "batchfee-477b8.firebasestorage.app";
}

const mediaStorageBucket = getStorage().bucket(configuredMediaStorageBucketName());
const mediaSecurityHandlers = createMediaSecurityHandlers({
  db,
  bucket: mediaStorageBucket,
});
const publicRegistrationHandler = createPublicRegistrationHandler({
  db,
  bucket: mediaStorageBucket,
  rateLimitSecret: registrationRateLimitSecret,
});
const tenantOperationalSummaryHandler = createTenantOperationalSummaryHandler({ db });

function requireString(data, field, maxLength = 128) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function isActiveRecord(data) {
  return data && data.status === "active" &&
    (!Object.prototype.hasOwnProperty.call(data, "archivedAtMs") || data.archivedAtMs == null);
}

function hasActiveSubscription(institute, now = Date.now()) {
  return hasCurrentSubscription(institute, now);
}

function assertActiveSubscription(institute) {
  if (!hasActiveSubscription(institute)) {
    throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
  }
}

async function assertCanManageStudent(authContext, instituteId) {
  if (!authContext || !authContext.uid) {
    throw new HttpsError("unauthenticated", "Sign in is required.");
  }

  const instituteRef = db.collection("institutes").doc(instituteId);
  const appUserRef = db.collection("app_users").doc(authContext.uid);
  const staffRef = instituteRef.collection("staffs").doc(authContext.uid);
  const [instituteSnap, appUserSnap, staffSnap] = await Promise.all([
    instituteRef.get(),
    appUserRef.get(),
    staffRef.get(),
  ]);
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");

  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const isSuperAdmin = appUser &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(appUser.role) || appUser.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isSuperAdmin) {
    assertActiveSubscription(instituteSnap.data());
    return instituteSnap;
  }

  const institute = instituteSnap.data();
  if (institute.isActive === false || institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "Institute is inactive.");
  }
  // Institute documents created before the managed-owner flow can use an
  // arbitrary document ID. Their actual owner is stored in ownerUid, so both
  // forms must have the same account-management authority.
  if (authContext.uid === instituteId || authContext.uid === institute.ownerUid) {
    assertActiveSubscription(institute);
    return instituteSnap;
  }

  const isManagedAdmin = appUser && appUser.instituteId === instituteId &&
    ["InstituteOwner", "owner", "instituteOwner", "institute_owner",
      "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isManagedAdmin) {
    assertActiveSubscription(institute);
    return instituteSnap;
  }

  const staff = staffSnap.exists ? staffSnap.data() : null;
  if (institute.isActive !== false && isActiveRecord(staff) &&
      hasPermission(staff.permissions, "manage_student")) {
    assertActiveSubscription(institute);
    return instituteSnap;
  }
  throw new HttpsError("permission-denied", "Student account management is not allowed.");
}

async function assertCanManageTenantResource(authContext, instituteId, permission, allowStaff = true) {
  if (!authContext || !authContext.uid) {
    throw new HttpsError("unauthenticated", "Sign in is required.");
  }
  const instituteRef = db.collection("institutes").doc(instituteId);
  const appUserRef = db.collection("app_users").doc(authContext.uid);
  const staffRef = instituteRef.collection("staffs").doc(authContext.uid);
  const [instituteSnap, appUserSnap, staffSnap] = await Promise.all([
    instituteRef.get(), appUserRef.get(), staffRef.get(),
  ]);
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");
  const institute = instituteSnap.data();
  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const platformAdmin = appUser &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(appUser.role) || appUser.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  const managedAdmin = appUser && appUser.instituteId === instituteId &&
    ["InstituteOwner", "owner", "instituteOwner", "institute_owner",
      "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  const owner = authContext.uid === instituteId || authContext.uid === institute.ownerUid;
  const staff = staffSnap.exists ? staffSnap.data() : null;
  const permittedStaff = allowStaff && isActiveRecord(staff) && hasPermission(staff.permissions, permission);
  if (!platformAdmin && !managedAdmin && !owner && !permittedStaff) {
    throw new HttpsError("permission-denied", "You do not have permission for this operation.");
  }
  assertActiveSubscription(institute);
  return instituteSnap;
}

async function assertPlatformRoot(authContext) {
  if (!authContext || !authContext.uid) {
    throw new HttpsError("unauthenticated", "Sign in is required.");
  }
  const user = await db.collection("app_users").doc(authContext.uid).get();
  const data = user.exists ? user.data() : null;
  const isRoot = data &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(data.role) || data.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
  if (!isRoot) throw new HttpsError("permission-denied", "Platform administrator access is required.");
}

async function repairSubscriptionEntitlementsHandler(request) {
  await assertPlatformRoot(request.auth);
  const now = Date.now();
  let repaired = 0;
  let scanned = 0;
  let failed = 0;
  let cursor = null;
  while (true) {
    let query = db.collection("institutes")
      .orderBy(FieldPath.documentId())
      .limit(MAINTENANCE_PAGE_SIZE);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    if (page.empty) break;
    scanned += page.size;
    const results = await mapWithConcurrency(
      page.docs,
      MAINTENANCE_CONCURRENCY,
      async (candidate) => {
        try {
          return await db.runTransaction(async (transaction) => {
            const current = await transaction.get(candidate.ref);
            if (!current.exists) return false;
            const institute = current.data();
            const planId = typeof institute.currentPlanId === "string" && institute.currentPlanId
              ? institute.currentPlanId : "plan_free_trial";
            const planSnap = await transaction.get(db.collection("subscription_plans").doc(planId));
            const plan = planFromSnapshot(planSnap, planId) || {};
            const { patch } = subscriptionEntitlementPatch({
              institute,
              plan,
              now,
              freeTrialDurationMs: FREE_TRIAL_DURATION_MS,
              freeTrialStudentLimit: FREE_TRIAL_STUDENT_LIMIT,
            });
            if (!Object.keys(patch).length) return false;
            transaction.update(candidate.ref, {
              ...patch,
              subscriptionEntitlementsRepairedAtMs: now,
            });
            return true;
          });
        } catch (error) {
          failed += 1;
          logger.error("Subscription entitlement repair failed for institute", {
            instituteId: candidate.id,
            errorCode: error && error.code,
          });
          return false;
        }
      },
    );
    repaired += results.filter(Boolean).length;
    cursor = page.docs[page.docs.length - 1];
    if (page.size < MAINTENANCE_PAGE_SIZE) break;
  }
  logger.info("Subscription entitlement repair completed", { repaired, scanned, failed });
  return { repaired, scanned, failed };
}

function requireEntity(data, field) {
  const value = data && data[field];
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function optionalString(value, maxLength = 500) {
  if (value == null) return null;
  if (typeof value !== "string" || value.length > maxLength) {
    throw new HttpsError("invalid-argument", "Invalid entity field.");
  }
  return value;
}

function optionalNumber(value, min = 0, max = 1000000000) {
  if (value == null) return null;
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) {
    throw new HttpsError("invalid-argument", "Invalid entity field.");
  }
  return value;
}

function optionalDocumentId(data, field) {
  const value = data && data[field];
  if (value == null) return null;
  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  const id = value.trim();
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(id)) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return id;
}

// Subscription capacity is intentionally measured only in active student
// seats. Batches and staff remain unlimited for every active institute.
function studentPlanLimit(institute, plan) {
  const stored = institute.studentLimit;
  if (Number.isSafeInteger(stored) && stored > 0) return stored;
  const fromPlan = plan.maxStudents;
  if (!Number.isSafeInteger(fromPlan) || fromPlan < 1) {
    throw new HttpsError("failed-precondition", "The subscription plan has no valid limit configuration.");
  }
  return fromPlan;
}

function copyFields(source, fields) {
  const output = {};
  for (const field of fields) {
    if (Object.prototype.hasOwnProperty.call(source, field)) output[field] = source[field];
  }
  return output;
}

async function createEntitledStudentHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const studentId = requireString(request.data, "studentId", 128);
  const registrationRequestId = optionalDocumentId(request.data, "registrationRequestId");
  const entity = requireEntity(request.data, "student");
  await assertCanManageTenantResource(request.auth, instituteId, "manage_student");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const students = instituteRef.collection("students");
  const studentRef = students.doc(studentId);
  const studentCountQuery = students.where("status", "==", "active").count();
  const pendingRegistrationRef = registrationRequestId
    ? db.collection("registrations").doc(instituteId).collection("pending").doc(registrationRequestId)
    : null;
  const approvalOperationRef = registrationRequestId
    ? db.collection("registrations").doc(instituteId).collection("approval_operations").doc(registrationRequestId)
    : null;
  const allowed = ["studentCode", "fullName", "photoUri", "gender", "dateOfBirthMs", "phone", "email", "address", "schoolName", "className", "guardianName", "guardianPhone", "guardianEmail", "emergencyContact", "bloodGroup", "admissionDateMs", "notes"];
  const rawStudentCode = requireString(entity, "studentCode", 32);
  const studentCode = normalizeStudentId(rawStudentCode);
  if (!isValidStudentId(studentCode) && !isLegacyAutoStudentId(studentCode)) {
    throw new HttpsError("invalid-argument", "Student ID must contain 3 to 20 letters, numbers or hyphens.");
  }
  const studentCodeNormalized = normalizeIdentifier(studentCode);
  const studentCodeClaimRef = db.collection("student_id_claims")
    .doc(studentIdClaimDocumentId(studentCode));
  const studentLoginRef = db.collection("student_auth_logins")
    .doc(studentLoginDocumentId(studentCode));
  const normalizedCodeQuery = students.where("studentCodeNormalized", "==", studentCodeNormalized);
  const legacyCodeVariants = [...new Set([
    rawStudentCode.trim(), studentCode, studentCode.toLocaleLowerCase("en-US"),
  ])];
  const legacyCodeQuery = students.where("studentCode", "in", legacyCodeVariants);
  const fullName = requireString(entity, "fullName", 160);
  optionalString(entity.phone, 32); optionalString(entity.email, 160); optionalString(entity.photoUri, 2048);
  optionalNumber(entity.admissionDateMs, 0, 4102444800000);
  let registrationPhoto = null;
  if (registrationRequestId && pendingRegistrationRef) {
    const pendingSnap = await pendingRegistrationRef.get();
    if (pendingSnap.exists && pendingSnap.get("status") === "pending" && pendingSnap.get("photoUpload") != null) {
      try {
        registrationPhoto = await materializeRegistrationStudentPhoto({
          bucket: mediaStorageBucket,
          instituteId,
          requestId: registrationRequestId,
          studentId,
          pendingPhoto: pendingSnap.get("photoUpload"),
        });
      } catch (error) {
        logger.error("Registration student photo could not be prepared", {
          instituteId,
          registrationRequestId,
          reason: error instanceof Error ? error.message : "Unknown error",
        });
        throw new HttpsError("unavailable", "Student photo could not be saved. Please try approving again.");
      }
    }
  }
  const result = await db.runTransaction(async (transaction) => {
    const reads = [
      transaction.get(instituteRef),
      transaction.get(studentRef),
      transaction.get(studentCountQuery),
      transaction.get(studentCodeClaimRef),
      transaction.get(studentLoginRef),
      transaction.get(normalizedCodeQuery),
      transaction.get(legacyCodeQuery),
    ];
    if (pendingRegistrationRef && approvalOperationRef) {
      reads.push(transaction.get(pendingRegistrationRef), transaction.get(approvalOperationRef));
    }
    const mediaAssetRef = registrationPhoto
      ? instituteRef.collection("media_assets").doc(registrationPhoto.assetId) : null;
    if (mediaAssetRef) reads.push(transaction.get(mediaAssetRef));
    const snapshots = await Promise.all(reads);
    const [
      instituteSnap, existingSnap, studentCountSnap, studentCodeClaimSnap,
      studentLoginSnap, normalizedCodeSnap, legacyCodeSnap,
    ] = snapshots;
    let nextSnapshot = 7;
    const pendingRegistrationSnap = registrationRequestId ? snapshots[nextSnapshot++] : null;
    const approvalOperationSnap = registrationRequestId ? snapshots[nextSnapshot++] : null;
    const mediaAssetSnap = mediaAssetRef ? snapshots[nextSnapshot++] : null;
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    const planSnap = await transaction.get(db.collection("subscription_plans").doc(instituteSnap.get("currentPlanId") || "plan_free_trial"));
    const plan = planFromSnapshot(planSnap, instituteSnap.get("currentPlanId") || "plan_free_trial");
    if (!plan) throw new HttpsError("failed-precondition", "Subscription plan is unavailable.");
    const count = studentCountSnap.data().count;
    const unlimitedTrialStudents = hasUnlimitedTrialStudents(instituteSnap.data());
    const limit = unlimitedTrialStudents ? null : studentPlanLimit(instituteSnap.data(), plan);
    // A callable can be retried by the transport after the original transaction
    // committed but before the success response reached Android. Return the
    // original success only when both the request ID and student ID match. A
    // later manual tap uses a new student ID and therefore cannot create a local
    // or cloud duplicate through this recovery path.
    const approvalReplay = resolveApprovalReplay({
      operation: approvalOperationSnap && approvalOperationSnap.exists
        ? approvalOperationSnap.data() : null,
      existingStudent: existingSnap.exists ? existingSnap.data() : null,
      requestedStudentId: studentId,
    });
    if (approvalReplay.kind === "replay") {
        return {
          studentId,
          studentCode: existingSnap.get("studentCode") || studentCode,
          studentCount: count,
          studentLimit: limit,
          unlimitedTrialStudents,
          photoUri: approvalReplay.photoUri,
          replayed: true,
        };
    }
    if (approvalReplay.kind === "conflict") {
      throw new HttpsError("already-exists", "This registration has already been approved.");
    }
    if (existingSnap.exists) throw new HttpsError("already-exists", "Student already exists.");
    const claimBelongsToStudent = studentCodeClaimSnap.exists &&
      studentCodeClaimSnap.get("instituteId") === instituteId &&
      studentCodeClaimSnap.get("studentId") === studentId;
    const loginBelongsToStudent = studentLoginSnap.exists &&
      studentLoginSnap.get("instituteId") === instituteId &&
      studentLoginSnap.get("studentId") === studentId;
    const duplicateInInstitute = [...normalizedCodeSnap.docs, ...legacyCodeSnap.docs]
      .some((document) => document.id !== studentId);
    if ((studentCodeClaimSnap.exists && !claimBelongsToStudent) ||
        (studentLoginSnap.exists && !loginBelongsToStudent) || duplicateInInstitute) {
      throw new HttpsError("already-exists", "This Student ID is already in use.");
    }
    if (limit != null && count >= limit) {
      throw new HttpsError("resource-exhausted", `Student limit (${limit}) has been reached.`);
    }
    const now = Date.now();
    if (registrationRequestId) {
      if (!pendingRegistrationSnap.exists || pendingRegistrationSnap.get("status") !== "pending") {
        throw new HttpsError("failed-precondition", "This registration is no longer pending.");
      }
      if (registrationPhoto) {
        const pendingPhoto = pendingRegistrationSnap.get("photoUpload");
        if (!pendingPhoto || pendingPhoto.sha256 !== registrationPhoto.sha256 ||
            pendingPhoto.storageObjectPath !== registrationPhoto.temporaryObjectPath) {
          throw new HttpsError("failed-precondition", "This registration photo changed. Please try approving again.");
        }
      }
      transaction.create(approvalOperationRef, {
        instituteId,
        requestId: registrationRequestId,
        studentId,
        approvedBy: request.auth.uid,
        approvedAtMs: now,
      });
      transaction.delete(pendingRegistrationRef);
    }
    if (registrationPhoto && mediaAssetRef) {
      if (mediaAssetSnap.exists && mediaAssetSnap.get("operationId") !== registrationPhoto.operationId) {
        throw new HttpsError("already-exists", "Registration photo asset collision detected.");
      }
      transaction.set(mediaAssetRef, {
        instituteId,
        assetId: registrationPhoto.assetId,
        operationId: registrationPhoto.operationId,
        purpose: "student_photo",
        subjectId: studentId,
        uploadedByUid: request.auth.uid,
        reference: registrationPhoto.reference,
        deliveryType: "firebase_storage_signed_url",
        storageBucket: mediaStorageBucket.name,
        storageObjectPath: registrationPhoto.storageObjectPath,
        storageGeneration: registrationPhoto.storageGeneration,
        format: "jpg",
        bytes: registrationPhoto.byteLength,
        sha256: registrationPhoto.sha256,
        status: "active",
        cleanupState: "retained",
        createdAtMs: mediaAssetSnap.exists ? mediaAssetSnap.get("createdAtMs") || now : now,
        updatedAtMs: now,
      });
      transaction.set(instituteRef.collection("media_audit").doc(registrationPhoto.operationId), {
        instituteId,
        operationId: registrationPhoto.operationId,
        assetId: registrationPhoto.assetId,
        action: "registration_approval_upload",
        purpose: "student_photo",
        subjectId: studentId,
        actorUid: request.auth.uid,
        occurredAtMs: now,
      });
    }
    const studentFields = copyFields(entity, allowed);
    if (registrationPhoto) studentFields.photoUri = registrationPhoto.reference;
    transaction.set(studentCodeClaimRef, {
      instituteId,
      studentId,
      studentCode,
      normalizedStudentCode: studentCodeNormalized,
      updatedAtMs: now,
      createdAtMs: studentCodeClaimSnap.exists
        ? studentCodeClaimSnap.get("createdAtMs") || now : now,
    });
    transaction.create(studentRef, {
      ...studentFields, instituteId, studentCode, studentCodeNormalized, fullName,
      status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
    });
    transaction.update(instituteRef, { studentCount: count + 1, updatedAtMs: now });
    return {
      studentId,
      studentCode,
      studentCount: count + 1,
      studentLimit: limit,
      unlimitedTrialStudents,
      photoUri: registrationPhoto ? registrationPhoto.reference : (studentFields.photoUri || null),
    };
  });
  if (registrationPhoto && registrationRequestId) {
    await cleanupPendingRegistrationPhoto({
      bucket: mediaStorageBucket,
      instituteId,
      requestId: registrationRequestId,
    });
  }
  logger.info("Registration approval completed", {
    instituteId,
    registrationRequestId: registrationRequestId || null,
    studentId: result.studentId,
    replayed: result.replayed === true,
  });
  return result;
}

async function createEntitledBatchHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const batchId = requireString(request.data, "batchId", 128);
  const operationId = optionalDocumentId(request.data, "operationId");
  const entity = requireEntity(request.data, "batch");
  await assertCanManageTenantResource(request.auth, instituteId, "manage_batch");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const batches = instituteRef.collection("batches");
  const batchRef = batches.doc(batchId);
  const operationRef = operationId
    ? instituteRef.collection("creation_operations").doc(operationId) : null;
  const requestHash = operationId
    ? trustedCreationHash({ kind: "batch", instituteId, batchId, entity }) : null;
  const batchCountQuery = batches.where("status", "==", "active").count();
  const allowed = ["batchCode", "name", "subject", "className", "teacherName", "monthlyFeeAmount", "admissionFeeAmount", "startDateMs", "endDateMs", "scheduleDays", "startTime", "endTime", "maxStudents", "description"];
  const batchCode = requireString(entity, "batchCode", 64);
  const name = requireString(entity, "name", 160);
  optionalNumber(entity.monthlyFeeAmount, 0); optionalNumber(entity.admissionFeeAmount, 0);
  return db.runTransaction(async (transaction) => {
    const reads = [
      transaction.get(instituteRef), transaction.get(batchRef), transaction.get(batchCountQuery),
    ];
    if (operationRef) reads.push(transaction.get(operationRef));
    const [instituteSnap, existingSnap, batchCountSnap, operationSnap] = await Promise.all(reads);
    if (operationRef && operationSnap.exists) {
      const replay = resolveTrustedCreationReplay(operationSnap.data(), request.auth.uid, requestHash);
      if (replay.kind === "replay") return replay.result;
      if (replay.kind === "conflict") {
        throw new HttpsError("already-exists", "Operation ID belongs to another batch request.");
      }
      throw new HttpsError("aborted", "The previous batch request is still being reconciled.");
    }
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    if (existingSnap.exists) throw new HttpsError("already-exists", "Batch already exists.");
    const count = batchCountSnap.data().count;
    const now = Date.now();
    transaction.create(batchRef, {
      ...copyFields(entity, allowed), instituteId, batchCode, name,
      status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
    });
    transaction.update(instituteRef, { batchCount: count + 1, updatedAtMs: now });
    const result = { batchId, batchCount: count + 1, unlimitedBatches: true };
    if (operationRef) {
      transaction.create(operationRef, {
        actorUid: request.auth.uid,
        requestHash,
        status: "completed",
        result,
        createdAtMs: now,
        completedAtMs: now,
      });
    }
    return result;
  });
}

async function createEntitledStaffHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const staffId = requireString(request.data, "staffId", 128);
  const operationId = optionalDocumentId(request.data, "operationId");
  const entity = requireEntity(request.data, "staff");
  await assertCanManageTenantResource(request.auth, instituteId, "manage_staff", false);
  try {
    await adminAuth.getUser(staffId);
  } catch (error) {
    if (error.code === "auth/user-not-found") throw new HttpsError("failed-precondition", "Staff authentication account was not created.");
    throw error;
  }
  const instituteRef = db.collection("institutes").doc(instituteId);
  const staffs = instituteRef.collection("staffs");
  const staffRef = staffs.doc(staffId);
  const operationRef = operationId
    ? instituteRef.collection("creation_operations").doc(operationId) : null;
  const requestHash = operationId
    ? trustedCreationHash({ kind: "staff", instituteId, staffId, entity }) : null;
  const staffCountQuery = staffs.where("status", "==", "active").count();
  const allowed = ["staffCode", "fullName", "photoUri", "roleTitle", "phone", "email", "address", "joiningDateMs", "monthlySalary", "assignedBatchIds", "notes", "permissions"];
  const staffCode = requireString(entity, "staffCode", 64);
  const fullName = requireString(entity, "fullName", 160);
  optionalString(entity.email, 160); optionalNumber(entity.monthlySalary, 0);
  return db.runTransaction(async (transaction) => {
    const reads = [
      transaction.get(instituteRef), transaction.get(staffRef), transaction.get(staffCountQuery),
    ];
    if (operationRef) reads.push(transaction.get(operationRef));
    const [instituteSnap, existingSnap, staffCountSnap, operationSnap] = await Promise.all(reads);
    if (operationRef && operationSnap.exists) {
      const replay = resolveTrustedCreationReplay(operationSnap.data(), request.auth.uid, requestHash);
      if (replay.kind === "replay") return replay.result;
      if (replay.kind === "conflict") {
        throw new HttpsError("already-exists", "Operation ID belongs to another staff request.");
      }
      throw new HttpsError("aborted", "The previous staff request is still being reconciled.");
    }
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    if (existingSnap.exists) throw new HttpsError("already-exists", "Staff already exists.");
    const count = staffCountSnap.data().count;
    const now = Date.now();
    transaction.create(staffRef, {
      ...copyFields(entity, allowed), instituteId, staffCode, fullName,
      status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
    });
    transaction.update(instituteRef, { staffCount: count + 1, updatedAtMs: now });
    const result = { staffId, staffCount: count + 1, unlimitedStaff: true };
    if (operationRef) {
      transaction.create(operationRef, {
        actorUid: request.auth.uid,
        requestHash,
        status: "completed",
        result,
        createdAtMs: now,
        completedAtMs: now,
      });
    }
    return result;
  });
}

function deterministicStaffUid(instituteId, operationId) {
  return `staff_${createHash("sha256")
    .update(instituteId)
    .update("\u0000")
    .update(operationId)
    .digest("hex")
    .slice(0, 40)}`;
}

async function provisionStaffAccountHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const operationId = optionalDocumentId(request.data, "operationId");
  if (!operationId) throw new HttpsError("invalid-argument", "Operation ID is required.");
  const entity = requireEntity(request.data, "staff");
  const password = request.data && request.data.password;
  if (!validatePassword(password)) {
    throw new HttpsError("invalid-argument", "Password must contain 6 to 128 characters.");
  }
  await assertCanManageTenantResource(request.auth, instituteId, "manage_staff", false);

  const allowed = ["staffCode", "fullName", "photoUri", "roleTitle", "phone", "email", "address", "joiningDateMs", "monthlySalary", "assignedBatchIds", "notes", "permissions"];
  const staffCode = requireString(entity, "staffCode", 64).toLocaleUpperCase("en-US");
  const fullName = requireString(entity, "fullName", 160);
  const email = requireString(entity, "email", 160).toLocaleLowerCase("en-US");
  optionalNumber(entity.monthlySalary, 0);
  const loginKey = staffLoginDocumentId(staffCode);
  const uid = deterministicStaffUid(instituteId, operationId);
  const instituteRef = db.collection("institutes").doc(instituteId);
  const staffRef = instituteRef.collection("staffs").doc(uid);
  const operationRef = instituteRef.collection("creation_operations").doc(operationId);
  const loginRef = db.collection("staff_auth_logins").doc(loginKey);
  const accountRef = db.collection("staff_auth_accounts").doc(uid);
  const appUserRef = db.collection("app_users").doc(uid);
  const requestHash = trustedCreationHash({
    kind: "provision_staff", instituteId, entity: { ...entity, staffCode, fullName, email },
    passwordFingerprint: createHash("sha256").update(password).digest("hex"),
  });

  const existingOperation = await operationRef.get();
  if (existingOperation.exists) {
    const replay = resolveTrustedCreationReplay(existingOperation.data(), request.auth.uid, requestHash);
    if (replay.kind === "replay") return replay.result;
    if (replay.kind === "conflict") {
      throw new HttpsError("already-exists", "Operation ID belongs to another staff request.");
    }
    throw new HttpsError("aborted", "The previous staff request is still being reconciled.");
  }

  let authCreated = false;
  try {
    try {
      await adminAuth.createUser({ uid, email, password, displayName: fullName, disabled: false });
      authCreated = true;
    } catch (error) {
      if (error.code !== "auth/uid-already-exists") {
        if (error.code === "auth/email-already-exists") {
          throw new HttpsError("already-exists", "A staff account with this email already exists.");
        }
        throw error;
      }
      const existingUser = await adminAuth.getUser(uid);
      if ((existingUser.email || "").toLocaleLowerCase("en-US") !== email) {
        throw new HttpsError("already-exists", "Staff authentication identity is already in use.");
      }
    }

    const passwordVerifier = await hashPassword(password);
    const staffCountQuery = instituteRef.collection("staffs").where("status", "==", "active").count();
    const result = await db.runTransaction(async (transaction) => {
      const [instituteSnap, operationSnap, loginSnap, staffSnap, accountSnap, countSnap] = await Promise.all([
        transaction.get(instituteRef), transaction.get(operationRef), transaction.get(loginRef),
        transaction.get(staffRef), transaction.get(accountRef), transaction.get(staffCountQuery),
      ]);
      if (operationSnap.exists) {
        const replay = resolveTrustedCreationReplay(operationSnap.data(), request.auth.uid, requestHash);
        if (replay.kind === "replay") return replay.result;
        if (replay.kind === "conflict") {
          throw new HttpsError("already-exists", "Operation ID belongs to another staff request.");
        }
        throw new HttpsError("aborted", "The previous staff request is still being reconciled.");
      }
      if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
        throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
      }
      if (staffSnap.exists || accountSnap.exists) {
        throw new HttpsError("already-exists", "Staff already exists.");
      }
      if (loginSnap.exists) {
        throw new HttpsError("already-exists", "That Staff ID is already in use. Generate a new Staff ID.");
      }
      const now = Date.now();
      const count = countSnap.data().count;
      transaction.create(staffRef, {
        ...copyFields(entity, allowed), instituteId, staffCode, fullName, email,
        status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
      });
      transaction.create(loginRef, {
        instituteId, staffId: uid, firebaseUid: uid,
        passwordSalt: passwordVerifier.salt, passwordHash: passwordVerifier.hash,
        enabled: true, updatedAtMs: now,
      });
      transaction.create(accountRef, { instituteId, staffId: uid, loginKey, updatedAtMs: now });
      transaction.set(appUserRef, {
        instituteId, name: fullName, email, role: "Staff", status: "active", createdAtMs: now,
      });
      transaction.update(instituteRef, { staffCount: count + 1, updatedAtMs: now });
      const completed = { staffId: uid, staffCount: count + 1, unlimitedStaff: true };
      transaction.create(operationRef, {
        actorUid: request.auth.uid, requestHash, status: "completed", result: completed,
        createdAtMs: now, completedAtMs: now,
      });
      return completed;
    });
    await adminAuth.setCustomUserClaims(uid, {
      staffManaged: true, staff: true, instituteId, staffId: uid,
    });
    return result;
  } catch (error) {
    if (authCreated) await adminAuth.deleteUser(uid).catch(() => {});
    throw error;
  }
}

async function updateStaffAccountHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const staffId = requireString(request.data, "staffId", 128);
  const operationId = optionalDocumentId(request.data, "operationId");
  if (!operationId) throw new HttpsError("invalid-argument", "Operation ID is required.");
  const entity = requireEntity(request.data, "staff");
  const suppliedPassword = request.data && request.data.password;
  if (suppliedPassword != null && suppliedPassword !== "" && !validatePassword(suppliedPassword)) {
    throw new HttpsError("invalid-argument", "Password must contain 6 to 128 characters.");
  }
  const password = typeof suppliedPassword === "string" && suppliedPassword ? suppliedPassword : null;
  await assertCanManageTenantResource(request.auth, instituteId, "manage_staff", false);

  const allowed = ["staffCode", "fullName", "photoUri", "roleTitle", "phone", "email", "address", "joiningDateMs", "monthlySalary", "assignedBatchIds", "notes", "permissions"];
  const staffCode = requireString(entity, "staffCode", 64).toLocaleUpperCase("en-US");
  const fullName = requireString(entity, "fullName", 160);
  const email = requireString(entity, "email", 160).toLocaleLowerCase("en-US");
  optionalString(entity.photoUri, 2048);
  optionalNumber(entity.monthlySalary, 0);
  const newLoginKey = staffLoginDocumentId(staffCode);
  const instituteRef = db.collection("institutes").doc(instituteId);
  const staffRef = instituteRef.collection("staffs").doc(staffId);
  const accountRef = db.collection("staff_auth_accounts").doc(staffId);
  const appUserRef = db.collection("app_users").doc(staffId);
  const operationRef = instituteRef.collection("creation_operations").doc(operationId);
  const requestHash = trustedCreationHash({
    kind: "update_staff", instituteId, staffId,
    entity: { ...entity, staffCode, fullName, email },
    passwordFingerprint: password
      ? createHash("sha256").update(password).digest("hex") : null,
  });

  async function completeAuthSync() {
    const update = { email, displayName: fullName, disabled: false };
    if (password) update.password = password;
    await adminAuth.updateUser(staffId, update);
    await adminAuth.setCustomUserClaims(staffId, {
      staffManaged: true, staff: true, instituteId, staffId,
    });
    const completed = { staffId, staffCode, email, authSyncState: "complete" };
    await operationRef.update({ status: "completed", result: completed, completedAtMs: Date.now() });
    return completed;
  }

  const existingOperation = await operationRef.get();
  if (existingOperation.exists) {
    if (existingOperation.get("actorUid") !== request.auth.uid ||
        existingOperation.get("requestHash") !== requestHash) {
      throw new HttpsError("already-exists", "Operation ID belongs to another staff update.");
    }
    if (existingOperation.get("status") === "completed") return existingOperation.get("result");
    if (existingOperation.get("status") === "pending_auth") return completeAuthSync();
    throw new HttpsError("aborted", "The previous staff update is still being reconciled.");
  }

  const currentAuthUser = await adminAuth.getUser(staffId).catch((error) => {
    if (error.code === "auth/user-not-found") {
      throw new HttpsError("failed-precondition", "This legacy staff login must be recreated once.");
    }
    throw error;
  });
  if (email !== (currentAuthUser.email || "").toLocaleLowerCase("en-US")) {
    const emailOwner = await adminAuth.getUserByEmail(email).catch((error) => {
      if (error.code === "auth/user-not-found") return null;
      throw error;
    });
    if (emailOwner && emailOwner.uid !== staffId) {
      throw new HttpsError("already-exists", "A staff account with this email already exists.");
    }
  }
  const passwordVerifier = password ? await hashPassword(password) : null;

  await db.runTransaction(async (transaction) => {
    const [instituteSnap, staffSnap, accountSnap, appUserSnap, operationSnap] = await Promise.all([
      transaction.get(instituteRef), transaction.get(staffRef), transaction.get(accountRef),
      transaction.get(appUserRef), transaction.get(operationRef),
    ]);
    if (operationSnap.exists) {
      if (operationSnap.get("actorUid") !== request.auth.uid ||
          operationSnap.get("requestHash") !== requestHash) {
        throw new HttpsError("already-exists", "Operation ID belongs to another staff update.");
      }
      return;
    }
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    if (!staffSnap.exists || staffSnap.get("archivedAtMs") != null) {
      throw new HttpsError("not-found", "Active staff profile was not found.");
    }
    if (!accountSnap.exists || accountSnap.get("instituteId") !== instituteId ||
        accountSnap.get("staffId") !== staffId) {
      throw new HttpsError("failed-precondition", "This legacy staff login must be recreated once.");
    }
    const oldLoginKey = accountSnap.get("loginKey");
    if (typeof oldLoginKey !== "string" || !oldLoginKey) {
      throw new HttpsError("failed-precondition", "Staff login mapping is incomplete.");
    }
    const oldLoginRef = db.collection("staff_auth_logins").doc(oldLoginKey);
    const newLoginRef = db.collection("staff_auth_logins").doc(newLoginKey);
    const [oldLoginSnap, newLoginSnap] = await Promise.all([
      transaction.get(oldLoginRef),
      oldLoginKey === newLoginKey ? Promise.resolve(null) : transaction.get(newLoginRef),
    ]);
    if (!oldLoginSnap.exists || oldLoginSnap.get("instituteId") !== instituteId ||
        oldLoginSnap.get("staffId") !== staffId || oldLoginSnap.get("firebaseUid") !== staffId) {
      throw new HttpsError("failed-precondition", "Staff login mapping is incomplete.");
    }
    if (newLoginSnap && newLoginSnap.exists) {
      throw new HttpsError("already-exists", "That Staff ID is already in use.");
    }
    const now = Date.now();
    const nextLogin = {
      ...oldLoginSnap.data(), instituteId, staffId, firebaseUid: staffId,
      enabled: true, updatedAtMs: now,
      ...(passwordVerifier
        ? { passwordSalt: passwordVerifier.salt, passwordHash: passwordVerifier.hash }
        : {}),
    };
    if (oldLoginKey === newLoginKey) transaction.set(oldLoginRef, nextLogin);
    else {
      transaction.create(newLoginRef, nextLogin);
      transaction.delete(oldLoginRef);
      transaction.delete(db.collection("staff_auth_attempts").doc(oldLoginKey));
    }
    transaction.update(accountRef, { loginKey: newLoginKey, status: "active", updatedAtMs: now });
    transaction.update(staffRef, {
      ...copyFields(entity, allowed), instituteId, staffCode, fullName, email, updatedAtMs: now,
    });
    transaction.set(appUserRef, {
      instituteId, name: fullName, email, role: "Staff", status: "active", updatedAtMs: now,
      createdAtMs: appUserSnap.exists ? appUserSnap.get("createdAtMs") || now : now,
    });
    transaction.create(operationRef, {
      actorUid: request.auth.uid, requestHash, status: "pending_auth",
      result: { staffId, staffCode, email, authSyncState: "pending" }, createdAtMs: now,
    });
  });
  return completeAuthSync();
}

async function loginStaffHandler(request) {
  const staffCode = requireString(request.data, "staffCode", 64);
  const password = request.data && request.data.password;
  if (!validatePassword(password)) {
    throw new HttpsError("unauthenticated", "Invalid Staff ID or password.");
  }
  const loginKey = staffLoginDocumentId(staffCode);
  const loginRef = db.collection("staff_auth_logins").doc(loginKey);
  const attemptRef = db.collection("staff_auth_attempts").doc(loginKey);
  const [loginSnap, attemptSnap] = await Promise.all([loginRef.get(), attemptRef.get()]);
  const now = Date.now();
  if (attemptSnap.exists && Number(attemptSnap.get("lockedUntilMs") || 0) > now) {
    throw new HttpsError("resource-exhausted", "Too many login attempts. Try again later.");
  }
  const login = loginSnap.exists ? loginSnap.data() : null;
  const passwordMatches = login && login.enabled === true
    ? await verifyPassword(password, login.passwordSalt, login.passwordHash)
    : await verifyPassword(password, DUMMY_SALT, DUMMY_HASH);
  if (!login || !passwordMatches) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid Staff ID or password.");
  }
  const { instituteId, staffId, firebaseUid } = login;
  if (![instituteId, staffId, firebaseUid].every((value) => typeof value === "string" && value)) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid Staff ID or password.");
  }
  const instituteRef = db.collection("institutes").doc(instituteId);
  const staffRef = instituteRef.collection("staffs").doc(staffId);
  const accountRef = db.collection("staff_auth_accounts").doc(firebaseUid);
  const appUserRef = db.collection("app_users").doc(firebaseUid);
  const [instituteSnap, staffSnap, accountSnap, appUserSnap] = await Promise.all([
    instituteRef.get(), staffRef.get(), accountRef.get(), appUserRef.get(),
  ]);
  const institute = instituteSnap.exists ? instituteSnap.data() : null;
  const staff = staffSnap.exists ? staffSnap.data() : null;
  const account = accountSnap.exists ? accountSnap.data() : null;
  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const identityIsValid = hasActiveSubscription(institute) && institute.isActive !== false &&
    isActiveRecord(staff) && normalizeIdentifier(staff.staffCode) === normalizeIdentifier(staffCode) &&
    account && account.instituteId === instituteId && account.staffId === staffId &&
    account.loginKey === loginKey && appUser && appUser.instituteId === instituteId &&
    appUser.role === "Staff" && appUser.status === "active";
  let userRecord = null;
  if (identityIsValid) {
    try {
      userRecord = await adminAuth.getUser(firebaseUid);
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
  }
  if (!identityIsValid || !userRecord || userRecord.disabled) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid Staff ID or password.");
  }
  await attemptRef.delete().catch(() => {});
  const claims = { staffManaged: true, staff: true, instituteId, staffId };
  const [customToken] = await Promise.all([
    adminAuth.createCustomToken(firebaseUid, claims),
    adminAuth.setCustomUserClaims(firebaseUid, claims),
  ]);
  return { customToken, firebaseUid, instituteId, staffId };
}

async function createRegistrationProfileHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const instituteSnap = await assertCanManageTenantResource(
    request.auth, instituteId, "manage_student", false,
  );
  const institute = instituteSnap.data();
  const instituteName = typeof institute.instituteName === "string" && institute.instituteName.trim()
    ? institute.instituteName.trim().slice(0, 160)
    : typeof institute.name === "string" && institute.name.trim()
      ? institute.name.trim().slice(0, 160) : "Institute";
  const phone = [institute.phone, institute.whatsappNumber]
    .find((value) => typeof value === "string" && value.trim()) || null;
  const logoCandidate = [institute.profilePhotoUri, institute.logoUri]
    .find((value) => typeof value === "string" && value.trim() && !value.startsWith("file:"));
  const profilePhotoUri = logoCandidate ? logoCandidate.trim().slice(0, 2048) : null;
  const slug = buildRegistrationSlug(instituteName, instituteId);
  const now = Date.now();
  const profile = {
    instituteId, instituteName, slug, phone, profilePhotoUri, updatedAtMs: now,
  };
  const batch = db.batch();
  batch.set(db.collection("public_registration_profiles").doc(slug), profile);
  batch.set(db.collection("public_registration_profiles").doc(`id_${instituteId}`), profile);
  await batch.commit();
  const url = registrationFormUrl(slug);
  return {
    instituteId, instituteName, slug, phone, profilePhotoUri, url,
    shareText: `${instituteName}\nOfficial Student Registration Form\n${url}`,
  };
}

/**
 * Updates only owner-managed student profile fields. This deliberately runs in
 * the trusted backend so records awaiting legacy credential cleanup can still
 * receive safe profile changes (especially a replacement photo) without
 * exposing or letting clients alter credential fields.
 */
async function updateStudentProfileHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const studentId = requireString(request.data, "studentId", 128);
  const entity = requireEntity(request.data, "student");
  await assertCanManageStudent(request.auth, instituteId);

  const rawStudentCode = requireString(entity, "studentCode", 64);
  const studentCode = normalizeStudentId(rawStudentCode);
  const validModernStudentCode = isValidStudentId(studentCode);
  const studentCodeNormalized = normalizeIdentifier(studentCode);
  const fullName = requireString(entity, "fullName", 160);
  const phone = requireString(entity, "phone", 32);
  const allowed = [
    "studentCode", "fullName", "photoUri", "gender", "dateOfBirthMs", "phone",
    "email", "address", "schoolName", "className", "guardianName", "guardianPhone",
    "guardianEmail", "emergencyContact", "bloodGroup", "admissionDateMs", "notes",
  ];
  optionalString(entity.photoUri, 2048);
  optionalString(entity.gender, 32);
  optionalString(entity.email, 160);
  optionalString(entity.address, 500);
  optionalString(entity.schoolName, 160);
  optionalString(entity.className, 160);
  optionalString(entity.guardianName, 160);
  optionalString(entity.guardianPhone, 32);
  optionalString(entity.guardianEmail, 160);
  optionalString(entity.emergencyContact, 160);
  optionalString(entity.bloodGroup, 32);
  optionalString(entity.notes, 2000);
  optionalNumber(entity.dateOfBirthMs, 0, 4102444800000);
  optionalNumber(entity.admissionDateMs, 0, 4102444800000);

  const students = db.collection("institutes").doc(instituteId).collection("students");
  const studentRef = students.doc(studentId);
  const studentCodeClaimRef = db.collection("student_id_claims")
    .doc(studentIdClaimDocumentId(studentCode));
  const studentLoginRef = db.collection("student_auth_logins")
    .doc(studentLoginDocumentId(studentCode));
  const normalizedCodeQuery = students.where("studentCodeNormalized", "==", studentCodeNormalized);
  const legacyCodeVariants = [...new Set([
    rawStudentCode.trim(), studentCode, studentCode.toLocaleLowerCase("en-US"),
  ])];
  const legacyCodeQuery = students.where("studentCode", "in", legacyCodeVariants);
  await db.runTransaction(async (transaction) => {
    const [studentSnap, claimSnap, loginSnap, normalizedCodeSnap, legacyCodeSnap] = await Promise.all([
      transaction.get(studentRef),
      transaction.get(studentCodeClaimRef),
      transaction.get(studentLoginRef),
      transaction.get(normalizedCodeQuery),
      transaction.get(legacyCodeQuery),
    ]);
    if (!studentSnap.exists) throw new HttpsError("not-found", "Student not found.");
    const previousStudentCode = typeof studentSnap.get("studentCode") === "string"
      ? normalizeStudentId(studentSnap.get("studentCode")) : "";
    if (!validModernStudentCode && previousStudentCode !== studentCode) {
      throw new HttpsError("invalid-argument", "Student ID must contain 3 to 20 letters, numbers or hyphens.");
    }
    const claimBelongsToStudent = claimSnap.exists &&
      claimSnap.get("instituteId") === instituteId && claimSnap.get("studentId") === studentId;
    const loginBelongsToStudent = loginSnap.exists &&
      loginSnap.get("instituteId") === instituteId && loginSnap.get("studentId") === studentId;
    const duplicateInInstitute = [...normalizedCodeSnap.docs, ...legacyCodeSnap.docs]
      .some((document) => document.id !== studentId);
    if ((validModernStudentCode && claimSnap.exists && !claimBelongsToStudent) ||
        (loginSnap.exists && !loginBelongsToStudent) || duplicateInInstitute) {
      throw new HttpsError("already-exists", "This Student ID is already in use.");
    }

    const previousClaimRef = previousStudentCode
      ? db.collection("student_id_claims").doc(studentIdClaimDocumentId(previousStudentCode)) : null;
    const previousClaimSnap = previousClaimRef && previousClaimRef.path !== studentCodeClaimRef.path
      ? await transaction.get(previousClaimRef) : null;
    const now = Date.now();
    if (validModernStudentCode) {
      transaction.set(studentCodeClaimRef, {
        instituteId,
        studentId,
        studentCode,
        normalizedStudentCode: studentCodeNormalized,
        updatedAtMs: now,
        createdAtMs: claimSnap.exists ? claimSnap.get("createdAtMs") || now : now,
      });
    }
    if (previousClaimRef && previousClaimSnap && previousClaimSnap.exists &&
        previousClaimSnap.get("instituteId") === instituteId &&
        previousClaimSnap.get("studentId") === studentId) {
      transaction.delete(previousClaimRef);
    }
    transaction.update(studentRef, {
      ...copyFields(entity, allowed),
      studentCode,
      studentCodeNormalized,
      fullName,
      phone,
      updatedAtMs: now,
    });
  });
  return { studentId, studentCode };
}

function assertActiveStudent(student) {
  if (!student || student.status !== "active" || student.archivedAtMs != null) {
    throw new HttpsError("failed-precondition", "Only active students can use app access.");
  }
}

function isManagedStudentUser(userRecord, instituteId, studentId) {
  const claims = userRecord && userRecord.customClaims;
  return claims && claims.studentManaged === true &&
    claims.instituteId === instituteId && claims.studentId === studentId;
}

async function getReusableStudentUser(student, instituteId, studentId, displayName) {
  const candidateUid = typeof student.firebaseUid === "string" ? student.firebaseUid : "";
  if (candidateUid) {
    try {
      const existing = await adminAuth.getUser(candidateUid);
      if (isManagedStudentUser(existing, instituteId, studentId)) return { user: existing, created: false };
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
  }

  const uid = `student_${randomUUID().replaceAll("-", "")}`;
  const user = await adminAuth.createUser({ uid, displayName, disabled: true });
  return { user, created: true };
}

async function provisionStudentAccountHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const studentId = requireString(request.data, "studentId");
  const password = request.data && request.data.password;
  if (!validatePassword(password)) {
    throw new HttpsError("invalid-argument", "Password must contain 6 to 128 characters.");
  }

  const managingInstitute = await assertCanManageStudent(request.auth, instituteId);
  assertActiveSubscription(managingInstitute.data());
  const studentRef = db.collection("institutes").doc(instituteId).collection("students").doc(studentId);
  const studentSnap = await studentRef.get();
  if (!studentSnap.exists) throw new HttpsError("not-found", "Student not found.");
  const student = studentSnap.data();
  assertActiveStudent(student);

  // Existing accounts may still carry the pre-v1.7 long Student ID. Keep those
  // working; every newly created or owner-changed ID is validated elsewhere.
  const studentCode = normalizeStudentId(
    requireString({ studentCode: student.studentCode }, "studentCode", 64),
  );
  const loginKey = studentLoginDocumentId(studentCode);
  const passwordVerifier = await hashPassword(password);
  const { user, created } = await getReusableStudentUser(
    student,
    instituteId,
    studentId,
    typeof student.fullName === "string" ? student.fullName.slice(0, 128) : "Student",
  );
  const uid = user.uid;
  const loginRef = db.collection("student_auth_logins").doc(loginKey);
  const studentCodeClaimRef = db.collection("student_id_claims")
    .doc(studentIdClaimDocumentId(studentCode));
  const accountRef = db.collection("student_auth_accounts").doc(uid);
  const legacyMappingRef = db.collection("student_login_mappings").doc(studentCode);
  let identityCommitted = false;

  try {
    await adminAuth.setCustomUserClaims(uid, {
      studentManaged: true,
      instituteId,
      studentId,
    });

    await db.runTransaction(async (transaction) => {
      const [freshStudentSnap, loginSnap, accountSnap, studentCodeClaimSnap] = await Promise.all([
        transaction.get(studentRef),
        transaction.get(loginRef),
        transaction.get(accountRef),
        transaction.get(studentCodeClaimRef),
      ]);
      if (!freshStudentSnap.exists) throw new HttpsError("not-found", "Student not found.");
      assertActiveStudent(freshStudentSnap.data());

      if (loginSnap.exists) {
        const existing = loginSnap.data();
        if (existing.instituteId !== instituteId || existing.studentId !== studentId) {
          throw new HttpsError("already-exists", "This Student ID is already in use.");
        }
      }
      if (studentCodeClaimSnap.exists) {
        const existing = studentCodeClaimSnap.data();
        if (existing.instituteId !== instituteId || existing.studentId !== studentId) {
          throw new HttpsError("already-exists", "This Student ID is already in use.");
        }
      }
      if (accountSnap.exists) {
        const existing = accountSnap.data();
        if (existing.instituteId !== instituteId || existing.studentId !== studentId) {
          throw new HttpsError("failed-precondition", "Student identity link is invalid.");
        }
        if (existing.loginKey && existing.loginKey !== loginKey) {
          transaction.delete(db.collection("student_auth_logins").doc(existing.loginKey));
        }
      }

      const now = Date.now();
      transaction.set(studentCodeClaimRef, {
        instituteId,
        studentId,
        studentCode,
        normalizedStudentCode: normalizeIdentifier(studentCode),
        updatedAtMs: now,
        createdAtMs: studentCodeClaimSnap.exists
          ? studentCodeClaimSnap.get("createdAtMs") || now : now,
      });
      transaction.set(loginRef, {
        instituteId,
        studentId,
        firebaseUid: uid,
        passwordSalt: passwordVerifier.salt,
        passwordHash: passwordVerifier.hash,
        enabled: true,
        updatedAtMs: now,
      });
      transaction.set(accountRef, { instituteId, studentId, loginKey, updatedAtMs: now });
      transaction.update(studentRef, {
        firebaseUid: uid,
        isAppAccessEnabled: true,
        studentPasswordHash: FieldValue.delete(),
        appAccessEmail: FieldValue.delete(),
        updatedAtMs: now,
      });
      transaction.delete(legacyMappingRef);
    });
    identityCommitted = true;

    await adminAuth.updateUser(uid, {
      displayName: typeof student.fullName === "string" ? student.fullName.slice(0, 128) : "Student",
      disabled: false,
    });
    await adminAuth.revokeRefreshTokens(uid);
    return { firebaseUid: uid, studentId, enabled: true };
  } catch (error) {
    if (identityCommitted) {
      await db.runTransaction(async (transaction) => {
        const [currentStudent, currentLogin, currentAccount] = await Promise.all([
          transaction.get(studentRef),
          transaction.get(loginRef),
          transaction.get(accountRef),
        ]);
        if (currentStudent.exists && currentStudent.get("firebaseUid") === uid) {
          transaction.update(studentRef, { isAppAccessEnabled: false, updatedAtMs: Date.now() });
        }
        if (currentLogin.exists && currentLogin.get("firebaseUid") === uid) {
          transaction.delete(loginRef);
        }
        if (currentAccount.exists && currentAccount.get("studentId") === studentId) {
          transaction.delete(accountRef);
        }
      }).catch(() => {});
      await adminAuth.updateUser(uid, { disabled: true }).catch(() => {});
      await adminAuth.revokeRefreshTokens(uid).catch(() => {});
    }
    if (created) {
      await adminAuth.deleteUser(uid).catch(() => {});
    }
    throw error;
  }
}

async function disableStudentIdentity(instituteId, studentId, authContext) {
  await assertCanManageStudent(authContext, instituteId);
  const studentRef = db.collection("institutes").doc(instituteId).collection("students").doc(studentId);
  const studentSnap = await studentRef.get();
  if (!studentSnap.exists) throw new HttpsError("not-found", "Student not found.");
  const student = studentSnap.data();
  const uid = typeof student.firebaseUid === "string" ? student.firebaseUid : "";
  const studentCode = typeof student.studentCode === "string" ? student.studentCode : "";

  let managedUser = null;
  if (uid) {
    try {
      const candidate = await adminAuth.getUser(uid);
      if (isManagedStudentUser(candidate, instituteId, studentId)) managedUser = candidate;
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
  }

  const accountRef = uid ? db.collection("student_auth_accounts").doc(uid) : null;
  const accountSnap = accountRef ? await accountRef.get() : null;
  const loginKey = accountSnap && accountSnap.exists ? accountSnap.get("loginKey") : null;
  await db.runTransaction(async (transaction) => {
    transaction.update(studentRef, {
      isAppAccessEnabled: false,
      studentPasswordHash: FieldValue.delete(),
      appAccessEmail: FieldValue.delete(),
      updatedAtMs: Date.now(),
    });
    if (loginKey) transaction.delete(db.collection("student_auth_logins").doc(loginKey));
    if (accountRef) transaction.delete(accountRef);
    if (studentCode) transaction.delete(db.collection("student_login_mappings").doc(studentCode));
  });

  if (managedUser) {
    await adminAuth.updateUser(uid, { disabled: true });
    await adminAuth.revokeRefreshTokens(uid);
  }
  return { studentId, enabled: false };
}

async function disableStudentAccountHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const studentId = requireString(request.data, "studentId");
  return disableStudentIdentity(instituteId, studentId, request.auth);
}

async function getStudentAccountStatusHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const studentId = requireString(request.data, "studentId");
  await assertCanManageStudent(request.auth, instituteId);
  const studentSnap = await db.collection("institutes").doc(instituteId)
    .collection("students").doc(studentId).get();
  if (!studentSnap.exists) throw new HttpsError("not-found", "Student not found.");
  const student = studentSnap.data();
  const uid = typeof student.firebaseUid === "string" ? student.firebaseUid : "";
  if (!uid || student.isAppAccessEnabled !== true) return { securelyLinked: false };

  const accountSnap = await db.collection("student_auth_accounts").doc(uid).get();
  if (!accountSnap.exists) return { securelyLinked: false };
  const account = accountSnap.data();
  if (account.instituteId !== instituteId || account.studentId !== studentId || !account.loginKey) {
    return { securelyLinked: false };
  }
  const loginSnap = await db.collection("student_auth_logins").doc(account.loginKey).get();
  let userRecord = null;
  try {
    userRecord = await adminAuth.getUser(uid);
  } catch (error) {
    if (error.code !== "auth/user-not-found") throw error;
  }
  const login = loginSnap.exists ? loginSnap.data() : null;
  return {
    securelyLinked: Boolean(
      userRecord && !userRecord.disabled && isManagedStudentUser(userRecord, instituteId, studentId) &&
      login && login.enabled === true && login.instituteId === instituteId &&
      login.studentId === studentId && login.firebaseUid === uid,
    ),
  };
}

async function recordFailedLogin(attemptRef) {
  const now = Date.now();
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(attemptRef);
    const current = snapshot.exists ? snapshot.data() : {};
    const inWindow = typeof current.windowStartedAtMs === "number" &&
      now - current.windowStartedAtMs < ATTEMPT_WINDOW_MS;
    const failedAttempts = (inWindow ? Number(current.failedAttempts || 0) : 0) + 1;
    transaction.set(attemptRef, {
      failedAttempts,
      windowStartedAtMs: inWindow ? current.windowStartedAtMs : now,
      lockedUntilMs: failedAttempts >= MAX_FAILED_ATTEMPTS ? now + LOCK_DURATION_MS : 0,
      updatedAtMs: now,
    });
  });
}

async function loginStudentHandler(request) {
  const studentCode = requireString(request.data, "studentCode", 64);
  const password = request.data && request.data.password;
  if (!validatePassword(password)) {
    throw new HttpsError("unauthenticated", "Invalid student ID or password.");
  }

  const loginKey = studentLoginDocumentId(studentCode);
  const loginRef = db.collection("student_auth_logins").doc(loginKey);
  const attemptRef = db.collection("student_auth_attempts").doc(loginKey);
  const [loginSnap, attemptSnap] = await Promise.all([loginRef.get(), attemptRef.get()]);
  const now = Date.now();
  if (attemptSnap.exists && Number(attemptSnap.get("lockedUntilMs") || 0) > now) {
    throw new HttpsError("resource-exhausted", "Too many login attempts. Try again later.");
  }

  // Seamless one-time migration for accounts created before Student IDs became
  // global. It is intentionally allowed only when exactly one student document
  // has the supplied ID; duplicate legacy IDs must be reset by an institute.
  let legacyLoginRef = null;
  let login = loginSnap.exists ? loginSnap.data() : null;
  if (!login) {
    const legacyCodeVariants = [...new Set([
      studentCode,
      studentCode.toLocaleUpperCase("en-US"),
      studentCode.toLocaleLowerCase("en-US"),
    ])];
    const legacyQuery = db.collectionGroup("students")
      .where("studentCode", legacyCodeVariants.length === 1 ? "==" : "in",
        legacyCodeVariants.length === 1 ? legacyCodeVariants[0] : legacyCodeVariants)
      .limit(2)
    const matches = await legacyQuery.get();
    if (matches.size === 1) {
      const candidate = matches.docs[0];
      const candidateData = candidate.data();
      const candidateInstituteRef = candidate.ref.parent.parent;
      const candidateUid = typeof candidateData.firebaseUid === "string" ? candidateData.firebaseUid : "";
      if (candidateInstituteRef && candidateUid) {
        const candidateAccountRef = db.collection("student_auth_accounts").doc(candidateUid);
        const candidateAccount = await candidateAccountRef.get();
        const oldLoginKey = candidateAccount.exists ? candidateAccount.get("loginKey") : "";
        if (candidateAccount.exists && oldLoginKey && oldLoginKey !== loginKey &&
            candidateAccount.get("instituteId") === candidateInstituteRef.id &&
            candidateAccount.get("studentId") === candidate.id) {
          const oldRef = db.collection("student_auth_logins").doc(oldLoginKey);
          const oldSnap = await oldRef.get();
          const oldLogin = oldSnap.exists ? oldSnap.data() : null;
          if (oldLogin && oldLogin.instituteId === candidateInstituteRef.id &&
              oldLogin.studentId === candidate.id && oldLogin.firebaseUid === candidateUid) {
            login = oldLogin;
            legacyLoginRef = oldRef;
          }
        }
      }
    }
  }

  const passwordMatches = login && login.enabled === true
    ? await verifyPassword(password, login.passwordSalt, login.passwordHash)
    : await verifyPassword(password, DUMMY_SALT, DUMMY_HASH);
  if (!login || !passwordMatches) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid student ID or password.");
  }

  const { instituteId, studentId, firebaseUid } = login;
  if (![instituteId, studentId, firebaseUid].every((value) => typeof value === "string" && value)) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid student ID or password.");
  }

  if (legacyLoginRef) {
    const accountRef = db.collection("student_auth_accounts").doc(firebaseUid);
    await db.runTransaction(async (transaction) => {
      const [globalLoginSnap, accountSnap] = await Promise.all([
        transaction.get(loginRef),
        transaction.get(accountRef),
      ]);
      if (globalLoginSnap.exists) {
        const existing = globalLoginSnap.data();
        if (existing.instituteId !== instituteId || existing.studentId !== studentId ||
            existing.firebaseUid !== firebaseUid) {
          throw new HttpsError("unauthenticated", "Invalid student ID or password.");
        }
      } else {
        transaction.set(loginRef, { ...login, updatedAtMs: Date.now() });
      }
      if (!accountSnap.exists || accountSnap.get("instituteId") !== instituteId ||
          accountSnap.get("studentId") !== studentId) {
        throw new HttpsError("unauthenticated", "Invalid student ID or password.");
      }
      transaction.update(accountRef, { loginKey, updatedAtMs: Date.now() });
      transaction.delete(legacyLoginRef);
    });
  }

  const instituteRef = db.collection("institutes").doc(instituteId);
  const studentRef = instituteRef.collection("students").doc(studentId);
  const accountRef = db.collection("student_auth_accounts").doc(firebaseUid);
  const [instituteSnap, studentSnap, accountSnap] = await Promise.all([
    instituteRef.get(),
    studentRef.get(),
    accountRef.get(),
  ]);
  const institute = instituteSnap.exists ? instituteSnap.data() : null;
  const student = studentSnap.exists ? studentSnap.data() : null;
  const account = accountSnap.exists ? accountSnap.data() : null;
  const identityIsValid = hasActiveSubscription(institute) && student &&
    student.status === "active" && student.archivedAtMs == null &&
    student.isAppAccessEnabled === true && student.firebaseUid === firebaseUid &&
    normalizeIdentifier(student.studentCode) === normalizeIdentifier(studentCode) &&
    account && account.instituteId === instituteId && account.studentId === studentId &&
    account.loginKey === loginKey;

  let userRecord = null;
  if (identityIsValid) {
    try {
      userRecord = await adminAuth.getUser(firebaseUid);
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
  }
  if (!identityIsValid || !userRecord || userRecord.disabled ||
      !isManagedStudentUser(userRecord, instituteId, studentId)) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid student ID or password.");
  }

  await attemptRef.delete().catch(() => {});
  // Credential verification above is the authoritative login event. Writing
  // here means the owner feed is not dependent on a later client page opening.
  await writeStudentActivity({
    db,
    instituteId,
    studentId,
    eventType: "login",
    now,
  }).catch((error) => {
    logger.warn("Student login activity could not be recorded", {
      instituteId,
      studentId,
      errorCode: error && error.code,
    });
  });
  const sessionExpiresAtMs = now + SESSION_DURATION_MS;
  const sessionClaims = {
    studentManaged: true,
    student: true,
    instituteId,
    studentId,
    studentSessionExpiresAt: sessionExpiresAtMs,
  };
  // Put the claims in the custom token for the first ID token, while storing
  // the same claims for later refreshes. These independent Admin calls can run
  // together, which shortens a successful login without weakening its checks.
  const [customToken] = await Promise.all([
    adminAuth.createCustomToken(firebaseUid, sessionClaims),
    adminAuth.setCustomUserClaims(firebaseUid, sessionClaims),
  ]);
  return {
    customToken,
    firebaseUid,
    studentId,
    studentName: typeof student.fullName === "string" ? student.fullName : "Student",
    studentCode: typeof student.studentCode === "string" ? student.studentCode : "",
    instituteId,
    instituteCode: typeof institute.instituteCode === "string" ? institute.instituteCode : "",
    sessionExpiresAtMs,
  };
}

function guarded(handler, operation = null) {
  return async (request) => {
    const startedAtMs = Date.now();
    const correlationId = randomUUID();
    const operationName = operation || handler.name || "trusted_operation";
    try {
      const result = await handler(request);
      const durationMs = Date.now() - startedAtMs;
      if (isSlowCallable(durationMs)) {
        logger.warn("Trusted backend operation is slow", callableTelemetryContext({
          request,
          operation: operationName,
          correlationId,
          durationMs,
        }));
      }
      return result;
    } catch (error) {
      const context = callableTelemetryContext({
        request,
        operation: operationName,
        correlationId,
        durationMs: Date.now() - startedAtMs,
      });
      if (error instanceof HttpsError) {
        const detail = {
          ...context,
          errorCode: error.code,
          errorMessage: error.message,
        };
        if (rejectionLogLevel(error.code) === "info") {
          logger.info("Trusted backend request rejected", detail);
        } else {
          logger.warn("Trusted backend request rejected", {
            ...detail,
            securityRelevant: ["permission-denied", "unauthenticated"].includes(error.code),
          });
        }
        throw error;
      }
      logger.error("Trusted backend operation failed", {
        ...context,
        errorCode: error && error.code,
        errorName: error && error.name,
        errorMessage: error && error.message,
        errorStack: error && typeof error.stack === "string" ? error.stack.slice(0, 2000) : null,
      });
      throw new HttpsError("internal", "Trusted service is temporarily unavailable.");
    }
  };
}

async function writeOperationalHealth(jobName, patch) {
  try {
    await db.collection("platform_health").doc(jobName).set({
      ...patch,
      region: REGION,
    }, { merge: true });
  } catch (error) {
    // Observability must never make a successful business maintenance job fail.
    logger.warn("Operational health record could not be written", {
      jobName,
      errorCode: error && error.code,
      errorMessage: error && error.message,
    });
  }
}

async function runMonitoredScheduledJob(jobName, handler) {
  const startedAtMs = Date.now();
  await writeOperationalHealth(jobName, scheduledHealthPatch({
    status: "running",
    startedAtMs,
  }));
  try {
    const metrics = await handler();
    const finishedAtMs = Date.now();
    await writeOperationalHealth(jobName, scheduledHealthPatch({
      status: "healthy",
      startedAtMs,
      finishedAtMs,
      metrics,
    }));
    return metrics;
  } catch (error) {
    const finishedAtMs = Date.now();
    await writeOperationalHealth(jobName, scheduledHealthPatch({
      status: "failed",
      startedAtMs,
      finishedAtMs,
      error,
    }));
    logger.error("Scheduled backend job failed", {
      jobName,
      durationMs: finishedAtMs - startedAtMs,
      errorCode: error && error.code,
      errorName: error && error.name,
      errorMessage: error && error.message,
    });
    throw error;
  }
}

exports.provisionStudentAccount = onCall(callableOptions, guarded(provisionStudentAccountHandler));
exports.disableStudentAccount = onCall(callableOptions, guarded(disableStudentAccountHandler));
exports.deleteStudentAccount = onCall(callableOptions, guarded(disableStudentAccountHandler));
exports.getStudentAccountStatus = onCall(callableOptions, guarded(getStudentAccountStatusHandler));
exports.loginStudent = onCall(callableOptions, guarded(loginStudentHandler));
exports.recordStudentActivity = onCall(
  callableOptions,
  guarded(createStudentActivityHandler({ db, hasActiveSubscription })),
);
exports.getStudentActivity = onCall(
  callableOptions,
  guarded(createStudentActivityFeedHandler({
    db,
    now: () => Date.now(),
    // This feed intentionally stays owner/admin-only. Staff do not receive a
    // shortcut to app-behaviour history simply by holding student permissions.
    assertCanRead: (auth, instituteId) =>
      assertCanManageTenantResource(auth, instituteId, "manage_student", false),
  })),
);
exports.recordInstituteOwnerLogin = onCall(
  callableOptions,
  guarded(createInstituteOwnerLoginRecorder({ db })),
);
exports.getInstituteOwnerLoginActivity = onCall(
  callableOptions,
  guarded(createInstituteOwnerLoginFeedHandler({ db, assertPlatformRoot })),
);
exports.cleanupInstituteOwnerLoginActivity = onSchedule(
  { region: REGION, schedule: "every day 03:15", timeZone: "Asia/Dhaka", timeoutSeconds: 300, memory: "256MiB" },
  async () => runMonitoredScheduledJob("owner_login_cleanup", async () => {
    const result = await createInstituteOwnerLoginCleanupHandler({ db })();
    logger.info("Institute owner login activity cleanup completed", result);
    return result;
  }),
);
exports.createEntitledStudent = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createEntitledStudentHandler, "registration_approval"),
);
exports.createEntitledBatch = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createEntitledBatchHandler),
);
exports.createEntitledStaff = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createEntitledStaffHandler),
);
exports.provisionStaffAccount = onCall(
  { ...callableOptions, timeoutSeconds: 60, memory: "512MiB" },
  guarded(provisionStaffAccountHandler, "staff_provisioning"),
);
exports.updateStaffAccount = onCall(
  { ...callableOptions, timeoutSeconds: 60, memory: "512MiB" },
  guarded(updateStaffAccountHandler, "staff_update"),
);
exports.loginStaff = onCall(
  callableOptions,
  guarded(loginStaffHandler, "staff_login"),
);
exports.createRegistrationProfile = onCall(
  callableOptions,
  guarded(createRegistrationProfileHandler, "registration_link"),
);
exports.updateStudentProfile = onCall(
  callableOptions,
  guarded(updateStudentProfileHandler),
);
exports.reconcileStudentOperationalSummary = onDocumentWritten(
  { region: REGION, document: "institutes/{instituteId}/students/{entityId}", memory: "256MiB" },
  tenantOperationalSummaryHandler,
);
exports.reconcileBatchOperationalSummary = onDocumentWritten(
  { region: REGION, document: "institutes/{instituteId}/batches/{entityId}", memory: "256MiB" },
  tenantOperationalSummaryHandler,
);
exports.reconcileStaffOperationalSummary = onDocumentWritten(
  { region: REGION, document: "institutes/{instituteId}/staffs/{entityId}", memory: "256MiB" },
  tenantOperationalSummaryHandler,
);
exports.repairSubscriptionEntitlements = onCall(
  { ...callableOptions, timeoutSeconds: 540 },
  guarded(repairSubscriptionEntitlementsHandler),
);
exports.expireElapsedSubscriptions = onSchedule(
  { region: REGION, schedule: "every 15 minutes", timeZone: "Asia/Dhaka", timeoutSeconds: 540, memory: "256MiB" },
  async () => runMonitoredScheduledJob("subscription_expiry_sweep", async () => {
    const now = Date.now();
    let expired = 0;
    let scanned = 0;
    let failed = 0;
    let pages = 0;
    while (pages < EXPIRY_MAX_PAGES_PER_RUN) {
      const page = await db.collection("institutes")
        .where("subscriptionStatus", "in", ["trial", "active"])
        .where("currentPeriodEndMs", "<=", now)
        .limit(MAINTENANCE_PAGE_SIZE)
        .get();
      if (page.empty) break;
      pages += 1;
      scanned += page.size;
      const results = await mapWithConcurrency(
        page.docs,
        MAINTENANCE_CONCURRENCY,
        async (candidate) => {
          try {
            return await db.runTransaction(async (transaction) => {
              const current = await transaction.get(candidate.ref);
              if (!current.exists) return false;
              const institute = current.data();
              if (institute.isActive === false ||
                  !["trial", "active"].includes(institute.subscriptionStatus) ||
                  !Number.isSafeInteger(institute.currentPeriodEndMs) ||
                  institute.currentPeriodEndMs > now) return false;
              transaction.update(candidate.ref, {
                subscriptionStatus: "expired",
                subscriptionExpiredAtMs: now,
              });
              return true;
            });
          } catch (error) {
            failed += 1;
            logger.error("Subscription expiry failed for institute", {
              instituteId: candidate.id,
              errorCode: error && error.code,
            });
            return false;
          }
        },
      );
      const pageExpired = results.filter(Boolean).length;
      expired += pageExpired;
      if (pageExpired === 0) {
        logger.warn("Subscription expiry sweep stopped because its current page made no progress", {
          pageSize: page.size,
          failed,
        });
        break;
      }
    }
    const result = { expired, scanned, failed, pages };
    logger.info("Subscription expiry sweep completed", result);
    return result;
  }),
);
exports.commitFinancialOperation = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createFinancialLedgerHandler({ db })),
);
exports.createExamWithFees = onCall(
  { ...callableOptions, timeoutSeconds: 60, memory: "512MiB" },
  guarded(createExamFeeBillingHandler({ db })),
);
exports.commitSubscriptionOperation = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createSubscriptionBillingHandler({ db, FieldValue })),
);
exports.commitPlatformAdminOperation = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createPlatformAdminHandler({ db, adminAuth })),
);
exports.commitSafeDeletion = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createSafeDeletionHandler({ db, adminAuth })),
);
exports.permanentlyPurgeStudent = onCall(
  // This only opens the callable transport. The runtime still enforces App Check,
  // Firebase authentication, and the institute-owner/Super Admin authorization below.
  { ...callableOptions, timeoutSeconds: 120, memory: "512MiB", invoker: "public" },
  guarded(createPermanentStudentPurgeHandler({
    db, adminAuth, bucket: mediaStorageBucket,
  }), "student_permanent_delete"),
);
exports.permanentlyPurgeBatch = onCall(
  { ...callableOptions, timeoutSeconds: 120, memory: "512MiB" },
  guarded(createPermanentBatchPurgeHandler({ db, bucket: mediaStorageBucket })),
);
exports.permanentlyPurgeStaff = onCall(
  { ...callableOptions, timeoutSeconds: 120, memory: "512MiB" },
  guarded(createPermanentStaffPurgeHandler({ db, adminAuth, bucket: mediaStorageBucket })),
);
exports.permanentlyPurgeInstitute = onCall(
  // Public invocation only opens the callable transport; the handler still
  // requires Firebase Auth plus an active, trusted Super Admin record.
  { ...callableOptions, timeoutSeconds: 540, memory: "1GiB", invoker: "public" },
  guarded(createPermanentInstitutePurgeHandler({ db, adminAuth, bucket: mediaStorageBucket })),
);
exports.uploadSecureMedia = onCall(
  { ...callableOptions, timeoutSeconds: 60, memory: "512MiB" },
  guarded(mediaSecurityHandlers.uploadSecureMedia),
);
exports.getSecureMediaUrl = onCall(
  callableOptions,
  guarded(mediaSecurityHandlers.getSecureMediaUrl),
);
exports.submitPublicRegistration = onRequest(
  {
    region: REGION,
    timeoutSeconds: 30,
    memory: "256MiB",
    secrets: [registrationRateLimitSecret],
  },
  publicRegistrationHandler,
);
