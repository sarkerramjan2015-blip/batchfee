"use strict";

// Platform administration deliberately lives behind one callable boundary.  The
// Android app must never create Auth users, assign platform roles, or change an
// institute owner directly through Firestore.
const { createHash, randomUUID } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const { FREE_TRIAL_DURATION_MS, FREE_TRIAL_STUDENT_LIMIT } = require("./subscriptionPolicy");
const { planFromSnapshot } = require("./defaultSubscriptionPlans");

const PLATFORM_ROLES = new Set(["root", "billing", "support", "operations", "read_only"]);
const ACTIONS = new Set([
  "create_institute",
  "preview_institute_import",
  "transfer_owner",
  "send_owner_recovery",
  "manage_platform_admin",
  "get_platform_dashboard",
]);
const PERMISSIONS = {
  root: new Set(ACTIONS),
  operations: new Set(["create_institute", "preview_institute_import", "transfer_owner", "get_platform_dashboard"]),
  support: new Set(["send_owner_recovery", "get_platform_dashboard"]),
  billing: new Set(["get_platform_dashboard"]),
  read_only: new Set(["get_platform_dashboard"]),
};

function requiredString(data, field, maxLength = 160) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  return value;
}

function optionalString(data, field, maxLength = 500) {
  if (data == null || data[field] == null || data[field] === "") return "";
  if (typeof data[field] !== "string" || data[field].trim().length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return data[field].trim();
}

function requiredEmail(data, field = "email") {
  const email = requiredString(data, field, 254).toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return email;
}

function optionalPhone(data, field = "phone") {
  const value = optionalString(data, field, 32);
  if (value && !/^[0-9+()\-\s]{6,32}$/.test(value)) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function operationHash(data) {
  const normalized = JSON.stringify(data, Object.keys(data).sort());
  return createHash("sha256").update(normalized).digest("hex");
}

function activeUser(data) {
  return data && (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
}

function platformRoleFor(user) {
  if (!activeUser(user)) return null;
  // Existing SuperAdmin users retain root access until they are explicitly
  // migrated by an authenticated Root administrator.  No migration is run here.
  if (["SuperAdmin", "superAdmin", "super_admin"].includes(user.role) && !user.platformRole) return "root";
  return typeof user.platformRole === "string" && PLATFORM_ROLES.has(user.platformRole)
    ? user.platformRole : null;
}

async function assertPermission(db, auth, action) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const userSnap = await db.collection("app_users").doc(auth.uid).get();
  const role = platformRoleFor(userSnap.exists ? userSnap.data() : null);
  if (!role || !PERMISSIONS[role].has(action)) {
    throw new HttpsError("permission-denied", "Your platform role cannot perform this action.");
  }
  return { role, user: userSnap.data() || {} };
}

async function getOrCreateOwner({ adminAuth, email, displayName }) {
  try {
    const existing = await adminAuth.getUserByEmail(email);
    return { user: existing, created: false };
  } catch (error) {
    if (error.code !== "auth/user-not-found") throw error;
  }
  const user = await adminAuth.createUser({
    uid: `owner_${randomUUID().replaceAll("-", "")}`,
    email,
    displayName,
    disabled: false,
  });
  return { user, created: true };
}

function instituteResult(id, values) {
  return {
    instituteId: id,
    instituteName: values.instituteName,
    ownerName: values.ownerName,
    ownerEmail: values.email,
    ownerPhone: values.phone || "",
  };
}

async function createInstitute({ db, adminAuth, request, operationId, requestHash, now }) {
  const instituteName = requiredString(request.data, "instituteName", 120);
  const ownerName = requiredString(request.data, "ownerName", 120);
  const email = requiredEmail(request.data, "ownerEmail");
  const phone = optionalPhone(request.data);
  const address = optionalString(request.data, "address", 300);
  const instituteCode = optionalString(request.data, "instituteCode", 48).toUpperCase();
  const requestedPlanId = optionalString(request.data, "planId", 96) || "plan_free_trial";
  const owner = await getOrCreateOwner({ adminAuth, email, displayName: ownerName });
  const instituteRef = db.collection("institutes").doc(owner.user.uid);
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const planRef = db.collection("subscription_plans").doc(requestedPlanId);
  const trialEndDate = now + FREE_TRIAL_DURATION_MS;
  const values = {
    instituteName,
    ownerName,
    email,
    phone,
    address,
    instituteCode,
    requestedPlanId,
  };
  const result = instituteResult(owner.user.uid, values);
  try {
    await db.runTransaction(async (transaction) => {
      const existingOperation = await transaction.get(operationRef);
      if (existingOperation.exists) {
        if (existingOperation.get("requestHash") !== requestHash || existingOperation.get("actorUid") !== request.auth.uid) {
          throw new HttpsError("already-exists", "Operation ID was already used for another request.");
        }
        return;
      }
      const [existingInstitute, planSnap, ownerRecord] = await Promise.all([
        transaction.get(instituteRef), transaction.get(planRef), transaction.get(db.collection("app_users").doc(owner.user.uid)),
      ]);
      if (existingInstitute.exists) throw new HttpsError("already-exists", "This owner already has an institute.");
      const plan = planFromSnapshot(planSnap, requestedPlanId);
      if (!plan) throw new HttpsError("not-found", "Selected subscription plan was not found.");
      if (ownerRecord.exists && ownerRecord.get("instituteId") && ownerRecord.get("instituteId") !== owner.user.uid) {
        throw new HttpsError("failed-precondition", "This email is already assigned to another institute.");
      }
      transaction.create(instituteRef, {
        instituteName,
        ownerName,
        ownerUid: owner.user.uid,
        email,
        phone,
        whatsappNumber: phone,
        address,
        instituteCode,
        currentPlanId: requestedPlanId,
        subscriptionStatus: requestedPlanId === "plan_free_trial" ? "trial" : "active",
        trialEndDate,
        currentPeriodEndMs: trialEndDate,
        isActive: true,
        studentLimit: requestedPlanId === "plan_free_trial"
          ? FREE_TRIAL_STUDENT_LIMIT
          : (Number.isSafeInteger(plan.maxStudents) ? plan.maxStudents : 0),
        staffLimit: Number.isSafeInteger(plan.maxUsers) ? plan.maxUsers : 0,
        createdAt: now,
        createdAtMs: now,
      });
      transaction.set(db.collection("app_users").doc(owner.user.uid), {
        name: ownerName,
        email,
        role: "InstituteOwner",
        instituteId: owner.user.uid,
        createdAtMs: now,
        status: "active",
      }, { merge: true });
      transaction.create(db.collection("platform_audit").doc(operationId), {
        operationId,
        action: "create_institute",
        actorUid: request.auth.uid,
        instituteId: owner.user.uid,
        createdAtMs: now,
        details: { instituteName, ownerEmail: email, planId: requestedPlanId, ownerCreated: owner.created },
      });
      transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "create_institute", result, createdAtMs: now });
    });
  } catch (error) {
    if (owner.created) await adminAuth.deleteUser(owner.user.uid).catch(() => {});
    throw error;
  }
  let recoveryLink = "";
  try { recoveryLink = await adminAuth.generatePasswordResetLink(email); } catch (_) { /* provisioning stays valid without e-mail delivery */ }
  return { ...result, recoveryLink, ownerCreated: owner.created };
}

async function previewImport({ db, rows }) {
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > 100) {
    throw new HttpsError("invalid-argument", "Import must contain 1 to 100 rows.");
  }
  const emails = new Set();
  const result = [];
  for (let index = 0; index < rows.length; index += 1) {
    const row = rows[index] && typeof rows[index] === "object" ? rows[index] : {};
    const issues = [];
    let email = "";
    try {
      requiredString(row, "instituteName", 120);
      requiredString(row, "ownerName", 120);
      email = requiredEmail(row, "ownerEmail");
      optionalPhone(row);
    } catch (error) { issues.push(error.message); }
    if (email && emails.has(email)) issues.push("Duplicate owner email in this CSV.");
    emails.add(email);
    result.push({ row: index + 1, ownerEmail: email, valid: issues.length === 0, issues });
  }
  const validEmails = result.filter((row) => row.valid).map((row) => row.ownerEmail);
  const existing = new Set();
  for (let offset = 0; offset < validEmails.length; offset += 10) {
    const chunk = validEmails.slice(offset, offset + 10);
    if (!chunk.length) continue;
    const snapshots = await db.collection("app_users").where("email", "in", chunk).get();
    snapshots.forEach((snap) => existing.add(String(snap.get("email") || "").toLowerCase()));
  }
  const planIds = [...new Set(rows.map((row) => typeof row.planId === "string" && row.planId.trim()
    ? row.planId.trim() : "plan_free_trial"))];
  const planSnaps = await Promise.all(planIds.map((planId) => db.collection("subscription_plans").doc(planId).get()));
  const existingPlans = new Set(planSnaps
    .filter((snap) => planFromSnapshot(snap, snap.id))
    .map((snap) => snap.id));
  return result.map((row, index) => {
    const requestedPlan = typeof rows[index]?.planId === "string" && rows[index].planId.trim()
      ? rows[index].planId.trim() : "plan_free_trial";
    if (existing.has(row.ownerEmail)) {
      return { ...row, valid: false, issues: [...row.issues, "An existing platform record uses this email."] };
    }
    if (!existingPlans.has(requestedPlan)) {
      return { ...row, valid: false, issues: [...row.issues, "Selected subscription plan was not found."] };
    }
    return row;
  });
}

async function dashboardMetrics(db) {
  const now = Date.now();
  const [institutesSnap, receiptsSnap] = await Promise.all([
    db.collection("institutes").get(),
    db.collectionGroup("subscription_receipts").get(),
  ]);
  const monthStartDate = new Date(now);
  monthStartDate.setUTCDate(1);
  monthStartDate.setUTCHours(0, 0, 0, 0);
  const monthStart = monthStartDate.getTime();
  const retained = institutesSnap.docs.filter((doc) => doc.get("deletionState") === "retained");
  const active = institutesSnap.docs.filter((doc) => {
    const end = Number(doc.get("currentPeriodEndMs") || doc.get("trialEndDate") || 0);
    return doc.get("deletionState") !== "retained" && doc.get("isActive") !== false && end > now;
  });
  const revenue = receiptsSnap.docs.reduce((totals, doc) => {
    const amount = Number(doc.get("amountPaid") || 0);
    const approvedAt = Number(doc.get("approvedAt") || doc.get("startDateMs") || 0);
    if (Number.isFinite(amount)) {
      totals.lifetime += amount;
      if (approvedAt >= monthStart) totals.thisMonth += amount;
    }
    return totals;
  }, { lifetime: 0, thisMonth: 0 });
  const expiringWithin = (days) => active.filter((doc) => {
    const end = Number(doc.get("currentPeriodEndMs") || doc.get("trialEndDate") || 0);
    return end <= now + days * 24 * 60 * 60 * 1000;
  }).length;
  return {
    snapshotAtMs: now,
    totalInstitutes: institutesSnap.size - retained.length,
    activeInstitutes: active.length,
    expiringIn7Days: expiringWithin(7),
    expiringIn30Days: expiringWithin(30),
    totalStudents: institutesSnap.docs.reduce((sum, doc) => sum + Number(doc.get("studentCount") || 0), 0),
    totalStaff: institutesSnap.docs.reduce((sum, doc) => sum + Number(doc.get("staffCount") || 0), 0),
    lifetimeRevenue: Math.round(revenue.lifetime * 100) / 100,
    thisMonthRevenue: Math.round(revenue.thisMonth * 100) / 100,
    canonicalReceiptCount: receiptsSnap.size,
  };
}

async function transferOwner({ db, adminAuth, request, operationId, requestHash, now }) {
  const instituteId = requiredString(request.data, "instituteId", 128);
  const ownerName = requiredString(request.data, "ownerName", 120);
  const email = requiredEmail(request.data, "ownerEmail");
  const reason = requiredString(request.data, "reason", 500);
  const owner = await getOrCreateOwner({ adminAuth, email, displayName: ownerName });
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const instituteRef = db.collection("institutes").doc(instituteId);
  const result = { instituteId, ownerUid: owner.user.uid, ownerName, ownerEmail: email };
  try {
    await db.runTransaction(async (transaction) => {
      const [operationSnap, instituteSnap, newOwnerSnap] = await Promise.all([
        transaction.get(operationRef), transaction.get(instituteRef), transaction.get(db.collection("app_users").doc(owner.user.uid)),
      ]);
      if (operationSnap.exists) {
        if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== request.auth.uid) {
          throw new HttpsError("already-exists", "Operation ID was already used for another request.");
        }
        return;
      }
      if (!instituteSnap.exists || instituteSnap.get("deletionState") === "retained") {
        throw new HttpsError("not-found", "Active institute not found.");
      }
      const oldOwnerUid = typeof instituteSnap.get("ownerUid") === "string" ? instituteSnap.get("ownerUid") : instituteId;
      if (newOwnerSnap.exists && newOwnerSnap.get("instituteId") && newOwnerSnap.get("instituteId") !== instituteId) {
        throw new HttpsError("failed-precondition", "This account belongs to another institute.");
      }
      transaction.update(instituteRef, { ownerUid: owner.user.uid, ownerName, email, ownerTransferAtMs: now });
      transaction.set(db.collection("app_users").doc(owner.user.uid), {
        name: ownerName, email, role: "InstituteOwner", instituteId, status: "active", createdAtMs: now,
      }, { merge: true });
      if (oldOwnerUid !== owner.user.uid) {
        transaction.set(db.collection("app_users").doc(oldOwnerUid), {
          role: "InstituteAdmin", instituteId, status: "active", ownerTransferredAtMs: now,
        }, { merge: true });
      }
      transaction.create(db.collection("platform_audit").doc(operationId), {
        operationId, action: "transfer_owner", actorUid: request.auth.uid, instituteId, createdAtMs: now,
        details: { previousOwnerUid: oldOwnerUid, newOwnerUid: owner.user.uid, ownerEmail: email, reason },
      });
      transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "transfer_owner", result, createdAtMs: now });
    });
  } catch (error) {
    if (owner.created) await adminAuth.deleteUser(owner.user.uid).catch(() => {});
    throw error;
  }
  let recoveryLink = "";
  try { recoveryLink = await adminAuth.generatePasswordResetLink(email); } catch (_) {}
  return { ...result, recoveryLink };
}

async function sendOwnerRecovery({ db, adminAuth, request, operationId, requestHash, now }) {
  const instituteId = requiredString(request.data, "instituteId", 128);
  const reason = requiredString(request.data, "reason", 500);
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const instituteRef = db.collection("institutes").doc(instituteId);
  const instituteSnap = await instituteRef.get();
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");
  const email = requiredEmail({ email: instituteSnap.get("email") });
  const result = { instituteId, ownerEmail: email };
  await db.runTransaction(async (transaction) => {
    const existing = await transaction.get(operationRef);
    if (existing.exists) {
      if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return;
    }
    transaction.create(db.collection("platform_audit").doc(operationId), {
      operationId, action: "send_owner_recovery", actorUid: request.auth.uid, instituteId, createdAtMs: now,
      details: { ownerEmail: email, reason },
    });
    transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "send_owner_recovery", result, createdAtMs: now });
  });
  return { ...result, recoveryLink: await adminAuth.generatePasswordResetLink(email) };
}

async function managePlatformAdmin({ db, adminAuth, request, operationId, requestHash, now }) {
  const email = requiredEmail(request.data);
  const name = requiredString(request.data, "name", 120);
  const platformRole = requiredString(request.data, "platformRole", 32).toLowerCase();
  if (!PLATFORM_ROLES.has(platformRole) || platformRole === "root") {
    throw new HttpsError("invalid-argument", "Only a separately provisioned non-root platform role is allowed here.");
  }
  const owner = await getOrCreateOwner({ adminAuth, email, displayName: name });
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const result = { userId: owner.user.uid, email, name, platformRole };
  try {
    await db.runTransaction(async (transaction) => {
      const existing = await transaction.get(operationRef);
      if (existing.exists) {
        if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
          throw new HttpsError("already-exists", "Operation ID was already used for another request.");
        }
        return;
      }
      transaction.set(db.collection("app_users").doc(owner.user.uid), {
        name, email, role: "PlatformAdmin", platformRole, instituteId: null, status: "active", createdAtMs: now,
      }, { merge: true });
      transaction.create(db.collection("platform_audit").doc(operationId), {
        operationId, action: "manage_platform_admin", actorUid: request.auth.uid, createdAtMs: now,
        details: { managedUserUid: owner.user.uid, email, platformRole },
      });
      transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "manage_platform_admin", result, createdAtMs: now });
    });
  } catch (error) {
    if (owner.created) await adminAuth.deleteUser(owner.user.uid).catch(() => {});
    throw error;
  }
  return { ...result, recoveryLink: await adminAuth.generatePasswordResetLink(email) };
}

function createPlatformAdminHandler({ db, adminAuth }) {
  return async (request) => {
    const action = requiredString(request.data, "action", 64);
    const operationId = requiredString(request.data, "operationId", 128);
    if (!ACTIONS.has(action) || !/^[A-Za-z0-9_-]{16,128}$/.test(operationId)) {
      throw new HttpsError("invalid-argument", "Invalid platform administration operation.");
    }
    await assertPermission(db, request.auth, action);
    const hash = operationHash(request.data || {});
    const now = Date.now();
    if (action === "create_institute") return createInstitute({ db, adminAuth, request, operationId, requestHash: hash, now });
    if (action === "preview_institute_import") {
      return { rows: await previewImport({ db, rows: request.data.rows }) };
    }
    if (action === "get_platform_dashboard") return dashboardMetrics(db);
    if (action === "transfer_owner") return transferOwner({ db, adminAuth, request, operationId, requestHash: hash, now });
    if (action === "send_owner_recovery") return sendOwnerRecovery({ db, adminAuth, request, operationId, requestHash: hash, now });
    return managePlatformAdmin({ db, adminAuth, request, operationId, requestHash: hash, now });
  };
}

module.exports = {
  ACTIONS,
  PERMISSIONS,
  PLATFORM_ROLES,
  createPlatformAdminHandler,
  platformRoleFor,
};
