"use strict";

const { randomUUID } = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { FieldValue, getFirestore } = require("firebase-admin/firestore");
const { logger } = require("firebase-functions");
const { defineSecret } = require("firebase-functions/params");
const { HttpsError, onCall, onRequest } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const cloudinary = require("cloudinary").v2;
const {
  hasPermission,
  hashPassword,
  normalizeIdentifier,
  studentLoginDocumentId,
  validatePassword,
  verifyPassword,
} = require("./studentAuthCore");
const { createFinancialLedgerHandler } = require("./financialLedger");
const { createMediaSecurityHandlers } = require("./mediaSecurity");
const { createSafeDeletionHandler } = require("./safeDeletion");
const { createPublicRegistrationHandler } = require("./publicRegistration");
const { createSubscriptionBillingHandler } = require("./subscriptionBilling");
const { createPlatformAdminHandler } = require("./platformAdmin");
const { FREE_TRIAL_DURATION_MS, hasCurrentSubscription } = require("./subscriptionPolicy");

initializeApp();

const REGION = "asia-south1";
const SESSION_DURATION_MS = 12 * 60 * 60 * 1000;
const ATTEMPT_WINDOW_MS = 15 * 60 * 1000;
const LOCK_DURATION_MS = 15 * 60 * 1000;
const MAX_FAILED_ATTEMPTS = 5;
const DUMMY_SALT = Buffer.alloc(16, 7).toString("base64");
const DUMMY_HASH = Buffer.alloc(64, 11).toString("base64");
const callableOptions = {
  region: REGION,
  timeoutSeconds: 30,
  memory: "256MiB",
  enforceAppCheck: true,
};
const cloudinaryUrl = defineSecret("CLOUDINARY_URL");
const registrationRateLimitSecret = defineSecret("REGISTRATION_RATE_LIMIT_SECRET");

const db = getFirestore();
const adminAuth = getAuth();

function getConfiguredCloudinary() {
  cloudinary.config(true);
  const config = cloudinary.config();
  if (!config.cloud_name || !config.api_key || !config.api_secret) {
    throw new HttpsError("failed-precondition", "Media service is not configured.");
  }
  return cloudinary;
}

const mediaSecurityHandlers = createMediaSecurityHandlers({ db, getCloudinary: getConfiguredCloudinary });
const publicRegistrationHandler = createPublicRegistrationHandler({
  db,
  rateLimitSecret: registrationRateLimitSecret,
});

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
  if (authContext.uid === instituteId) {
    assertActiveSubscription(institute);
    return instituteSnap;
  }

  const isManagedAdmin = appUser && appUser.instituteId === instituteId &&
    ["InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
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
    ["InstituteOwner", "owner", "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
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
  const institutes = await db.collection("institutes").get();
  let repaired = 0;
  for (const candidate of institutes.docs) {
    await db.runTransaction(async (transaction) => {
      const current = await transaction.get(candidate.ref);
      if (!current.exists) return;
      const institute = current.data();
      const planId = typeof institute.currentPlanId === "string" && institute.currentPlanId
        ? institute.currentPlanId : "plan_free_trial";
      const planSnap = await transaction.get(db.collection("subscription_plans").doc(planId));
      const plan = planSnap.exists ? planSnap.data() : {};
      const createdAt = Number(institute.createdAtMs || institute.createdAt || now);
      const endMs = Number.isSafeInteger(institute.currentPeriodEndMs)
        ? institute.currentPeriodEndMs
        : Number.isSafeInteger(institute.trialEndDate)
          ? institute.trialEndDate
          : createdAt + FREE_TRIAL_DURATION_MS;
      const validStatus = ["trial", "active", "expired", "blocked"].includes(institute.subscriptionStatus);
      const nextStatus = institute.isActive === false ? "blocked"
        : endMs <= now ? "expired"
          : planId === "plan_free_trial" ? "trial" : "active";
      const patch = {};
      if (institute.isActive == null) patch.isActive = true;
      if (!Number.isSafeInteger(institute.currentPeriodEndMs)) patch.currentPeriodEndMs = endMs;
      if (typeof institute.currentPlanId !== "string" || !institute.currentPlanId) patch.currentPlanId = planId;
      if (!validStatus || (institute.subscriptionStatus !== "blocked" && institute.subscriptionStatus !== "expired" && institute.subscriptionStatus !== nextStatus)) {
        patch.subscriptionStatus = nextStatus;
      }
      if (!Number.isSafeInteger(institute.studentLimit) || institute.studentLimit < 1) {
        patch.studentLimit = Number.isSafeInteger(plan.maxStudents) && plan.maxStudents > 0 ? plan.maxStudents : 50;
      }
      if (!Number.isSafeInteger(institute.staffLimit) || institute.staffLimit < 1) {
        patch.staffLimit = Number.isSafeInteger(plan.maxUsers) && plan.maxUsers > 0 ? plan.maxUsers : 1;
      }
      if (Object.keys(patch).length) {
        patch.subscriptionEntitlementsRepairedAtMs = now;
        transaction.update(candidate.ref, patch);
        repaired += 1;
      }
    });
  }
  logger.info("Subscription entitlement repair completed", { repaired, scanned: institutes.size });
  return { repaired, scanned: institutes.size };
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

function planLimit(institute, plan, kind) {
  const stored = kind === "student" ? institute.studentLimit : kind === "staff" ? institute.staffLimit : null;
  if (Number.isSafeInteger(stored) && stored > 0) return stored;
  const fromPlan = kind === "student" ? plan.maxStudents : kind === "batch" ? plan.maxBatches : plan.maxUsers;
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
  const entity = requireEntity(request.data, "student");
  await assertCanManageTenantResource(request.auth, instituteId, "manage_student");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const students = instituteRef.collection("students");
  const studentRef = students.doc(studentId);
  const studentCountQuery = students.where("archivedAtMs", "==", null).count();
  const allowed = ["studentCode", "fullName", "photoUri", "gender", "dateOfBirthMs", "phone", "email", "address", "schoolName", "className", "guardianName", "guardianPhone", "guardianEmail", "emergencyContact", "bloodGroup", "admissionDateMs", "notes"];
  const studentCode = requireString(entity, "studentCode", 64);
  const fullName = requireString(entity, "fullName", 160);
  optionalString(entity.phone, 32); optionalString(entity.email, 160); optionalString(entity.photoUri, 2048);
  optionalNumber(entity.admissionDateMs, 0, 4102444800000);
  return db.runTransaction(async (transaction) => {
    const [instituteSnap, existingSnap, studentCountSnap] = await Promise.all([
      transaction.get(instituteRef), transaction.get(studentRef), transaction.get(studentCountQuery),
    ]);
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    const planSnap = await transaction.get(db.collection("subscription_plans").doc(instituteSnap.get("currentPlanId") || "plan_free_trial"));
    if (!planSnap.exists) throw new HttpsError("failed-precondition", "Subscription plan is unavailable.");
    if (existingSnap.exists) throw new HttpsError("already-exists", "Student already exists.");
    const count = studentCountSnap.data().count;
    const limit = planLimit(instituteSnap.data(), planSnap.data(), "student");
    if (count >= limit) throw new HttpsError("resource-exhausted", `Student limit (${limit}) has been reached.`);
    const now = Date.now();
    transaction.create(studentRef, {
      ...copyFields(entity, allowed), instituteId, studentCode, fullName,
      status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
    });
    transaction.update(instituteRef, { studentCount: count + 1, updatedAtMs: now });
    return { studentId, studentCount: count + 1, studentLimit: limit };
  });
}

async function createEntitledBatchHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const batchId = requireString(request.data, "batchId", 128);
  const entity = requireEntity(request.data, "batch");
  await assertCanManageTenantResource(request.auth, instituteId, "manage_batch");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const batches = instituteRef.collection("batches");
  const batchRef = batches.doc(batchId);
  const batchCountQuery = batches.where("archivedAtMs", "==", null).count();
  const allowed = ["batchCode", "name", "subject", "className", "teacherName", "monthlyFeeAmount", "admissionFeeAmount", "startDateMs", "endDateMs", "scheduleDays", "startTime", "endTime", "maxStudents", "description"];
  const batchCode = requireString(entity, "batchCode", 64);
  const name = requireString(entity, "name", 160);
  optionalNumber(entity.monthlyFeeAmount, 0); optionalNumber(entity.admissionFeeAmount, 0);
  return db.runTransaction(async (transaction) => {
    const [instituteSnap, existingSnap, batchCountSnap] = await Promise.all([
      transaction.get(instituteRef), transaction.get(batchRef), transaction.get(batchCountQuery),
    ]);
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    const planSnap = await transaction.get(db.collection("subscription_plans").doc(instituteSnap.get("currentPlanId") || "plan_free_trial"));
    if (!planSnap.exists) throw new HttpsError("failed-precondition", "Subscription plan is unavailable.");
    if (existingSnap.exists) throw new HttpsError("already-exists", "Batch already exists.");
    const count = batchCountSnap.data().count;
    const limit = planLimit(instituteSnap.data(), planSnap.data(), "batch");
    if (count >= limit) throw new HttpsError("resource-exhausted", `Batch limit (${limit}) has been reached.`);
    const now = Date.now();
    transaction.create(batchRef, {
      ...copyFields(entity, allowed), instituteId, batchCode, name,
      status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
    });
    transaction.update(instituteRef, { batchCount: count + 1, updatedAtMs: now });
    return { batchId, batchCount: count + 1, batchLimit: limit };
  });
}

async function createEntitledStaffHandler(request) {
  const instituteId = requireString(request.data, "instituteId");
  const staffId = requireString(request.data, "staffId", 128);
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
  const staffCountQuery = staffs.where("archivedAtMs", "==", null).count();
  const allowed = ["staffCode", "fullName", "photoUri", "roleTitle", "phone", "email", "address", "joiningDateMs", "monthlySalary", "assignedBatchIds", "notes", "permissions"];
  const staffCode = requireString(entity, "staffCode", 64);
  const fullName = requireString(entity, "fullName", 160);
  optionalString(entity.email, 160); optionalNumber(entity.monthlySalary, 0);
  return db.runTransaction(async (transaction) => {
    const [instituteSnap, existingSnap, staffCountSnap] = await Promise.all([
      transaction.get(instituteRef), transaction.get(staffRef), transaction.get(staffCountQuery),
    ]);
    if (!instituteSnap.exists || !hasActiveSubscription(instituteSnap.data())) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    const planSnap = await transaction.get(db.collection("subscription_plans").doc(instituteSnap.get("currentPlanId") || "plan_free_trial"));
    if (!planSnap.exists) throw new HttpsError("failed-precondition", "Subscription plan is unavailable.");
    if (existingSnap.exists) throw new HttpsError("already-exists", "Staff already exists.");
    const count = staffCountSnap.data().count;
    const limit = planLimit(instituteSnap.data(), planSnap.data(), "staff");
    if (count >= limit) throw new HttpsError("resource-exhausted", `Staff limit (${limit}) has been reached.`);
    const now = Date.now();
    transaction.create(staffRef, {
      ...copyFields(entity, allowed), instituteId, staffCode, fullName,
      status: "active", archivedAtMs: null, createdAtMs: now, updatedAtMs: now,
    });
    transaction.update(instituteRef, { staffCount: count + 1, updatedAtMs: now });
    return { staffId, staffCount: count + 1, staffLimit: limit };
  });
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

  const studentCode = requireString({ studentCode: student.studentCode }, "studentCode", 64);
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
      const [freshStudentSnap, loginSnap, accountSnap] = await Promise.all([
        transaction.get(studentRef),
        transaction.get(loginRef),
        transaction.get(accountRef),
      ]);
      if (!freshStudentSnap.exists) throw new HttpsError("not-found", "Student not found.");
      assertActiveStudent(freshStudentSnap.data());

      if (loginSnap.exists) {
        const existing = loginSnap.data();
        if (existing.instituteId !== instituteId || existing.studentId !== studentId) {
          throw new HttpsError("already-exists", "That Student ID is already in use. Generate a new Student ID.");
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
  const sessionExpiresAtMs = now + SESSION_DURATION_MS;
  await adminAuth.setCustomUserClaims(firebaseUid, {
    studentManaged: true,
    student: true,
    instituteId,
    studentId,
    studentSessionExpiresAt: sessionExpiresAtMs,
  });
  const customToken = await adminAuth.createCustomToken(firebaseUid);
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

function guarded(handler) {
  return async (request) => {
    try {
      return await handler(request);
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      logger.error("Trusted backend operation failed", {
        code: error && error.code,
        message: error && error.message,
      });
      throw new HttpsError("internal", "Trusted service is temporarily unavailable.");
    }
  };
}

exports.provisionStudentAccount = onCall(callableOptions, guarded(provisionStudentAccountHandler));
exports.disableStudentAccount = onCall(callableOptions, guarded(disableStudentAccountHandler));
exports.deleteStudentAccount = onCall(callableOptions, guarded(disableStudentAccountHandler));
exports.getStudentAccountStatus = onCall(callableOptions, guarded(getStudentAccountStatusHandler));
exports.loginStudent = onCall(callableOptions, guarded(loginStudentHandler));
exports.createEntitledStudent = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createEntitledStudentHandler),
);
exports.createEntitledBatch = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createEntitledBatchHandler),
);
exports.createEntitledStaff = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createEntitledStaffHandler),
);
exports.repairSubscriptionEntitlements = onCall(
  { ...callableOptions, timeoutSeconds: 540 },
  guarded(repairSubscriptionEntitlementsHandler),
);
exports.expireElapsedSubscriptions = onSchedule(
  { region: REGION, schedule: "every 15 minutes", timeZone: "Asia/Dhaka", timeoutSeconds: 540, memory: "256MiB" },
  async () => {
    const now = Date.now();
    const snapshot = await db.collection("institutes")
      .where("subscriptionStatus", "in", ["trial", "active"])
      .where("currentPeriodEndMs", "<=", now)
      .get();
    let expired = 0;
    for (const candidate of snapshot.docs) {
      await db.runTransaction(async (transaction) => {
        const current = await transaction.get(candidate.ref);
        if (!current.exists) return;
        const institute = current.data();
        if (institute.isActive !== false && ["trial", "active"].includes(institute.subscriptionStatus) &&
            Number.isSafeInteger(institute.currentPeriodEndMs) && institute.currentPeriodEndMs <= now) {
          transaction.update(candidate.ref, { subscriptionStatus: "expired", subscriptionExpiredAtMs: now });
          expired += 1;
        }
      });
    }
    logger.info("Subscription expiry sweep completed", { expired, scanned: snapshot.size });
  },
);
exports.commitFinancialOperation = onCall(
  { ...callableOptions, timeoutSeconds: 60 },
  guarded(createFinancialLedgerHandler({ db })),
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
exports.uploadSecureMedia = onCall(
  { ...callableOptions, timeoutSeconds: 60, memory: "512MiB", secrets: [cloudinaryUrl] },
  guarded(mediaSecurityHandlers.uploadSecureMedia),
);
exports.getSecureMediaUrl = onCall(
  { ...callableOptions, secrets: [cloudinaryUrl] },
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
