"use strict";

const { randomUUID } = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { FieldValue, getFirestore } = require("firebase-admin/firestore");
const { logger } = require("firebase-functions");
const { defineSecret } = require("firebase-functions/params");
const { HttpsError, onCall, onRequest } = require("firebase-functions/v2/https");
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
  if (isSuperAdmin) return instituteSnap;

  const institute = instituteSnap.data();
  if (institute.isActive === false || institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "Institute is inactive.");
  }
  if (authContext.uid === instituteId) return instituteSnap;

  const isManagedAdmin = appUser && appUser.instituteId === instituteId &&
    ["InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isManagedAdmin) return instituteSnap;

  const staff = staffSnap.exists ? staffSnap.data() : null;
  if (institute.isActive !== false && isActiveRecord(staff) &&
      hasPermission(staff.permissions, "manage_student")) {
    return instituteSnap;
  }
  throw new HttpsError("permission-denied", "Student account management is not allowed.");
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

  const instituteSnap = await assertCanManageStudent(request.auth, instituteId);
  const studentRef = db.collection("institutes").doc(instituteId).collection("students").doc(studentId);
  const studentSnap = await studentRef.get();
  if (!studentSnap.exists) throw new HttpsError("not-found", "Student not found.");
  const student = studentSnap.data();
  assertActiveStudent(student);

  const instituteCode = requireString({ instituteCode: instituteSnap.get("instituteCode") }, "instituteCode", 64);
  const studentCode = requireString({ studentCode: student.studentCode }, "studentCode", 64);
  const loginKey = studentLoginDocumentId(instituteCode, studentCode);
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
          throw new HttpsError("already-exists", "That institute/student ID combination is already in use.");
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
  const instituteCode = requireString(request.data, "instituteCode", 64);
  const studentCode = requireString(request.data, "studentCode", 64);
  const password = request.data && request.data.password;
  if (!validatePassword(password)) {
    throw new HttpsError("unauthenticated", "Invalid institute code, student ID, or password.");
  }

  const loginKey = studentLoginDocumentId(instituteCode, studentCode);
  const loginRef = db.collection("student_auth_logins").doc(loginKey);
  const attemptRef = db.collection("student_auth_attempts").doc(loginKey);
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
    throw new HttpsError("unauthenticated", "Invalid institute code, student ID, or password.");
  }

  const { instituteId, studentId, firebaseUid } = login;
  if (![instituteId, studentId, firebaseUid].every((value) => typeof value === "string" && value)) {
    await recordFailedLogin(attemptRef);
    throw new HttpsError("unauthenticated", "Invalid institute code, student ID, or password.");
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
  const identityIsValid = institute && institute.isActive !== false && student &&
    student.status === "active" && student.archivedAtMs == null &&
    student.isAppAccessEnabled === true && student.firebaseUid === firebaseUid &&
    normalizeIdentifier(institute.instituteCode) === normalizeIdentifier(instituteCode) &&
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
    throw new HttpsError("unauthenticated", "Invalid institute code, student ID, or password.");
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
