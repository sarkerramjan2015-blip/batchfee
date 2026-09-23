"use strict";

// Platform administration deliberately lives behind one callable boundary.  The
// Android app must never create Auth users, assign platform roles, or change an
// institute owner directly through Firestore.
const { createHash, randomUUID } = require("node:crypto");
const { FieldPath } = require("firebase-admin/firestore");
const { HttpsError } = require("firebase-functions/v2/https");
const { FREE_TRIAL_DURATION_MS, FREE_TRIAL_STUDENT_LIMIT } = require("./subscriptionPolicy");
const { planFromSnapshot } = require("./defaultSubscriptionPlans");
const { SMS_UNIT_COST_PAISA, SMS_PROVIDER_RECHARGE_CHARGE_PERCENT, dhakaUsageKeys, walletDefaults } = require("./smsWallet");

const PLATFORM_ROLES = new Set(["root", "billing", "support", "operations", "read_only"]);
const NON_ROOT_PLATFORM_ROLES = new Set(["billing", "support", "operations", "read_only"]);
const PLATFORM_MEMBER_STATUSES = new Set(["active", "suspended"]);
const INSTITUTE_ACCOUNT_ROLES = new Set([
  "InstituteOwner", "owner", "InstituteAdmin", "admin", "instituteAdmin", "institute_admin",
]);
const ACTIONS = new Set([
  "create_institute",
  "preview_institute_import",
  "transfer_owner",
  "send_owner_recovery",
  "manage_platform_admin",
  "list_platform_admins",
  "update_platform_admin",
  "query_institute_directory",
  "query_institute_timeline",
  "query_student_support",
  "get_student_support_details",
  "list_client_notes",
  "create_client_note",
  "query_support_queue",
  "update_support_case",
  "review_support_case",
  "list_support_case_notes",
  "get_platform_dashboard",
  "get_business_intelligence",
]);
const PERMISSIONS = {
  root: new Set(ACTIONS),
  operations: new Set(["create_institute", "preview_institute_import", "transfer_owner", "query_institute_directory", "get_platform_dashboard"]),
  // Support may find a student and maintain the private client-note history,
  // but only Root can open a student's detail view or the institute timeline.
  support: new Set([
    "send_owner_recovery", "query_institute_directory", "query_student_support",
    "list_client_notes", "create_client_note", "query_support_queue",
    "update_support_case", "list_support_case_notes", "get_platform_dashboard",
  ]),
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

/**
 * Auth users can be shared by sign-in providers, but their BatchFee authority
 * must never be repurposed by a Super Admin form. In particular, assigning a
 * platform account to an institute would silently overwrite root/support
 * access. Keep platform and institute identities strictly separate.
 */
function assertCanAssignInstituteOwner(existingUser, instituteId) {
  if (!existingUser) return;
  if (platformRoleFor(existingUser)) {
    throw new HttpsError("failed-precondition", "A platform account cannot be assigned to an institute.");
  }
  const assignedInstituteId = typeof existingUser.instituteId === "string"
    ? existingUser.instituteId.trim() : "";
  if (assignedInstituteId && assignedInstituteId !== instituteId) {
    throw new HttpsError("failed-precondition", "This account belongs to another institute.");
  }
  const role = typeof existingUser.role === "string" ? existingUser.role : "";
  if (role && !INSTITUTE_ACCOUNT_ROLES.has(role)) {
    throw new HttpsError("failed-precondition", "This account cannot be converted into an institute owner.");
  }
}

function assertCanAssignPlatformRole(existingUser) {
  if (!existingUser) return;
  const assignedInstituteId = typeof existingUser.instituteId === "string"
    ? existingUser.instituteId.trim() : "";
  if (assignedInstituteId) {
    throw new HttpsError("failed-precondition", "An institute account cannot receive a platform role.");
  }
  if (platformRoleFor(existingUser) === "root") {
    throw new HttpsError("failed-precondition", "Root access cannot be replaced from this screen.");
  }
  const role = typeof existingUser.role === "string" ? existingUser.role : "";
  if (role && role !== "PlatformAdmin") {
    throw new HttpsError("failed-precondition", "This account cannot be converted into a platform administrator.");
  }
}

function isManageablePlatformMember(user) {
  return Boolean(user) && user.role === "PlatformAdmin" &&
    typeof user.platformRole === "string" && NON_ROOT_PLATFORM_ROLES.has(user.platformRole);
}

/** Deliberately excludes credentials, recovery URLs, tokens, and tenant identity. */
function publicPlatformMember(userId, user) {
  return {
    userId,
    name: typeof user.name === "string" ? user.name : "",
    email: typeof user.email === "string" ? user.email : "",
    platformRole: user.platformRole,
    status: user.status === "suspended" ? "suspended" : "active",
    createdAtMs: Number(user.createdAtMs || 0),
    updatedAtMs: Number(user.updatedAtMs || user.createdAtMs || 0),
  };
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

async function createInstitute({ db, adminAuth, request, actor, operationId, requestHash, now }) {
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
      assertCanAssignInstituteOwner(ownerRecord.exists ? ownerRecord.data() : null, owner.user.uid);
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
      transactionActivity(transaction, db, owner.user.uid, `institute_${operationId}`, {
        action: "institute_created",
        actorUid: request.auth.uid,
        actorRole: actor.role,
        targetType: "institute",
        targetId: owner.user.uid,
        now,
        summary: "Institute provisioned",
      });
      transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "create_institute", result, createdAtMs: now });
    });
  } catch (error) {
    if (owner.created) await adminAuth.deleteUser(owner.user.uid).catch(() => { });
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
  const [
    institutesSnap,
    receiptsSnap,
    pendingSubscriptionSnap,
    approvedPaymentRequests,
    rejectedPaymentRequests,
  ] = await Promise.all([
    db.collection("institutes").get(),
    db.collectionGroup("subscription_receipts").get(),
    db.collection("subscriptionRequests").where("status", "==", "pending").get(),
    db.collectionGroup("payment_requests").where("status", "==", "approved").count().get(),
    db.collectionGroup("payment_requests").where("status", "==", "rejected").count().get(),
  ]);
  // Revenue and institute-growth windows follow Dhaka calendar months, not
  // UTC months — a receipt around Dhaka midnight must land in the correct
  // business month.
  const monthStart = Date.parse(`${dhakaUsageKeys(now).monthKey}-01T00:00:00Z`) - 6 * 3600 * 1000;
  const nonRetainedInstitutes = institutesSnap.docs.filter((doc) => doc.get("deletionState") !== "retained");
  // "Active" mirrors firestore.rules hasActiveSubscription: the institute must be
  // enabled (isActive !== false), hold a trial/active subscription status, and still
  // be inside its current subscription period. Statuses such as past_due, expired,
  // blocked, or cancelled must not count as active.
  const active = nonRetainedInstitutes.filter((doc) => {
    const end = Number(doc.get("currentPeriodEndMs") || doc.get("trialEndDate") || 0);
    const status = String(doc.get("subscriptionStatus") || "");
    return doc.get("isActive") !== false
      && (status === "trial" || status === "active")
      && end > now;
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
  const newInstitutesThisMonth = nonRetainedInstitutes.filter((doc) => {
    const createdAtMs = safeMillis(doc.get("createdAt"), safeMillis(doc.get("createdAtMs"), 0));
    return createdAtMs > 0 && createdAtMs >= monthStart;
  }).length;
  const pendingSubscriptionAmount = pendingSubscriptionSnap.docs.reduce(
    (sum, doc) => sum + (Number(doc.get("amountPaid")) || 0), 0,
  );
  const approvedCount = approvedPaymentRequests.data().count || 0;
  const rejectedCount = rejectedPaymentRequests.data().count || 0;
  const reviewedCount = approvedCount + rejectedCount;
  return {
    snapshotAtMs: now,
    // "Institutes" is the true total of every institute document, including
    // soft-deleted ("retained") records.
    totalInstitutes: institutesSnap.size,
    activeInstitutes: active.length,
    expiringIn7Days: expiringWithin(7),
    expiringIn30Days: expiringWithin(30),
    totalStudents: nonRetainedInstitutes.reduce((sum, doc) => sum + Number(doc.get("studentCount") || 0), 0),
    totalStaff: nonRetainedInstitutes.reduce((sum, doc) => sum + Number(doc.get("staffCount") || 0), 0),
    lifetimeRevenue: Math.round(revenue.lifetime * 100) / 100,
    thisMonthRevenue: Math.round(revenue.thisMonth * 100) / 100,
    canonicalReceiptCount: receiptsSnap.size,
    newInstitutesThisMonth,
    pendingSubscriptionAmountBdt: Math.round(pendingSubscriptionAmount * 100) / 100,
    pendingSubscriptionCount: pendingSubscriptionSnap.size,
    paymentRequestApprovalRate: reviewedCount > 0
      ? Math.round((approvedCount / reviewedCount) * 1000) / 10 : null,
    paymentRequestApprovedCount: approvedCount,
    paymentRequestRejectedCount: rejectedCount,
  };
}

// ---------------------------------------------------------------------------
// Safe, cursor-based institute directory
// ---------------------------------------------------------------------------
// The Android client must not attempt global directory search against the small
// window it happens to have loaded.  Firestore cannot perform an arbitrary
// substring OR-search across institute/owner/contact fields, so this trusted
// service scans a bounded, server-owned cursor window and returns only the
// allowlisted fields needed by the directory card.
const DIRECTORY_PAGE_SIZES = new Set([25, 50, 100]);
const DIRECTORY_STATUSES = new Set(["all", "trial", "active", "expired", "blocked"]);
const DIRECTORY_RENEWAL_WINDOWS = new Set(["all", "7days", "30days", "expired"]);
const DIRECTORY_ACTIVITY_WINDOWS = new Set(["all", "today", "7days", "30days", "inactive30", "never"]);
const DIRECTORY_SCAN_CHUNK_SIZE = 250;
const DIRECTORY_MAX_SCANNED_DOCUMENTS = 2_000;
const DIRECTORY_DAY_MS = 24 * 60 * 60 * 1_000;

function safeMillis(value, fallback = 0) {
  if (typeof value === "number" && Number.isFinite(value)) return Math.trunc(value);
  if (value instanceof Date) return value.getTime();
  if (value && typeof value.toMillis === "function") {
    const millis = value.toMillis();
    return typeof millis === "number" && Number.isFinite(millis) ? Math.trunc(millis) : fallback;
  }
  return fallback;
}

function normalizedText(value) {
  return typeof value === "string" ? value.trim().toLocaleLowerCase() : "";
}

function normalizedPhone(value) {
  const digits = typeof value === "string" ? value.replace(/\D/g, "") : "";
  if (digits.startsWith("880") && digits.length >= 13) return `0${digits.slice(3)}`;
  return digits;
}

function optionalNonNegativeInteger(value, field) {
  if (value == null || value === "") return null;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0 || value > 10_000_000) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function directoryValue(value, allowed, field) {
  if (value == null || value === "") return "all";
  if (typeof value !== "string" || !allowed.has(value)) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function directorySignature(filters, pageSize) {
  return createHash("sha256").update(JSON.stringify({
    query: filters.query,
    planId: filters.planId,
    status: filters.status,
    renewalWindow: filters.renewalWindow,
    activityWindow: filters.activityWindow,
    minStudentCount: filters.minStudentCount,
    maxStudentCount: filters.maxStudentCount,
    pageSize,
  })).digest("base64url");
}

function encodeDirectoryCursor(cursor) {
  return Buffer.from(JSON.stringify(cursor)).toString("base64url");
}

function decodeDirectoryCursor(value, signature) {
  if (!value) return null;
  if (typeof value !== "string" || value.length > 1_024) {
    throw new HttpsError("invalid-argument", "Invalid directory cursor.");
  }
  try {
    const cursor = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (!cursor || cursor.v !== 1 || cursor.signature !== signature ||
      !Number.isSafeInteger(cursor.createdAtMs) || cursor.createdAtMs < 0 ||
      typeof cursor.id !== "string" || !cursor.id || cursor.id.length > 256) {
      throw new Error("invalid cursor");
    }
    return { createdAtMs: cursor.createdAtMs, id: cursor.id };
  } catch (_) {
    throw new HttpsError("invalid-argument", "This directory cursor is no longer valid. Refresh the directory.");
  }
}

function normalizeDirectoryRequest(data) {
  const filtersInput = data && typeof data.filters === "object" && !Array.isArray(data.filters)
    ? data.filters : {};
  const pageSize = data?.pageSize == null ? 50 : data.pageSize;
  if (!DIRECTORY_PAGE_SIZES.has(pageSize)) {
    throw new HttpsError("invalid-argument", "Directory page size must be 25, 50, or 100.");
  }
  const query = optionalString(data, "query", 120);
  const planId = optionalString(filtersInput, "planId", 96);
  const minStudentCount = optionalNonNegativeInteger(filtersInput.minStudentCount, "minStudentCount");
  const maxStudentCount = optionalNonNegativeInteger(filtersInput.maxStudentCount, "maxStudentCount");
  if (minStudentCount != null && maxStudentCount != null && minStudentCount > maxStudentCount) {
    throw new HttpsError("invalid-argument", "Minimum student count cannot exceed maximum student count.");
  }
  const filters = {
    query: normalizedText(query),
    planId,
    status: directoryValue(filtersInput.status, DIRECTORY_STATUSES, "directory status"),
    renewalWindow: directoryValue(filtersInput.renewalWindow, DIRECTORY_RENEWAL_WINDOWS, "renewal window"),
    activityWindow: directoryValue(filtersInput.activityWindow, DIRECTORY_ACTIVITY_WINDOWS, "activity window"),
    minStudentCount,
    maxStudentCount,
  };
  const signature = directorySignature(filters, pageSize);
  return {
    filters,
    pageSize,
    signature,
    cursor: decodeDirectoryCursor(data?.cursor, signature),
  };
}

function publicDirectoryInstitute(id, data, now) {
  const createdAtMs = safeMillis(data.createdAt, safeMillis(data.createdAtMs, 0));
  const trialEndDateMs = safeMillis(data.trialEndDate, 0);
  const currentPeriodEndMs = safeMillis(data.currentPeriodEndMs, trialEndDateMs);
  const storedStatus = typeof data.subscriptionStatus === "string" ? data.subscriptionStatus : "";
  const currentPlanId = typeof data.currentPlanId === "string" && data.currentPlanId
    ? data.currentPlanId : "plan_free_trial";
  const isActive = data.isActive !== false;
  const subscriptionStatus = !isActive || storedStatus === "blocked"
    ? "blocked"
    : storedStatus === "expired" || (currentPeriodEndMs > 0 && currentPeriodEndMs <= now)
      ? "expired"
      : storedStatus === "trial" || currentPlanId === "plan_free_trial"
        ? "trial" : "active";
  return {
    instituteId: id,
    instituteName: typeof data.instituteName === "string" ? data.instituteName : "Institute",
    ownerName: typeof data.ownerName === "string" ? data.ownerName : "",
    ownerEmail: typeof data.email === "string" ? data.email : "",
    phone: typeof data.phone === "string" ? data.phone
      : (typeof data.whatsappNumber === "string" ? data.whatsappNumber : ""),
    instituteCode: typeof data.instituteCode === "string" ? data.instituteCode : "",
    currentPlanId,
    subscriptionStatus,
    currentPeriodEndMs,
    createdAtMs,
    lastActiveAtMs: safeMillis(data.lastActiveAt, 0),
    studentCount: Math.max(0, Math.trunc(Number(data.studentCount) || 0)),
    staffCount: Math.max(0, Math.trunc(Number(data.staffCount) || 0)),
    batchCount: Math.max(0, Math.trunc(Number(data.batchCount) || 0)),
  };
}

function matchesDirectoryFilters(row, filters, now) {
  if (filters.status !== "all" && row.subscriptionStatus !== filters.status) return false;
  if (filters.planId && row.currentPlanId !== filters.planId) return false;
  if (filters.minStudentCount != null && row.studentCount < filters.minStudentCount) return false;
  if (filters.maxStudentCount != null && row.studentCount > filters.maxStudentCount) return false;

  if (filters.renewalWindow === "expired") {
    if (row.subscriptionStatus !== "expired") return false;
  } else if (filters.renewalWindow !== "all") {
    const days = filters.renewalWindow === "7days" ? 7 : 30;
    if (row.currentPeriodEndMs <= now || row.currentPeriodEndMs > now + days * DIRECTORY_DAY_MS) return false;
  }

  const age = row.lastActiveAtMs > 0 ? now - row.lastActiveAtMs : null;
  if (filters.activityWindow === "today" && !(age != null && age <= DIRECTORY_DAY_MS)) return false;
  if (filters.activityWindow === "7days" && !(age != null && age <= 7 * DIRECTORY_DAY_MS)) return false;
  if (filters.activityWindow === "30days" && !(age != null && age <= 30 * DIRECTORY_DAY_MS)) return false;
  if (filters.activityWindow === "inactive30" && !(age == null || age > 30 * DIRECTORY_DAY_MS)) return false;
  if (filters.activityWindow === "never" && age != null) return false;

  if (!filters.query) return true;
  const searchableText = [row.instituteName, row.instituteCode, row.ownerName, row.ownerEmail]
    .map(normalizedText)
    .join(" ");
  if (searchableText.includes(filters.query)) return true;
  const requestedPhone = normalizedPhone(filters.query);
  return requestedPhone.length >= 3 && normalizedPhone(row.phone).includes(requestedPhone);
}

async function queryInstituteDirectory({ db, request, now }) {
  const directory = normalizeDirectoryRequest(request.data);
  const rows = [];
  let scanned = 0;
  let cursor = directory.cursor;
  let hasMore = false;

  while (scanned < DIRECTORY_MAX_SCANNED_DOCUMENTS && rows.length < directory.pageSize) {
    let query = db.collection("institutes")
      .orderBy("createdAt", "desc")
      .orderBy(FieldPath.documentId(), "desc")
      // Fetch one sentinel record so `hasMore` is accurate without a client
      // page that advances to an avoidable empty result.
      .limit(DIRECTORY_SCAN_CHUNK_SIZE + 1);
    if (cursor) query = query.startAfter(cursor.createdAtMs, cursor.id);
    const page = await query.get();
    if (page.empty) break;
    const hasDocumentAfterChunk = page.docs.length > DIRECTORY_SCAN_CHUNK_SIZE;
    const documents = page.docs.slice(0, DIRECTORY_SCAN_CHUNK_SIZE);

    for (let index = 0; index < documents.length; index += 1) {
      const document = documents[index];
      const data = document.data() || {};
      const createdAtMs = safeMillis(data.createdAt, safeMillis(data.createdAtMs, 0));
      scanned += 1;
      // Advance the scan cursor even when a malformed legacy record cannot be
      // displayed. Otherwise one bad document could make the next page repeat
      // forever.
      cursor = { createdAtMs, id: document.id };
      // Every newly created institute already carries createdAt. Legacy records
      // without it are skipped rather than returning an unstable cursor page.
      if (createdAtMs <= 0) continue;
      if (data.deletionState !== "retained") {
        const row = publicDirectoryInstitute(document.id, data, now);
        if (matchesDirectoryFilters(row, directory.filters, now)) rows.push(row);
      }
      if (rows.length === directory.pageSize) {
        hasMore = index < documents.length - 1 || hasDocumentAfterChunk;
        break;
      }
      if (scanned >= DIRECTORY_MAX_SCANNED_DOCUMENTS) {
        // If this was the final document in the final server chunk, do not
        // advertise an avoidable empty "next" page. Otherwise the cursor is
        // still useful because more documents remain either in this chunk or
        // in a later chunk.
        hasMore = index < documents.length - 1 || hasDocumentAfterChunk;
        break;
      }
    }
    if (rows.length === directory.pageSize || scanned >= DIRECTORY_MAX_SCANNED_DOCUMENTS) break;
    if (!hasDocumentAfterChunk) break;
    hasMore = true;
  }

  return {
    results: rows,
    pageSize: directory.pageSize,
    scannedDocuments: scanned,
    hasMore,
    nextCursor: hasMore && cursor
      ? encodeDirectoryCursor({ v: 1, signature: directory.signature, ...cursor }) : "",
  };
}

// ---------------------------------------------------------------------------
// Root-only business intelligence: churn risk, rankings, and forecasts
// ---------------------------------------------------------------------------
// Every number here is derived from already-persisted Firestore data. Nothing
// is guessed: missing activity counters simply contribute no signal until the
// counter writers (payment callable + attendance trigger) populate them.
const BI_RISK_LIMIT = 200;
const BI_RANKING_LIMIT = 8;
const BI_ACTIVITY_WINDOW_DAYS = 30;
const BI_RISK_RANK = { high: 0, medium: 1, low: 2 };

function roundMoney2(value) {
  return Math.round(Number(value || 0) * 100) / 100;
}

function institutePathId(documentRef) {
  return documentRef && documentRef.parent && documentRef.parent.parent
    ? documentRef.parent.parent.id : "";
}

function countByInstitute(documents) {
  const counts = new Map();
  for (const doc of documents) {
    const instituteId = institutePathId(doc.ref);
    if (!instituteId) continue;
    counts.set(instituteId, (counts.get(instituteId) || 0) + 1);
  }
  return counts;
}

function churnRiskRows({ institutesSnap, studentsAdded, studentsLost, pendingPaymentRequests, now }) {
  const rows = [];
  for (const doc of institutesSnap.docs) {
    const data = doc.data() || {};
    if (data.deletionState === "retained") continue;
    const base = publicDirectoryInstitute(doc.id, data, now);
    let daysSinceActive = null;
    if (base.lastActiveAtMs > 0) {
      daysSinceActive = Math.floor((now - base.lastActiveAtMs) / DIRECTORY_DAY_MS);
    } else if (base.createdAtMs > 0) {
      // An institute that never reported activity is aged from creation.
      daysSinceActive = Math.floor((now - base.createdAtMs) / DIRECTORY_DAY_MS);
    }
    const smsBalance = Math.max(0, Math.trunc(Number(data.sms_balance) || 0));
    const smsPurchased = Math.max(0, Math.trunc(Number(data.total_sms_purchased) || 0));
    const studentsAdded30d = studentsAdded.get(doc.id) || 0;
    const studentsLost30d = studentsLost.get(doc.id) || 0;
    const lastCollectionAtMs = safeMillis(data.lastCollectionAtMs, 0);
    const lastAttendanceAtMs = safeMillis(data.lastAttendanceAtMs, 0);
    const pendingPaymentRequestCount = pendingPaymentRequests.get(doc.id) || 0;

    const reasons = [];
    let score = 0;
    if (daysSinceActive != null && daysSinceActive > 21) {
      score += 2;
      reasons.push(`No app login in ${daysSinceActive} days`);
    } else if (daysSinceActive != null && daysSinceActive > 7) {
      score += 1;
      reasons.push(`Inactive for ${daysSinceActive} days`);
    }
    if (smsPurchased > 0 && smsBalance <= 0) {
      score += 1;
      reasons.push("SMS credit exhausted");
    }
    if (base.subscriptionStatus === "expired" || base.subscriptionStatus === "blocked") {
      score += 3;
      reasons.push(base.subscriptionStatus === "expired" ? "Subscription expired" : "Subscription blocked");
    } else if (base.currentPeriodEndMs > 0
        && base.currentPeriodEndMs <= now + 3 * DIRECTORY_DAY_MS) {
      score += 1;
      reasons.push("Subscription expires within 3 days");
    }
    if (studentsLost30d > studentsAdded30d) {
      score += 1;
      reasons.push(`${studentsLost30d} student(s) archived vs ${studentsAdded30d} added in 30 days`);
    }
    if (lastCollectionAtMs > 0 && now - lastCollectionAtMs > BI_ACTIVITY_WINDOW_DAYS * DIRECTORY_DAY_MS) {
      score += 1;
      reasons.push("No fee collection in 30 days");
    }
    if (lastAttendanceAtMs > 0 && now - lastAttendanceAtMs > BI_ACTIVITY_WINDOW_DAYS * DIRECTORY_DAY_MS) {
      score += 1;
      reasons.push("No attendance in 30 days");
    }

    rows.push({
      instituteId: base.instituteId,
      instituteName: base.instituteName,
      phone: base.phone,
      whatsappNumber: typeof data.whatsappNumber === "string" ? data.whatsappNumber : base.phone,
      currentPlanId: base.currentPlanId,
      subscriptionStatus: base.subscriptionStatus,
      currentPeriodEndMs: base.currentPeriodEndMs,
      lastActiveAtMs: base.lastActiveAtMs,
      daysSinceActive,
      smsBalance,
      studentsAdded30d,
      studentsLost30d,
      lastCollectionAtMs,
      lastAttendanceAtMs,
      pendingPaymentRequestCount,
      risk: score >= 4 ? "high" : score >= 2 ? "medium" : "low",
      reasons,
    });
  }
  rows.sort((a, b) => BI_RISK_RANK[a.risk] - BI_RISK_RANK[b.risk]
    || (b.daysSinceActive ?? 0) - (a.daysSinceActive ?? 0)
    || a.instituteName.localeCompare(b.instituteName));
  return rows.slice(0, BI_RISK_LIMIT);
}

function instituteRankings({ institutesSnap, receiptsSnap, pendingSubscriptionSnap, pendingPaymentRequestsSnap, studentsAdded, now }) {
  const names = new Map();
  const revenue = new Map();
  const outstanding = new Map();
  const smsConsumption = new Map();
  for (const doc of institutesSnap.docs) {
    const data = doc.data() || {};
    names.set(doc.id, typeof data.instituteName === "string" ? data.instituteName : doc.id);
    // Wallet counters reset when the stored month key is stale, so the raw
    // field can carry last month's usage. The authoritative reader applies
    // that rollover; never sum the raw field directly.
    smsConsumption.set(doc.id, Math.max(0, Math.trunc(Number(walletDefaults(data, now).sms_used_this_month) || 0)));
  }
  for (const doc of receiptsSnap.docs) {
    const instituteId = typeof doc.get("instituteId") === "string" ? doc.get("instituteId") : "";
    if (!instituteId) continue;
    revenue.set(instituteId, (revenue.get(instituteId) || 0) + (Number(doc.get("amountPaid")) || 0));
  }
  for (const doc of pendingSubscriptionSnap.docs) {
    const instituteId = typeof doc.get("instituteId") === "string" ? doc.get("instituteId") : "";
    outstanding.set(instituteId, (outstanding.get(instituteId) || 0) + (Number(doc.get("amountPaid")) || 0));
  }
  for (const doc of pendingPaymentRequestsSnap.docs) {
    const instituteId = institutePathId(doc.ref);
    outstanding.set(instituteId, (outstanding.get(instituteId) || 0) + (Number(doc.get("amount")) || 0));
  }
  const rank = (entries) => entries
    .sort((a, b) => b[1] - a[1])
    .slice(0, BI_RANKING_LIMIT)
    .map(([instituteId, value]) => ({
      instituteId,
      instituteName: names.get(instituteId) || instituteId,
      value: roundMoney2(value),
    }));
  return {
    topRevenue: rank([...revenue.entries()]),
    topOutstanding: rank([...outstanding.entries()]),
    topSmsConsumers: rank([...smsConsumption.entries()].filter(([, value]) => value > 0)),
    fastestGrowing: rank([...studentsAdded.entries()].filter(([, value]) => value > 0)),
  };
}

function subscriptionForecast({ receiptsSnap, now }) {
  const currentMonthKey = dhakaUsageKeys(now).monthKey;
  const monthKeys = [];
  for (let offset = 2; offset >= 0; offset -= 1) {
    const base = new Date(`${currentMonthKey}-01T00:00:00Z`);
    base.setUTCMonth(base.getUTCMonth() - offset);
    monthKeys.push(base.toISOString().slice(0, 7));
  }
  const totals = new Map(monthKeys.map((key) => [key, 0]));
  for (const doc of receiptsSnap.docs) {
    const startDateMs = safeMillis(doc.get("startDateMs"), safeMillis(doc.get("approvedAt"), 0));
    if (startDateMs <= 0) continue;
    // Bucket by the Dhaka calendar month of the receipt start date.
    const key = dhakaUsageKeys(startDateMs).monthKey;
    if (!totals.has(key)) continue;
    totals.set(key, totals.get(key) + (Number(doc.get("amountPaid")) || 0));
  }
  const trailingMonths = monthKeys.map((key) => ({
    monthKey: key,
    collectedBdt: roundMoney2(totals.get(key)),
  }));
  const average = trailingMonths.reduce((sum, month) => sum + month.collectedBdt, 0)
    / Math.max(1, trailingMonths.length);
  const confidence = receiptsSnap.size >= 12 ? "high" : receiptsSnap.size >= 6 ? "medium" : "low";
  return {
    trailingMonths,
    forecast7dBdt: roundMoney2((average / 30.44) * 7),
    forecast30dBdt: roundMoney2(average),
    confidence,
  };
}

async function smsForecast({ smsProvider, institutesSnap, now }) {
  const day = dhakaUsageKeys(now);
  const dayOfMonth = Number(day.dayKey.slice(8, 10));
  let monthSms = 0;
  let outstandingSms = 0;
  for (const doc of institutesSnap.docs) {
    const data = doc.data() || {};
    monthSms += Math.max(0, Math.trunc(Number(walletDefaults(data, now).sms_used_this_month) || 0));
    outstandingSms += Math.max(0, Math.trunc(Number(data.sms_balance) || 0));
  }
  const dailyBurnSms = dayOfMonth > 0 ? Math.round(monthSms / dayOfMonth) : 0;
  let providerBalance = null;
  let providerError = "";
  if (smsProvider) {
    try {
      providerBalance = await smsProvider.getBalance();
    } catch (error) {
      providerError = error && error.message ? error.message.slice(0, 120) : "Zend balance unavailable.";
    }
  }
  const centralCapacitySms = providerBalance && Number.isFinite(providerBalance.balance)
    ? Math.floor(providerBalance.balance * 100 / SMS_UNIT_COST_PAISA) : null;
  const daysOfCentralCapacityLeft = centralCapacitySms != null && dailyBurnSms > 0
    ? Math.floor(centralCapacitySms / dailyBurnSms) : null;
  const suggestedReorderSms = centralCapacitySms != null
    ? Math.max(0, outstandingSms + dailyBurnSms * 7 - centralCapacitySms) : null;
  // Central procurement pays the provider recharge charge (2%), not the
  // owner-facing service charge (1.8%).
  const providerEffectiveCostPaisa = SMS_UNIT_COST_PAISA * (1 + SMS_PROVIDER_RECHARGE_CHARGE_PERCENT / 100);
  return {
    monthSms,
    dailyBurnSms,
    outstandingSms,
    centralCapacitySms,
    daysOfCentralCapacityLeft,
    suggestedReorderSms,
    suggestedReorderAmountBdt: suggestedReorderSms != null
      ? roundMoney2(suggestedReorderSms * providerEffectiveCostPaisa / 100) : null,
    providerError,
  };
}

async function businessIntelligence({ db, smsProvider, now = Date.now() }) {
  const cutoff30d = now - BI_ACTIVITY_WINDOW_DAYS * DIRECTORY_DAY_MS;
  const [
    institutesSnap,
    receiptsSnap,
    pendingSubscriptionSnap,
    pendingPaymentRequestsSnap,
    addedStudentsSnap,
    lostStudentsSnap,
  ] = await Promise.all([
    db.collection("institutes").get(),
    db.collectionGroup("subscription_receipts").get(),
    db.collection("subscriptionRequests").where("status", "==", "pending").get(),
    db.collectionGroup("payment_requests").where("status", "==", "pending").get(),
    db.collectionGroup("students").where("createdAtMs", ">=", cutoff30d).get(),
    db.collectionGroup("students").where("archivedAtMs", ">=", cutoff30d).get(),
  ]);
  const studentsAdded = countByInstitute(addedStudentsSnap.docs);
  const studentsLost = countByInstitute(lostStudentsSnap.docs);
  const pendingPaymentRequests = countByInstitute(pendingPaymentRequestsSnap.docs);
  const [subscription, sms] = await Promise.all([
    subscriptionForecast({ receiptsSnap, now }),
    smsForecast({ smsProvider, institutesSnap, now }),
  ]);
  return {
    snapshotAtMs: now,
    churnRisk: churnRiskRows({
      institutesSnap, studentsAdded, studentsLost, pendingPaymentRequests, now,
    }),
    rankings: instituteRankings({
      institutesSnap, receiptsSnap, pendingSubscriptionSnap, pendingPaymentRequestsSnap, studentsAdded, now,
    }),
    forecast: { subscription, sms },
  };
}

// ---------------------------------------------------------------------------
// Root activity timeline, safe student support, and internal client notes
// ---------------------------------------------------------------------------
// All of these paths are deliberately served by this callable rather than by
// direct Firestore reads. That keeps staff/student/guardian fields out of the
// platform UI unless a Root user deliberately opens a support detail view.
const PLATFORM_ACTIVITY_PAGE_SIZES = new Set([25, 50, 100]);
const STUDENT_SUPPORT_PAGE_SIZES = new Set([25, 50]);
const CLIENT_NOTE_PAGE_SIZES = new Set([25, 50]);
const CLIENT_NOTE_STATUSES = new Set(["open", "follow_up", "resolved"]);
const STUDENT_SUPPORT_SCAN_CHUNK_SIZE = 250;
const STUDENT_SUPPORT_MAX_SCANNED_DOCUMENTS = 2_000;

function opaqueCursor(payload) {
  return Buffer.from(JSON.stringify(payload)).toString("base64url");
}

function parseOpaqueCursor(value, signature, type) {
  if (!value) return null;
  if (typeof value !== "string" || value.length > 2_048) {
    throw new HttpsError("invalid-argument", `Invalid ${type} cursor.`);
  }
  try {
    const cursor = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (!cursor || cursor.v !== 1 || cursor.signature !== signature) throw new Error("invalid cursor");
    return cursor;
  } catch (_) {
    throw new HttpsError("invalid-argument", `This ${type} cursor is no longer valid. Refresh and try again.`);
  }
}

function cursorSignature(payload) {
  return createHash("sha256").update(JSON.stringify(payload)).digest("base64url");
}

function requiredInstituteId(data) {
  const instituteId = requiredString(data, "instituteId", 128);
  if (instituteId.includes("/")) throw new HttpsError("invalid-argument", "Invalid institute ID.");
  return instituteId;
}

function platformActivityRef(db, instituteId, eventId) {
  return db.collection("institutes").doc(instituteId)
    .collection("platform_activity_events").doc(eventId);
}

function newPlatformActivity({ action, actorUid, actorRole, targetType, targetId, now, summary, supportReason = "" }) {
  return {
    action,
    actorUid,
    actorRole,
    targetType,
    targetId,
    outcome: "completed",
    summary,
    supportReason,
    occurredAtMs: now,
  };
}

function transactionActivity(transaction, db, instituteId, eventId, values) {
  transaction.create(platformActivityRef(db, instituteId, eventId), newPlatformActivity(values));
}

function publicPlatformActivity(id, data) {
  return {
    eventId: id,
    action: typeof data.action === "string" ? data.action : "platform_activity",
    actorRole: typeof data.actorRole === "string" ? data.actorRole : "platform",
    targetType: typeof data.targetType === "string" ? data.targetType : "",
    targetId: typeof data.targetId === "string" ? data.targetId : "",
    outcome: data.outcome === "failed" ? "failed" : "completed",
    summary: typeof data.summary === "string" ? data.summary.slice(0, 240) : "",
    occurredAtMs: safeMillis(data.occurredAtMs, 0),
  };
}

async function assertActiveInstitute(db, instituteId) {
  const snapshot = await db.collection("institutes").doc(instituteId).get();
  if (!snapshot.exists || snapshot.get("deletionState") === "retained") {
    throw new HttpsError("not-found", "Active institute not found.");
  }
  return snapshot;
}

function normalizeTimelineRequest(data) {
  const instituteId = requiredInstituteId(data);
  const pageSize = data?.pageSize == null ? 25 : data.pageSize;
  if (!PLATFORM_ACTIVITY_PAGE_SIZES.has(pageSize)) {
    throw new HttpsError("invalid-argument", "Timeline page size must be 25, 50, or 100.");
  }
  const signature = cursorSignature({ instituteId, pageSize, type: "timeline" });
  const cursor = parseOpaqueCursor(data?.cursor, signature, "timeline");
  if (cursor && (!Number.isSafeInteger(cursor.occurredAtMs) || cursor.occurredAtMs < 0 ||
      typeof cursor.id !== "string" || !cursor.id || cursor.id.length > 256)) {
    throw new HttpsError("invalid-argument", "This timeline cursor is no longer valid. Refresh and try again.");
  }
  return { instituteId, pageSize, signature, cursor };
}

async function queryInstituteTimeline({ db, request }) {
  const timeline = normalizeTimelineRequest(request.data);
  await assertActiveInstitute(db, timeline.instituteId);
  let query = db.collection("institutes").doc(timeline.instituteId)
    .collection("platform_activity_events")
    .orderBy("occurredAtMs", "desc")
    .orderBy(FieldPath.documentId(), "desc")
    .limit(timeline.pageSize + 1);
  if (timeline.cursor) query = query.startAfter(timeline.cursor.occurredAtMs, timeline.cursor.id);
  const page = await query.get();
  const documents = page.docs.slice(0, timeline.pageSize);
  const hasMore = page.docs.length > timeline.pageSize;
  const last = documents.at(-1);
  return {
    events: documents.map((document) => publicPlatformActivity(document.id, document.data() || {})),
    pageSize: timeline.pageSize,
    hasMore,
    nextCursor: hasMore && last
      ? opaqueCursor({
        v: 1,
        signature: timeline.signature,
        occurredAtMs: safeMillis(last.get("occurredAtMs"), 0),
        id: last.id,
      }) : "",
  };
}

function noteContainsCredentialMaterial(value) {
  // We intentionally reject common credential labels instead of attempting to
  // redact an unknown secret after it has reached the server.
  return /\b(password|passcode|one[ -]?time[ -]?code|otp|access[ -]?token|auth[ -]?token|security[ -]?pin)\b/i.test(value);
}

function normalizeClientNoteRequest(data) {
  const instituteId = requiredInstituteId(data);
  const title = optionalString(data, "title", 120);
  const body = requiredString(data, "body", 2_000);
  if (body.length < 3) throw new HttpsError("invalid-argument", "Client note is too short.");
  if (noteContainsCredentialMaterial(`${title}\n${body}`)) {
    throw new HttpsError("invalid-argument", "Do not place passwords, OTPs, PINs, or tokens in client notes.");
  }
  const status = optionalString(data, "status", 32).toLowerCase() || "open";
  if (!CLIENT_NOTE_STATUSES.has(status)) throw new HttpsError("invalid-argument", "Invalid client note status.");
  const followUpAtMs = data?.followUpAtMs == null ? 0 : data.followUpAtMs;
  if (!Number.isSafeInteger(followUpAtMs) || followUpAtMs < 0 || followUpAtMs > 4_102_444_800_000) {
    throw new HttpsError("invalid-argument", "Invalid follow-up date.");
  }
  return { instituteId, title, body, status, followUpAtMs };
}

function publicClientNote(id, data) {
  return {
    noteId: id,
    title: typeof data.title === "string" ? data.title : "",
    body: typeof data.body === "string" ? data.body : "",
    status: CLIENT_NOTE_STATUSES.has(data.status) ? data.status : "open",
    followUpAtMs: safeMillis(data.followUpAtMs, 0),
    createdAtMs: safeMillis(data.createdAtMs, 0),
    createdByName: typeof data.createdByName === "string" ? data.createdByName : "Platform team",
    createdByRole: typeof data.createdByRole === "string" ? data.createdByRole : "platform",
  };
}

async function createClientNote({ db, request, actor, operationId, requestHash, now }) {
  const note = normalizeClientNoteRequest(request.data);
  await assertActiveInstitute(db, note.instituteId);
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const instituteRef = db.collection("institutes").doc(note.instituteId);
  const noteRef = instituteRef.collection("client_notes").doc(operationId);
  const actorName = typeof actor.user.name === "string" && actor.user.name.trim()
    ? actor.user.name.trim().slice(0, 120) : "Platform team";
  const result = {
    noteId: operationId,
    instituteId: note.instituteId,
    title: note.title,
    body: note.body,
    status: note.status,
    followUpAtMs: note.followUpAtMs,
    createdAtMs: now,
    createdByName: actorName,
    createdByRole: actor.role,
  };
  return db.runTransaction(async (transaction) => {
    const [existing, instituteSnap] = await Promise.all([
      transaction.get(operationRef), transaction.get(instituteRef),
    ]);
    if (existing.exists) {
      if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return existing.get("result");
    }
    if (!instituteSnap.exists || instituteSnap.get("deletionState") === "retained") {
      throw new HttpsError("not-found", "Active institute not found.");
    }
    transaction.create(noteRef, result);
    transactionActivity(transaction, db, note.instituteId, `note_${operationId}`, {
      action: "client_note_created",
      actorUid: request.auth.uid,
      actorRole: actor.role,
      targetType: "client_note",
      targetId: operationId,
      now,
      summary: note.status === "resolved" ? "Client note recorded as resolved" : "Client note recorded",
    });
    transaction.create(operationRef, {
      actorUid: request.auth.uid,
      requestHash,
      action: "create_client_note",
      result,
      createdAtMs: now,
    });
    return result;
  });
}

function normalizeClientNotesListRequest(data) {
  const instituteId = requiredInstituteId(data);
  const pageSize = data?.pageSize == null ? 25 : data.pageSize;
  if (!CLIENT_NOTE_PAGE_SIZES.has(pageSize)) throw new HttpsError("invalid-argument", "Client-note page size must be 25 or 50.");
  const signature = cursorSignature({ instituteId, pageSize, type: "client_notes" });
  const cursor = parseOpaqueCursor(data?.cursor, signature, "client-note");
  if (cursor && (!Number.isSafeInteger(cursor.createdAtMs) || cursor.createdAtMs < 0 ||
      typeof cursor.id !== "string" || !cursor.id || cursor.id.length > 256)) {
    throw new HttpsError("invalid-argument", "This client-note cursor is no longer valid. Refresh and try again.");
  }
  return { instituteId, pageSize, signature, cursor };
}

async function listClientNotes({ db, request }) {
  const values = normalizeClientNotesListRequest(request.data);
  await assertActiveInstitute(db, values.instituteId);
  let query = db.collection("institutes").doc(values.instituteId).collection("client_notes")
    .orderBy("createdAtMs", "desc")
    .orderBy(FieldPath.documentId(), "desc")
    .limit(values.pageSize + 1);
  if (values.cursor) query = query.startAfter(values.cursor.createdAtMs, values.cursor.id);
  const page = await query.get();
  const documents = page.docs.slice(0, values.pageSize);
  const hasMore = page.docs.length > values.pageSize;
  const last = documents.at(-1);
  return {
    notes: documents.map((document) => publicClientNote(document.id, document.data() || {})),
    hasMore,
    nextCursor: hasMore && last ? opaqueCursor({
      v: 1,
      signature: values.signature,
      createdAtMs: safeMillis(last.get("createdAtMs"), 0),
      id: last.id,
    }) : "",
  };
}

// Institute-owner outreach is kept separate from product feedback and tenant
// notes. Only the trusted platform callable can read or change these cases.
const SUPPORT_CASE_STATUSES = new Set(["open", "follow_up", "done"]);
const SUPPORT_CONTACT_CHANNELS = new Set(["call", "whatsapp", "sms", "other"]);
const SUPPORT_QUEUE_VIEWS = new Set(["active", "due", "upcoming", "done", "review"]);
const SUPPORT_DONE_COOLDOWN_MS = 7 * DIRECTORY_DAY_MS;

function normalizeSupportCaseUpdate(data, now) {
  const instituteId = requiredInstituteId(data);
  const status = requiredString(data, "status", 24);
  if (!SUPPORT_CASE_STATUSES.has(status)) throw new HttpsError("invalid-argument", "Invalid support status.");
  const body = requiredString(data, "body", 2_000);
  if (body.length < 3) throw new HttpsError("invalid-argument", "Write a short contact note.");
  const channel = requiredString(data, "channel", 24);
  if (!SUPPORT_CONTACT_CHANNELS.has(channel)) throw new HttpsError("invalid-argument", "Invalid contact channel.");
  const followUpAtMs = data?.followUpAtMs == null ? 0 : data.followUpAtMs;
  if (!Number.isSafeInteger(followUpAtMs) || followUpAtMs < 0 || followUpAtMs > now + 366 * DIRECTORY_DAY_MS ||
      (status === "follow_up" && followUpAtMs <= now) || (status !== "follow_up" && followUpAtMs !== 0)) {
    throw new HttpsError("invalid-argument", "Choose a future follow-up date within one year.");
  }
  if (noteContainsCredentialMaterial(body)) {
    throw new HttpsError("invalid-argument", "Do not place passwords, OTPs, PINs, or tokens in support notes.");
  }
  return { instituteId, status, body, channel, followUpAtMs };
}

function publicSupportCase(institute, risk, record, now) {
  const status = SUPPORT_CASE_STATUSES.has(record.status) ? record.status : "open";
  return {
    instituteId: institute.instituteId,
    instituteName: institute.instituteName,
    ownerName: institute.ownerName || "",
    phone: institute.phone || "",
    whatsappNumber: institute.whatsappNumber || institute.phone || "",
    subscriptionStatus: institute.subscriptionStatus || "",
    risk: risk?.risk || "low",
    reasons: risk?.reasons || [],
    status,
    followUpAtMs: safeMillis(record.followUpAtMs),
    hiddenUntilMs: safeMillis(record.hiddenUntilMs),
    lastContactAtMs: safeMillis(record.lastContactAtMs),
    lastChannel: typeof record.lastChannel === "string" ? record.lastChannel : "",
    lastNote: typeof record.lastNote === "string" ? record.lastNote.slice(0, 2_000) : "",
    lastAgentName: typeof record.lastAgentName === "string" ? record.lastAgentName : "",
    assignedToName: typeof record.assignedToName === "string" ? record.assignedToName : "",
    reviewStatus: record.reviewStatus === "pending" ? "pending" : "reviewed",
    reviewedAtMs: safeMillis(record.reviewedAtMs),
    isFollowUpDue: status === "follow_up" && safeMillis(record.followUpAtMs) <= now,
  };
}

function supportQueueRows({ institutesSnap, casesSnap, now, view, actorUid = "", query = "" }) {
  const risks = churnRiskRows({
    institutesSnap, studentsAdded: new Map(), studentsLost: new Map(),
    pendingPaymentRequests: new Map(), now,
  });
  const riskById = new Map(risks.map((row) => [row.instituteId, row]));
  const casesById = new Map(casesSnap.docs.map((doc) => [doc.id, doc.data() || {}]));
  const rows = [];
  for (const doc of institutesSnap.docs) {
    const data = doc.data() || {};
    if (data.deletionState === "retained") continue;
    const risk = riskById.get(doc.id);
    const record = casesById.get(doc.id) || {};
    if (actorUid && record.assignedToUid && record.assignedToUid !== actorUid) continue;
    if (!risk && !casesById.has(doc.id)) continue;
    const institute = { ...publicDirectoryInstitute(doc.id, data, now),
      whatsappNumber: typeof data.whatsappNumber === "string" ? data.whatsappNumber : "" };
    if (query && ![doc.id, institute.instituteName, institute.ownerName, institute.phone]
      .some((value) => normalizedText(value).includes(query))) continue;
    const row = publicSupportCase(institute, risk, record, now);
    const inCooldown = row.status === "done" && row.hiddenUntilMs > now;
    const eligibleNew = !casesById.has(doc.id) && (query || (risk && risk.risk !== "low"));
    let include = false;
    if (view === "review") include = casesById.has(doc.id) && row.reviewStatus === "pending";
    else if (view === "done") include = row.status === "done";
    else if (view === "upcoming") include = row.status === "follow_up" && !row.isFollowUpDue;
    else if (view === "due") include = row.isFollowUpDue;
    else include = !inCooldown && (eligibleNew || row.status === "open" || row.isFollowUpDue ||
      (row.status === "done" && risk && risk.risk !== "low"));
    if (include) rows.push(row);
  }
  rows.sort((a, b) => Number(b.isFollowUpDue) - Number(a.isFollowUpDue)
    || BI_RISK_RANK[a.risk] - BI_RISK_RANK[b.risk]
    || a.instituteName.localeCompare(b.instituteName));
  return rows;
}

async function querySupportQueue({ db, request, actor, now }) {
  const view = optionalString(request.data, "view", 24) || "active";
  const query = normalizedText(optionalString(request.data, "query", 120));
  if (!SUPPORT_QUEUE_VIEWS.has(view) || (view === "review" && actor.role !== "root")) {
    throw new HttpsError("permission-denied", "This support view is unavailable.");
  }
  const [institutesSnap, casesSnap] = await Promise.all([
    db.collection("institutes").get(), db.collection("platform_support_cases").get(),
  ]);
  const rows = supportQueueRows({ institutesSnap, casesSnap, now, view,
    actorUid: actor.role === "support" ? request.auth.uid : "", query });
  return { cases: rows.slice(0, 200), total: rows.length, view, snapshotAtMs: now };
}

async function updateSupportCase({ db, request, actor, operationId, requestHash, now }) {
  const values = normalizeSupportCaseUpdate(request.data, now);
  const instituteRef = db.collection("institutes").doc(values.instituteId);
  const caseRef = db.collection("platform_support_cases").doc(values.instituteId);
  const noteRef = caseRef.collection("notes").doc(operationId);
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const actorName = typeof actor.user.name === "string" && actor.user.name.trim()
    ? actor.user.name.trim().slice(0, 120) : "Support team";
  return db.runTransaction(async (transaction) => {
    const [existing, instituteSnap, caseSnap] = await Promise.all([
      transaction.get(operationRef), transaction.get(instituteRef), transaction.get(caseRef),
    ]);
    if (existing.exists) {
      if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return existing.get("result");
    }
    if (!instituteSnap.exists || instituteSnap.get("deletionState") === "retained") {
      throw new HttpsError("not-found", "Active institute not found.");
    }
    const previous = caseSnap.exists ? caseSnap.data() || {} : {};
    if (actor.role === "support" && previous.assignedToUid && previous.assignedToUid !== request.auth.uid) {
      throw new HttpsError("permission-denied", "This case is assigned to another support member.");
    }
    const next = {
      instituteId: values.instituteId,
      status: values.status,
      followUpAtMs: values.followUpAtMs,
      hiddenUntilMs: values.status === "done" ? now + SUPPORT_DONE_COOLDOWN_MS : 0,
      lastContactAtMs: now,
      lastChannel: values.channel,
      lastNote: values.body,
      lastAgentUid: request.auth.uid,
      lastAgentName: actorName,
      assignedToUid: previous.assignedToUid || request.auth.uid,
      assignedToName: previous.assignedToName || actorName,
      reviewStatus: "pending",
      reviewedAtMs: 0,
      createdAtMs: safeMillis(previous.createdAtMs, now),
      updatedAtMs: now,
    };
    transaction.set(caseRef, next);
    transaction.create(noteRef, {
      body: values.body, channel: values.channel, status: values.status,
      followUpAtMs: values.followUpAtMs, actorUid: request.auth.uid,
      actorName, actorRole: actor.role, createdAtMs: now,
    });
    transactionActivity(transaction, db, values.instituteId, `support_${operationId}`, {
      action: "support_case_updated", actorUid: request.auth.uid, actorRole: actor.role,
      targetType: "support_case", targetId: values.instituteId, now,
      summary: `Support case marked ${values.status.replace("_", " ")}`,
    });
    const result = publicSupportCase({
      ...publicDirectoryInstitute(values.instituteId, instituteSnap.data() || {}, now),
      whatsappNumber: instituteSnap.get("whatsappNumber") || "",
    }, null, next, now);
    transaction.create(operationRef, {
      actorUid: request.auth.uid, requestHash, action: "update_support_case", result, createdAtMs: now,
    });
    return result;
  });
}

async function reviewSupportCase({ db, request, actor, operationId, requestHash, now }) {
  const instituteId = requiredInstituteId(request.data);
  const decision = requiredString(request.data, "decision", 24);
  if (!new Set(["reviewed", "reopen"]).has(decision)) {
    throw new HttpsError("invalid-argument", "Invalid support review decision.");
  }
  const note = optionalString(request.data, "note", 500);
  if (noteContainsCredentialMaterial(note)) throw new HttpsError("invalid-argument", "Do not include credentials in review notes.");
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const caseRef = db.collection("platform_support_cases").doc(instituteId);
  return db.runTransaction(async (transaction) => {
    const [existing, caseSnap] = await Promise.all([transaction.get(operationRef), transaction.get(caseRef)]);
    if (existing.exists) {
      if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return existing.get("result");
    }
    if (!caseSnap.exists) throw new HttpsError("not-found", "Support case not found.");
    const next = {
      reviewStatus: "reviewed", reviewedAtMs: now, reviewedByUid: request.auth.uid,
      reviewDecision: decision, reviewNote: note, updatedAtMs: now,
    };
    if (decision === "reopen") {
      next.status = "open";
      next.hiddenUntilMs = 0;
      next.followUpAtMs = 0;
      next.assignedToUid = "";
      next.assignedToName = "";
    }
    transaction.update(caseRef, next);
    transaction.create(caseRef.collection("notes").doc(operationId), {
      body: note || (decision === "reopen" ? "Super Admin requested follow-up" : "Super Admin reviewed feedback"),
      channel: "internal", status: decision, actorUid: request.auth.uid,
      actorName: typeof actor.user.name === "string" ? actor.user.name.slice(0, 120) : "Super Admin",
      actorRole: actor.role, createdAtMs: now,
    });
    transactionActivity(transaction, db, instituteId, `support_review_${operationId}`, {
      action: "support_case_reviewed", actorUid: request.auth.uid, actorRole: actor.role,
      targetType: "support_case", targetId: instituteId, now,
      summary: decision === "reopen" ? "Super Admin reopened support case" : "Super Admin reviewed support feedback",
    });
    const result = { instituteId, decision, reviewedAtMs: now };
    transaction.create(operationRef, {
      actorUid: request.auth.uid, requestHash, action: "review_support_case", result, createdAtMs: now,
    });
    return result;
  });
}

async function listSupportCaseNotes({ db, request, actor }) {
  const instituteId = requiredInstituteId(request.data);
  await assertActiveInstitute(db, instituteId);
  const caseSnap = await db.collection("platform_support_cases").doc(instituteId).get();
  if (actor.role === "support" && caseSnap.exists && caseSnap.get("assignedToUid") &&
      caseSnap.get("assignedToUid") !== request.auth.uid) {
    throw new HttpsError("permission-denied", "This case is assigned to another support member.");
  }
  const page = await db.collection("platform_support_cases").doc(instituteId)
    .collection("notes").orderBy("createdAtMs", "desc").limit(50).get();
  return { notes: page.docs.map((doc) => {
    const note = doc.data() || {};
    return {
      noteId: doc.id,
      body: typeof note.body === "string" ? note.body : "",
      channel: typeof note.channel === "string" ? note.channel : "",
      status: typeof note.status === "string" ? note.status : "",
      actorName: typeof note.actorName === "string" ? note.actorName : "",
      createdAtMs: safeMillis(note.createdAtMs),
      followUpAtMs: safeMillis(note.followUpAtMs),
    };
  }) };
}

function studentState(data) {
  if (data.archivedAtMs != null || data.deletionState === "retained" || data.status === "archived") return "archived";
  return data.status === "inactive" || data.status === "close" || data.status === "closed" ? "inactive" : "active";
}

function matchesStudentSupportSearch(data, documentId, normalizedQuery) {
  const searchable = [documentId, data.studentCode, data.studentCodeNormalized, data.fullName]
    .map(normalizedText).join(" ");
  if (searchable.includes(normalizedQuery)) return true;
  const phoneQuery = normalizedPhone(normalizedQuery);
  return phoneQuery.length >= 3 && normalizedPhone(data.phone).includes(phoneQuery);
}

async function activeStudentBatchNames(db, instituteId, studentId, maximum = 2) {
  const enrollments = await db.collection("institutes").doc(instituteId).collection("batch_students")
    .where("studentId", "==", studentId).limit(20).get();
  const activeIds = enrollments.docs
    .filter((document) => document.get("status") === "active")
    .map((document) => document.get("batchId"))
    .filter((batchId) => typeof batchId === "string" && batchId)
    .slice(0, maximum);
  const batches = await Promise.all(activeIds.map((batchId) => db.collection("institutes").doc(instituteId)
    .collection("batches").doc(batchId).get()));
  return batches.map((batch) => typeof batch.get("name") === "string" ? batch.get("name") : "")
    .filter(Boolean);
}

function normalizeStudentSupportRequest(data) {
  const query = normalizedText(requiredString(data, "query", 120));
  if (query.length < 3 && normalizedPhone(query).length < 3) {
    throw new HttpsError("invalid-argument", "Enter at least 3 characters of a student name, ID, or phone number.");
  }
  const instituteId = optionalString(data, "instituteId", 128);
  if (instituteId.includes("/")) throw new HttpsError("invalid-argument", "Invalid institute ID.");
  const pageSize = data?.pageSize == null ? 25 : data.pageSize;
  if (!STUDENT_SUPPORT_PAGE_SIZES.has(pageSize)) throw new HttpsError("invalid-argument", "Student-support page size must be 25 or 50.");
  const signature = cursorSignature({ query, instituteId, pageSize, type: "student_support" });
  const cursor = parseOpaqueCursor(data?.cursor, signature, "student-support");
  if (cursor && (typeof cursor.documentPath !== "string" || !/^institutes\/[^/]+\/students\/[^/]+$/.test(cursor.documentPath))) {
    throw new HttpsError("invalid-argument", "This student-support cursor is no longer valid. Refresh and try again.");
  }
  return { query, instituteId, pageSize, signature, cursor };
}

async function queryStudentSupport({ db, request }) {
  const lookup = normalizeStudentSupportRequest(request.data);
  const rows = [];
  const instituteCache = new Map();
  let scanned = 0;
  let cursorPath = lookup.cursor?.documentPath || "";
  let hasMore = false;
  while (scanned < STUDENT_SUPPORT_MAX_SCANNED_DOCUMENTS && rows.length < lookup.pageSize) {
    let query = db.collectionGroup("students").orderBy(FieldPath.documentId())
      .limit(STUDENT_SUPPORT_SCAN_CHUNK_SIZE + 1);
    if (cursorPath) query = query.startAfter(cursorPath);
    const page = await query.get();
    if (page.empty) break;
    const hasDocumentAfterChunk = page.docs.length > STUDENT_SUPPORT_SCAN_CHUNK_SIZE;
    const documents = page.docs.slice(0, STUDENT_SUPPORT_SCAN_CHUNK_SIZE);
    for (let index = 0; index < documents.length; index += 1) {
      const document = documents[index];
      const data = document.data() || {};
      cursorPath = document.ref.path;
      scanned += 1;
      const instituteId = typeof data.instituteId === "string" ? data.instituteId : "";
      const documentInstituteId = document.ref.parent.parent?.id || "";
      if (instituteId && instituteId === documentInstituteId &&
          (!lookup.instituteId || lookup.instituteId === instituteId) &&
          studentState(data) !== "archived" && matchesStudentSupportSearch(data, document.id, lookup.query)) {
        let institute = instituteCache.get(instituteId);
        if (institute === undefined) {
          const instituteSnap = await db.collection("institutes").doc(instituteId).get();
          institute = instituteSnap.exists && instituteSnap.get("deletionState") !== "retained"
            ? instituteSnap.data() : null;
          instituteCache.set(instituteId, institute);
        }
        if (institute) {
          const batchNames = await activeStudentBatchNames(db, instituteId, document.id, 1);
          rows.push({
            instituteId,
            instituteName: typeof institute.instituteName === "string" ? institute.instituteName : "Institute",
            studentId: document.id,
            studentCode: typeof data.studentCode === "string" ? data.studentCode : "",
            fullName: typeof data.fullName === "string" ? data.fullName : "Student",
            status: studentState(data),
            batchName: batchNames[0] || "No active batch",
          });
        }
      }
      if (rows.length === lookup.pageSize || scanned >= STUDENT_SUPPORT_MAX_SCANNED_DOCUMENTS) {
        hasMore = index < documents.length - 1 || hasDocumentAfterChunk;
        break;
      }
    }
    if (rows.length === lookup.pageSize || scanned >= STUDENT_SUPPORT_MAX_SCANNED_DOCUMENTS) break;
    if (!hasDocumentAfterChunk) break;
    hasMore = true;
  }
  return {
    results: rows,
    scannedDocuments: scanned,
    hasMore,
    nextCursor: hasMore && cursorPath ? opaqueCursor({
      v: 1, signature: lookup.signature, documentPath: cursorPath,
    }) : "",
  };
}

async function getStudentSupportDetails({ db, request, actor, operationId, requestHash, now }) {
  const instituteId = requiredInstituteId(request.data);
  const studentId = requiredString(request.data, "studentId", 128);
  if (studentId.includes("/")) throw new HttpsError("invalid-argument", "Invalid student ID.");
  const reason = requiredString(request.data, "reason", 300);
  if (reason.length < 10) throw new HttpsError("invalid-argument", "A support reason of at least 10 characters is required.");
  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const replay = await operationRef.get();
  if (replay.exists) {
    if (replay.get("requestHash") !== requestHash || replay.get("actorUid") !== request.auth.uid) {
      throw new HttpsError("already-exists", "Operation ID was already used for another request.");
    }
    return replay.get("result");
  }
  const [instituteSnap, studentSnap] = await Promise.all([
    assertActiveInstitute(db, instituteId),
    db.collection("institutes").doc(instituteId).collection("students").doc(studentId).get(),
  ]);
  if (!studentSnap.exists || studentState(studentSnap.data() || {}) === "archived") {
    throw new HttpsError("not-found", "Active student not found.");
  }
  const data = studentSnap.data() || {};
  const batchNames = await activeStudentBatchNames(db, instituteId, studentId, 10);
  const details = {
    instituteId,
    instituteName: typeof instituteSnap.get("instituteName") === "string" ? instituteSnap.get("instituteName") : "Institute",
    studentId,
    studentCode: typeof data.studentCode === "string" ? data.studentCode : "",
    fullName: typeof data.fullName === "string" ? data.fullName : "Student",
    status: studentState(data),
    phone: typeof data.phone === "string" ? data.phone : "",
    className: typeof data.className === "string" ? data.className : "",
    admissionDateMs: safeMillis(data.admissionDateMs, 0),
    batchNames,
  };
  return db.runTransaction(async (transaction) => {
    const existing = await transaction.get(operationRef);
    if (existing.exists) {
      if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return existing.get("result");
    }
    transactionActivity(transaction, db, instituteId, `student_detail_${operationId}`, {
      action: "student_support_details_viewed",
      actorUid: request.auth.uid,
      actorRole: actor.role,
      targetType: "student",
      targetId: studentId,
      now,
      summary: "Student support details viewed",
      supportReason: reason,
    });
    transaction.create(operationRef, {
      actorUid: request.auth.uid,
      requestHash,
      action: "get_student_support_details",
      result: details,
      createdAtMs: now,
    });
    return details;
  });
}

async function transferOwner({ db, adminAuth, request, actor, operationId, requestHash, now }) {
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
      assertCanAssignInstituteOwner(newOwnerSnap.exists ? newOwnerSnap.data() : null, instituteId);
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
      transactionActivity(transaction, db, instituteId, `owner_${operationId}`, {
        action: "owner_transferred",
        actorUid: request.auth.uid,
        actorRole: actor.role,
        targetType: "owner",
        targetId: owner.user.uid,
        now,
        summary: "Institute ownership transferred",
        supportReason: reason,
      });
      transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "transfer_owner", result, createdAtMs: now });
    });
  } catch (error) {
    if (owner.created) await adminAuth.deleteUser(owner.user.uid).catch(() => { });
    throw error;
  }
  let recoveryLink = "";
  try { recoveryLink = await adminAuth.generatePasswordResetLink(email); } catch (_) { }
  return { ...result, recoveryLink };
}

async function sendOwnerRecovery({ db, adminAuth, request, actor, operationId, requestHash, now }) {
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
    transactionActivity(transaction, db, instituteId, `recovery_${operationId}`, {
      action: "owner_recovery_requested",
      actorUid: request.auth.uid,
      actorRole: actor.role,
      targetType: "owner",
      targetId: instituteId,
      now,
      summary: "Owner recovery link requested",
      supportReason: reason,
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
      const [existing, existingUser] = await Promise.all([
        transaction.get(operationRef),
        transaction.get(db.collection("app_users").doc(owner.user.uid)),
      ]);
      if (existing.exists) {
        if (existing.get("requestHash") !== requestHash || existing.get("actorUid") !== request.auth.uid) {
          throw new HttpsError("already-exists", "Operation ID was already used for another request.");
        }
        return;
      }
      const existingData = existingUser.exists ? existingUser.data() : null;
      if (isManageablePlatformMember(existingData)) {
        throw new HttpsError(
          "already-exists",
          "This platform member already exists. Change its role or status from Team Members.",
        );
      }
      assertCanAssignPlatformRole(existingData);
      transaction.set(db.collection("app_users").doc(owner.user.uid), {
        name,
        email,
        role: "PlatformAdmin",
        platformRole,
        instituteId: null,
        status: "active",
        createdAtMs: now,
        updatedAtMs: now,
      }, { merge: true });
      transaction.create(db.collection("platform_audit").doc(operationId), {
        operationId, action: "manage_platform_admin", actorUid: request.auth.uid, createdAtMs: now,
        details: { managedUserUid: owner.user.uid, email, platformRole, status: "active" },
      });
      transaction.create(operationRef, { actorUid: request.auth.uid, requestHash, action: "manage_platform_admin", result, createdAtMs: now });
    });
  } catch (error) {
    if (owner.created) await adminAuth.deleteUser(owner.user.uid).catch(() => { });
    throw error;
  }
  return { ...result, recoveryLink: await adminAuth.generatePasswordResetLink(email) };
}

async function listPlatformAdmins({ db }) {
  const snapshot = await db.collection("app_users")
    .where("role", "==", "PlatformAdmin")
    .limit(250)
    .get();
  return {
    members: snapshot.docs
      .map((doc) => isManageablePlatformMember(doc.data()) ? publicPlatformMember(doc.id, doc.data()) : null)
      .filter(Boolean)
      .sort((left, right) => left.name.localeCompare(right.name) || left.email.localeCompare(right.email)),
  };
}

async function updatePlatformAdmin({ db, request, operationId, requestHash, now }) {
  const targetUserId = requiredString(request.data, "targetUserId", 128);
  const reason = requiredString(request.data, "reason", 500);
  if (reason.length < 3) {
    throw new HttpsError("invalid-argument", "A reason is required for a platform access change.");
  }
  const requestedRole = optionalString(request.data, "platformRole", 32).toLowerCase();
  const requestedStatus = optionalString(request.data, "status", 32).toLowerCase();
  if (!requestedRole && !requestedStatus) {
    throw new HttpsError("invalid-argument", "Choose a new platform role or account status.");
  }
  if (requestedRole && !NON_ROOT_PLATFORM_ROLES.has(requestedRole)) {
    throw new HttpsError("invalid-argument", "Root access cannot be assigned from Team Members.");
  }
  if (requestedStatus && !PLATFORM_MEMBER_STATUSES.has(requestedStatus)) {
    throw new HttpsError("invalid-argument", "Invalid platform account status.");
  }

  const operationRef = db.collection("platform_admin_operations").doc(operationId);
  const memberRef = db.collection("app_users").doc(targetUserId);
  return db.runTransaction(async (transaction) => {
    const [operationSnap, memberSnap] = await Promise.all([
      transaction.get(operationRef),
      transaction.get(memberRef),
    ]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== request.auth.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return operationSnap.get("result");
    }
    if (!memberSnap.exists || !isManageablePlatformMember(memberSnap.data())) {
      throw new HttpsError("not-found", "Only a non-root platform member can be changed here.");
    }
    const existing = memberSnap.data();
    const nextRole = requestedRole || existing.platformRole;
    const nextStatus = requestedStatus || (existing.status === "suspended" ? "suspended" : "active");
    if (nextRole === existing.platformRole && nextStatus === (existing.status === "suspended" ? "suspended" : "active")) {
      throw new HttpsError("failed-precondition", "This platform member already has that access.");
    }
    const next = { ...existing, platformRole: nextRole, status: nextStatus, updatedAtMs: now };
    const result = publicPlatformMember(targetUserId, next);
    transaction.update(memberRef, {
      platformRole: nextRole,
      status: nextStatus,
      updatedAtMs: now,
    });
    transaction.create(db.collection("platform_audit").doc(operationId), {
      operationId,
      action: "update_platform_admin",
      actorUid: request.auth.uid,
      createdAtMs: now,
      details: {
        managedUserUid: targetUserId,
        previousRole: existing.platformRole,
        nextRole,
        previousStatus: existing.status === "suspended" ? "suspended" : "active",
        nextStatus,
        reason,
      },
    });
    transaction.create(operationRef, {
      actorUid: request.auth.uid,
      requestHash,
      action: "update_platform_admin",
      result,
      createdAtMs: now,
    });
    return result;
  });
}

function createPlatformAdminHandler({ db, adminAuth, smsProvider = null }) {
  return async (request) => {
    const action = requiredString(request.data, "action", 64);
    const operationId = requiredString(request.data, "operationId", 128);
    if (!ACTIONS.has(action) || !/^[A-Za-z0-9_-]{16,128}$/.test(operationId)) {
      throw new HttpsError("invalid-argument", "Invalid platform administration operation.");
    }
    const actor = await assertPermission(db, request.auth, action);
    const hash = operationHash(request.data || {});
    const now = Date.now();
    if (action === "create_institute") return createInstitute({ db, adminAuth, request, actor, operationId, requestHash: hash, now });
    if (action === "preview_institute_import") {
      return { rows: await previewImport({ db, rows: request.data.rows }) };
    }
    if (action === "get_platform_dashboard") return dashboardMetrics(db);
    if (action === "get_business_intelligence") return businessIntelligence({ db, smsProvider, now });
    if (action === "query_institute_directory") return queryInstituteDirectory({ db, request, now });
    if (action === "query_institute_timeline") return queryInstituteTimeline({ db, request });
    if (action === "query_student_support") return queryStudentSupport({ db, request });
    if (action === "get_student_support_details") return getStudentSupportDetails({ db, request, actor, operationId, requestHash: hash, now });
    if (action === "list_client_notes") return listClientNotes({ db, request });
    if (action === "create_client_note") return createClientNote({ db, request, actor, operationId, requestHash: hash, now });
    if (action === "query_support_queue") return querySupportQueue({ db, request, actor, now });
    if (action === "update_support_case") return updateSupportCase({ db, request, actor, operationId, requestHash: hash, now });
    if (action === "review_support_case") return reviewSupportCase({ db, request, actor, operationId, requestHash: hash, now });
    if (action === "list_support_case_notes") return listSupportCaseNotes({ db, request, actor });
    if (action === "list_platform_admins") return listPlatformAdmins({ db });
    if (action === "transfer_owner") return transferOwner({ db, adminAuth, request, actor, operationId, requestHash: hash, now });
    if (action === "send_owner_recovery") return sendOwnerRecovery({ db, adminAuth, request, actor, operationId, requestHash: hash, now });
    if (action === "update_platform_admin") return updatePlatformAdmin({ db, request, operationId, requestHash: hash, now });
    return managePlatformAdmin({ db, adminAuth, request, operationId, requestHash: hash, now });
  };
}

module.exports = {
  ACTIONS,
  PERMISSIONS,
  PLATFORM_ROLES,
  NON_ROOT_PLATFORM_ROLES,
  PLATFORM_MEMBER_STATUSES,
  assertCanAssignInstituteOwner,
  assertCanAssignPlatformRole,
  CLIENT_NOTE_STATUSES,
  createPlatformAdminHandler,
  businessIntelligence,
  churnRiskRows,
  matchesStudentSupportSearch,
  isManageablePlatformMember,
  matchesDirectoryFilters,
  normalizeClientNoteRequest,
  normalizeSupportCaseUpdate,
  supportQueueRows,
  normalizeDirectoryRequest,
  normalizeStudentSupportRequest,
  normalizeTimelineRequest,
  platformRoleFor,
  publicClientNote,
  publicDirectoryInstitute,
  publicPlatformActivity,
  publicPlatformMember,
  studentState,
};
