"use strict";

const { FieldValue } = require("firebase-admin/firestore");
const { HttpsError } = require("firebase-functions/v2/https");
const { hasPermission } = require("./studentAuthCore");
const { hasCurrentSubscription, hasUnlimitedTrialStudents } = require("./subscriptionPolicy");
const { parseMediaReference } = require("./mediaSecurityCore");
const {
  canonicalDeletionRequest,
  deletionStateId,
  retainedUntil,
} = require("./safeDeletionCore");

function isActive(data) {
  return data && data.status === "active" && data.archivedAtMs == null;
}

function isSuperAdmin(data) {
  return hasPlatformAdminRole(data) &&
    (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
}

// Platform identities must never be disabled as a side effect of tenant cleanup.
// The role check intentionally ignores status so an already-archived root identity
// is still protected and can be recovered safely.
function hasPlatformAdminRole(data) {
  return data && (["SuperAdmin", "superAdmin", "super_admin"].includes(data.role) ||
    data.platformRole === "root");
}

function resolveInstituteOwnerUid(instituteId, institute) {
  const ownerUid = typeof institute?.ownerUid === "string" ? institute.ownerUid.trim() : "";
  return ownerUid || instituteId;
}

function isProtectedAuthIdentity({ actorUid, authUid, appUser }) {
  return !!authUid && (authUid === actorUid || hasPlatformAdminRole(appUser));
}

function isManagedAdmin(data, instituteId) {
  return data && data.instituteId === instituteId &&
    ["InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(data.role) &&
    (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
}

function assertAuthority({ auth, institute, appUser, staff, instituteId, entityType, action }) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  if (isSuperAdmin(appUser)) return;
  if (entityType === "institute") {
    throw new HttpsError("permission-denied", "Only a trusted SuperAdmin can archive an institute.");
  }
  if (!institute || institute.isActive === false || institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "Institute is unavailable.");
  }
  if (!hasCurrentSubscription(institute)) {
    throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
  }
  if (auth.uid === instituteId || isManagedAdmin(appUser, instituteId)) return;
  const permission = entityType === "student" ? "manage_student"
    : entityType === "staff" ? "manage_staff" : "manage_batch";
  if (isActive(staff) && hasPermission(staff.permissions, permission)) return;
  throw new HttpsError("permission-denied", `${entityType} ${action} is not allowed.`);
}

function entityRef(instituteRef, entityType, entityId) {
  if (entityType === "institute") return instituteRef;
  const collection = entityType === "student" ? "students"
    : entityType === "staff" ? "staffs" : "batches";
  return instituteRef.collection(collection).doc(entityId);
}

function previousState(entityType, entity, appUser) {
  if (entityType === "student") {
    return {
      status: entity.status || "active",
      archivedAtMs: entity.archivedAtMs || null,
      isAppAccessEnabled: entity.isAppAccessEnabled === true,
    };
  }
  if (entityType === "batch" || entityType === "staff") {
    return { status: entity.status || "active", archivedAtMs: entity.archivedAtMs || null };
  }
  return {
    isActive: entity.isActive !== false,
    status: entity.status || null,
    subscriptionStatus: entity.subscriptionStatus || "active",
    archivedAtMs: entity.archivedAtMs || null,
    appUserStatus: appUser && Object.prototype.hasOwnProperty.call(appUser, "status")
      ? appUser.status : null,
  };
}

function counterField(entityType) {
  if (entityType === "student") return "studentCount";
  if (entityType === "batch") return "batchCount";
  if (entityType === "staff") return "staffCount";
  return null;
}

function seatCollection(instituteRef, entityType) {
  if (entityType === "student") return instituteRef.collection("students");
  if (entityType === "batch") return instituteRef.collection("batches");
  if (entityType === "staff") return instituteRef.collection("staffs");
  return null;
}

// Only student seats are subscription-controlled. Archived batches and staff
// can always be restored while the institute subscription itself is active.
function studentSeatLimit(institute, plan) {
  const stored = institute.studentLimit;
  if (Number.isSafeInteger(stored) && stored > 0) return stored;
  const configured = plan.maxStudents;
  if (!Number.isSafeInteger(configured) || configured < 1) {
    throw new HttpsError("failed-precondition", "The subscription plan has no valid limit configuration.");
  }
  return configured;
}

function archivePatch(entityType, operationId, now, retentionUntilMs) {
  const common = {
    status: "archived",
    archivedAtMs: now,
    updatedAtMs: now,
    deletionState: "retained",
    deletionOperationId: operationId,
    retentionUntilMs,
    mediaCleanupState: "retained",
  };
  if (entityType === "student") return { ...common, isAppAccessEnabled: false };
  if (entityType === "institute") {
    return { ...common, isActive: false, subscriptionStatus: "deletion_pending" };
  }
  return common;
}

function restorePatch(entityType, previous, now) {
  const common = {
    updatedAtMs: now,
    archivedAtMs: previous.archivedAtMs || null,
    deletionState: FieldValue.delete(),
    deletionOperationId: FieldValue.delete(),
    retentionUntilMs: FieldValue.delete(),
    mediaCleanupState: FieldValue.delete(),
  };
  if (entityType === "student") {
    return { ...common, status: previous.status || "active", isAppAccessEnabled: false };
  }
  if (entityType === "batch" || entityType === "staff") {
    return { ...common, status: previous.status || "active" };
  }
  return {
    ...common,
    isActive: previous.isActive !== false,
    status: previous.status == null ? FieldValue.delete() : previous.status,
    subscriptionStatus: previous.subscriptionStatus || "active",
  };
}

function resultFor({ parsed, entity, now, retentionUntilMs, authCleanupState }) {
  return {
    operationId: parsed.operationId,
    instituteId: parsed.instituteId,
    entityType: parsed.entityType,
    entityId: parsed.entityId,
    action: parsed.action,
    status: parsed.action === "archive" ? "archived" : (entity.status || "active"),
    archivedAtMs: parsed.action === "archive" ? now : (entity.archivedAtMs || null),
    retentionUntilMs: parsed.action === "archive" ? retentionUntilMs : null,
    isAppAccessEnabled: parsed.entityType === "student" ? false : null,
    subscriptionStatus: parsed.entityType === "institute"
      ? (parsed.action === "archive" ? "deletion_pending" : (entity.subscriptionStatus || "active"))
      : null,
    authCleanupState,
    mediaCleanupState: parsed.action === "archive" ? "retained" : "restored",
    hardDeleteAllowed: false,
  };
}

function isManagedStudentUser(user, instituteId, studentId) {
  const claims = user && user.customClaims;
  return claims && claims.studentManaged === true &&
    claims.instituteId === instituteId && claims.studentId === studentId;
}

async function reconcileAuth({ adminAuth, db, plan }) {
  if (!plan.authUid) return { ...plan.result, authCleanupState: "not_required" };
  const instituteRef = db.collection("institutes").doc(plan.result.instituteId);
  const operationRef = instituteRef.collection("deletion_operations").doc(plan.result.operationId);
  const stateRef = instituteRef.collection("deletion_states")
    .doc(deletionStateId(plan.result.entityType, plan.result.entityId));
  try {
    const stateSnap = await stateRef.get();
    const isCurrent = stateSnap.exists && (plan.disableAuth
      ? stateSnap.get("active") === true &&
        stateSnap.get("archiveOperationId") === plan.result.operationId
      : stateSnap.get("active") === false &&
        stateSnap.get("restoreOperationId") === plan.result.operationId);
    if (!isCurrent) {
      const superseded = { ...plan.result, authCleanupState: "superseded" };
      await operationRef.update({ authCleanupState: "superseded", result: superseded });
      return superseded;
    }
    let user;
    try {
      user = await adminAuth.getUser(plan.authUid);
    } catch (error) {
      if (error && error.code === "auth/user-not-found") {
        const missingResult = { ...plan.result, authCleanupState: "identity_absent" };
        await operationRef.update({ authCleanupState: "identity_absent", result: missingResult });
        return missingResult;
      }
      throw error;
    }
    if (plan.result.entityType === "student" &&
      !isManagedStudentUser(user, plan.result.instituteId, plan.result.entityId)) {
      const invalidResult = { ...plan.result, authCleanupState: "link_invalid" };
      await operationRef.update({ authCleanupState: "link_invalid", result: invalidResult });
      return invalidResult;
    }

    const authAppUserSnap = await db.collection("app_users").doc(plan.authUid).get();
    const authAppUser = authAppUserSnap.exists ? authAppUserSnap.data() : null;
    if (isProtectedAuthIdentity({
      actorUid: plan.actorUid,
      authUid: plan.authUid,
      appUser: authAppUser,
    })) {
      const protectedResult = { ...plan.result, authCleanupState: "protected_platform_identity" };
      await operationRef.update({
        authCleanupState: "protected_platform_identity",
        result: protectedResult,
      });
      return protectedResult;
    }

    await adminAuth.updateUser(plan.authUid, { disabled: plan.disableAuth });
    await adminAuth.revokeRefreshTokens(plan.authUid);

    let completedResult = { ...plan.result, authCleanupState: "complete" };
    if (!plan.disableAuth && plan.restoreStudentAccess && plan.loginRef && plan.accountRef) {
      await db.runTransaction(async (transaction) => {
        const [studentSnap, loginSnap, accountSnap] = await Promise.all([
          transaction.get(plan.targetRef),
          transaction.get(plan.loginRef),
          transaction.get(plan.accountRef),
        ]);
        if (!studentSnap.exists || !loginSnap.exists || !accountSnap.exists) return;
        if (studentSnap.get("deletionState") === "retained") return;
        transaction.update(plan.loginRef, { enabled: true, updatedAtMs: Date.now() });
        transaction.set(plan.accountRef, { status: "active", updatedAtMs: Date.now() }, { merge: true });
        transaction.update(plan.targetRef, { isAppAccessEnabled: true, updatedAtMs: Date.now() });
      });
      completedResult = { ...completedResult, isAppAccessEnabled: true };
    }
    await operationRef.update({ authCleanupState: "complete", result: completedResult });
    return completedResult;
  } catch (error) {
    await operationRef.update({
      authCleanupState: "pending",
      lastError: String(error && error.message || "Auth cleanup failed").slice(0, 500),
      updatedAtMs: Date.now(),
    }).catch(() => {});
    throw new HttpsError(
      "unavailable",
      "The record is secured; authentication reconciliation is still pending.",
    );
  }
}

function createSafeDeletionHandler({ db, adminAuth }) {
  return async (request) => {
    let parsed;
    try {
      parsed = canonicalDeletionRequest(request.data || {});
    } catch (error) {
      throw new HttpsError("invalid-argument", error.message);
    }
    if (!request.auth || !request.auth.uid) {
      throw new HttpsError("unauthenticated", "Sign in is required.");
    }
    const actorUid = request.auth.uid;
    const now = Date.now();
    const instituteRef = db.collection("institutes").doc(parsed.instituteId);
    const operationRef = instituteRef.collection("deletion_operations").doc(parsed.operationId);
    const stateRef = instituteRef.collection("deletion_states")
      .doc(deletionStateId(parsed.entityType, parsed.entityId));
    const targetRef = entityRef(instituteRef, parsed.entityType, parsed.entityId);
    const seats = seatCollection(instituteRef, parsed.entityType);
    const seatCountQuery = seats ? seats.where("status", "==", "active").count() : null;

    const plan = await db.runTransaction(async (transaction) => {
      const actorAppUserRef = db.collection("app_users").doc(actorUid);
      const staffRef = instituteRef.collection("staffs").doc(actorUid);
      const [instituteSnap, appUserSnap, staffSnap, operationSnap, seatCountSnap] = await Promise.all([
        transaction.get(instituteRef),
        transaction.get(actorAppUserRef),
        transaction.get(staffRef),
        transaction.get(operationRef),
        seatCountQuery ? transaction.get(seatCountQuery) : Promise.resolve(null),
      ]);
      if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");
      const institute = instituteSnap.data();
      const appUser = appUserSnap.exists ? appUserSnap.data() : null;
      const staff = staffSnap.exists ? staffSnap.data() : null;
      assertAuthority({
        auth: request.auth,
        institute,
        appUser,
        staff,
        instituteId: parsed.instituteId,
        entityType: parsed.entityType,
        action: parsed.action,
      });

      if (operationSnap.exists) {
        if (operationSnap.get("requestHash") !== parsed.requestHash ||
          operationSnap.get("actorUid") !== actorUid) {
          throw new HttpsError("already-exists", "Operation ID belongs to another request.");
        }
        const existingAuthUid = operationSnap.get("authUid") || null;
        const existingLoginKey = operationSnap.get("loginKey") || null;
        return {
          result: operationSnap.get("result"),
          actorUid,
          authUid: existingAuthUid,
          disableAuth: operationSnap.get("disableAuth") === true,
          restoreStudentAccess: operationSnap.get("restoreStudentAccess") === true,
          targetRef,
          loginRef: existingLoginKey
            ? db.collection("student_auth_logins").doc(existingLoginKey) : null,
          accountRef: existingAuthUid && parsed.entityType === "student"
            ? db.collection("student_auth_accounts").doc(existingAuthUid) : null,
        };
      }

      const [targetSnap, stateSnap] = targetRef.path === instituteRef.path
        ? [instituteSnap, await transaction.get(stateRef)]
        : await Promise.all([transaction.get(targetRef), transaction.get(stateRef)]);
      if (!targetSnap.exists) throw new HttpsError("not-found", `${parsed.entityType} not found.`);
      const entity = targetSnap.data();
      const retentionUntilMs = retainedUntil(now);
      const mediaUri = parsed.entityType === "student" ? (entity.photoUri || null) :
        parsed.entityType === "institute" ? (entity.profilePhotoUri || null) : null;
      const managedMedia = parseMediaReference(mediaUri);
      const mediaAssetRef = managedMedia && managedMedia.instituteId === parsed.instituteId
        ? instituteRef.collection("media_assets").doc(managedMedia.assetId) : null;
      const mediaAssetSnap = mediaAssetRef ? await transaction.get(mediaAssetRef) : null;
      let authUid = null;
      let loginRef = null;
      let accountRef = null;
      let loginKey = null;
      let restoreStudentAccess = false;
      let studentIdentity = null;
      const instituteOwnerUid = parsed.entityType === "institute"
        ? resolveInstituteOwnerUid(parsed.instituteId, entity) : null;
      const targetAppUserRef = instituteOwnerUid
        ? db.collection("app_users").doc(instituteOwnerUid) : null;
      const targetAppUserSnap = targetAppUserRef ? await transaction.get(targetAppUserRef) : null;
      const targetAppUser = targetAppUserSnap && targetAppUserSnap.exists
        ? targetAppUserSnap.data() : null;
      const protectInstituteIdentity = parsed.entityType === "institute" &&
        isProtectedAuthIdentity({ actorUid, authUid: instituteOwnerUid, appUser: targetAppUser });

      if (parsed.entityType === "student") {
        authUid = typeof entity.firebaseUid === "string" ? entity.firebaseUid : null;
        accountRef = authUid ? db.collection("student_auth_accounts").doc(authUid) : null;
        const accountSnap = accountRef ? await transaction.get(accountRef) : null;
        loginKey = accountSnap && accountSnap.exists ? accountSnap.get("loginKey") : null;
        loginRef = loginKey ? db.collection("student_auth_logins").doc(loginKey) : null;
        const loginSnap = loginRef ? await transaction.get(loginRef) : null;
        studentIdentity = { accountSnap, loginSnap };
      } else if (parsed.entityType === "institute") {
        authUid = protectInstituteIdentity ? null : instituteOwnerUid;
      }

      let result;
      if (parsed.action === "archive") {
        if (stateSnap.exists && stateSnap.get("active") === true) {
          throw new HttpsError("already-exists", "This record is already in retained deletion state.");
        }
        const previous = previousState(
          parsed.entityType,
          entity,
          targetAppUserSnap && targetAppUserSnap.exists ? targetAppUserSnap.data() : null,
        );
        if (parsed.entityType === "student") {
          restoreStudentAccess = previous.isAppAccessEnabled === true &&
            !!studentIdentity.accountSnap && studentIdentity.accountSnap.exists &&
            !!studentIdentity.loginSnap && studentIdentity.loginSnap.exists;
          if (loginRef && studentIdentity.loginSnap && studentIdentity.loginSnap.exists) {
            transaction.update(loginRef, { enabled: false, updatedAtMs: now });
          }
          if (accountRef && studentIdentity.accountSnap && studentIdentity.accountSnap.exists) {
            transaction.set(accountRef, { status: "archived", updatedAtMs: now }, { merge: true });
          }
        }
        transaction.update(targetRef, archivePatch(
          parsed.entityType,
          parsed.operationId,
          now,
          retentionUntilMs,
        ));
        const archiveCounter = counterField(parsed.entityType);
        if (archiveCounter) {
          const currentCount = Number.isSafeInteger(institute[archiveCounter]) ? institute[archiveCounter] : 0;
          transaction.update(instituteRef, { [archiveCounter]: Math.max(0, currentCount - 1), updatedAtMs: now });
        }
        if (mediaAssetRef && mediaAssetSnap && mediaAssetSnap.exists &&
            mediaAssetSnap.get("reference") === mediaUri) {
          transaction.update(mediaAssetRef, {
            status: "retained",
            cleanupState: "retained",
            deletionOperationId: parsed.operationId,
            retentionUntilMs,
            updatedAtMs: now,
          });
        }
        if (parsed.entityType === "institute" && !protectInstituteIdentity &&
            targetAppUserSnap && targetAppUserSnap.exists) {
          transaction.update(targetAppUserRef, { status: "archived", updatedAtMs: now });
        }
        transaction.set(stateRef, {
          instituteId: parsed.instituteId,
          entityType: parsed.entityType,
          entityId: parsed.entityId,
          active: true,
          archiveOperationId: parsed.operationId,
          archivedAtMs: now,
          retentionUntilMs,
          previous,
          authUid,
          restoreStudentAccess,
          mediaUri,
          mediaCleanupState: "retained",
        });
        result = resultFor({
          parsed,
          entity,
          now,
          retentionUntilMs,
          authCleanupState: authUid ? "pending" : "not_required",
        });
      } else {
        if (!stateSnap.exists || stateSnap.get("active") !== true) {
          throw new HttpsError("failed-precondition", "No recoverable deletion state exists.");
        }
        const previous = stateSnap.get("previous") || {};
        authUid = stateSnap.get("authUid") || authUid;
        restoreStudentAccess = stateSnap.get("restoreStudentAccess") === true;
        const needsStudentSeatCheck = parsed.entityType === "student" &&
          !hasUnlimitedTrialStudents(institute, now);
        if (seatCountSnap && needsStudentSeatCheck) {
          const planSnap = await transaction.get(
            db.collection("subscription_plans").doc(institute.currentPlanId || "plan_free_trial"),
          );
          if (!planSnap.exists) {
            throw new HttpsError("failed-precondition", "Subscription plan is unavailable.");
          }
          const count = seatCountSnap.data().count;
          const limit = studentSeatLimit(institute, planSnap.data());
          if (count >= limit) {
            throw new HttpsError("resource-exhausted", `Cannot restore: ${parsed.entityType} limit (${limit}) has been reached.`);
          }
        }
        transaction.update(targetRef, restorePatch(parsed.entityType, previous, now));
        const restoreCounter = counterField(parsed.entityType);
        if (restoreCounter) {
          const currentCount = Number.isSafeInteger(institute[restoreCounter]) ? institute[restoreCounter] : 0;
          transaction.update(instituteRef, { [restoreCounter]: currentCount + 1, updatedAtMs: now });
        }
        if (mediaAssetRef && mediaAssetSnap && mediaAssetSnap.exists &&
            mediaAssetSnap.get("reference") === mediaUri &&
            mediaAssetSnap.get("deletionOperationId") === stateSnap.get("archiveOperationId")) {
          transaction.update(mediaAssetRef, {
            status: "active",
            cleanupState: "retained",
            deletionOperationId: FieldValue.delete(),
            retentionUntilMs: FieldValue.delete(),
            updatedAtMs: now,
          });
        }
        if (parsed.entityType === "student") {
          if (loginRef && studentIdentity.loginSnap && studentIdentity.loginSnap.exists) {
            transaction.update(loginRef, { enabled: false, updatedAtMs: now });
          }
          if (accountRef && studentIdentity.accountSnap && studentIdentity.accountSnap.exists) {
            transaction.set(accountRef, { status: "restoring", updatedAtMs: now }, { merge: true });
          }
        }
        if (parsed.entityType === "institute" && !protectInstituteIdentity &&
            targetAppUserSnap && targetAppUserSnap.exists) {
          const appUserStatus = previous.appUserStatus;
          transaction.update(targetAppUserRef, {
            status: appUserStatus == null ? "active" : appUserStatus,
            updatedAtMs: now,
          });
        }
        transaction.update(stateRef, {
          active: false,
          restoreOperationId: parsed.operationId,
          restoredAtMs: now,
          mediaCleanupState: "restored",
        });
        const restoredEntity = { ...entity, ...previous };
        result = resultFor({
          parsed,
          entity: restoredEntity,
          now,
          retentionUntilMs: null,
          authCleanupState: authUid ? "pending" : "not_required",
        });
      }

      const auditRef = instituteRef.collection("deletion_audit").doc(parsed.operationId);
      transaction.create(auditRef, {
        instituteId: parsed.instituteId,
        entityType: parsed.entityType,
        entityId: parsed.entityId,
        action: parsed.action,
        reason: parsed.reason,
        actorUid,
        operationId: parsed.operationId,
        occurredAtMs: now,
        retentionPolicy: "retain_ledger_and_media_until_controlled_review",
      });
      transaction.create(operationRef, {
        instituteId: parsed.instituteId,
        entityType: parsed.entityType,
        entityId: parsed.entityId,
        action: parsed.action,
        actorUid,
        requestHash: parsed.requestHash,
        authUid,
        loginKey,
        disableAuth: parsed.action === "archive",
        restoreStudentAccess,
        authCleanupState: authUid ? "pending" : "not_required",
        mediaCleanupState: result.mediaCleanupState,
        result,
        createdAtMs: now,
        updatedAtMs: now,
      });
      return {
        result,
        actorUid,
        authUid,
        disableAuth: parsed.action === "archive",
        restoreStudentAccess,
        targetRef,
        loginRef,
        accountRef,
      };
    });

    return reconcileAuth({ adminAuth, db, plan });
  };
}

module.exports = {
  createSafeDeletionHandler,
  hasPlatformAdminRole,
  resolveInstituteOwnerUid,
  isProtectedAuthIdentity,
};
