"use strict";

// Multi-tenant SMS wallet fields live on the institute document and are
// server-authoritative. A client can read its own wallet and choose a sending
// method through this callable, but can never forge a balance or counter.
// Recharge requests follow the same trusted pattern as subscription requests:
// the owner submits a package request with a manual Nagad/BKash payment, a
// Root/Billing platform account reviews it, and only the trusted approval
// credits the institute wallet.

const { HttpsError } = require("firebase-functions/v2/https");
const { createHash } = require("node:crypto");

const SMS_WALLET_ACTIONS = new Set([
  "get_wallet",
  "set_send_method",
  "list_packages",
  "submit_recharge_request",
  "list_my_recharge_requests",
  "list_recharge_requests",
  "review_recharge_request",
  "sms_accounting",
  "record_sms_batch",
  "list_sms_report",
]);
const SMS_SEND_METHODS = new Set(["carrier", "server"]);
const SMS_PAYMENT_METHODS = new Set(["bkash", "nagad"]);
const SMS_REVIEW_DECISIONS = new Set(["approve", "reject"]);
const SMS_MESSAGE_STATUSES = new Set(["pending", "sent", "delivered", "failed"]);
const SMS_MESSAGE_CHANNELS = new Set(["carrier", "server"]);
const PLATFORM_ROLES = new Set(["root", "billing", "support", "operations", "read_only"]);

const WALLET_FIELDS = {
  sms_balance: 0,
  total_sms_purchased: 0,
  total_sms_used: 0,
  sms_send_method: "carrier",
};

// Manual payment charge added on top of the package base amount.
const SMS_RECHARGE_CHARGE_PERCENT = 1.8;

// SMS_PACKAGES is the single authoritative price list. Clients never send
// amounts; they send a package ID and the server quotes the price again.
const SMS_PACKAGES = [
  { id: "starter", layer: "Small & Medium Batches", name: "Starter", baseAmount: 100, smsCount: 275 },
  { id: "basic", layer: "Small & Medium Batches", name: "Basic", baseAmount: 200, smsCount: 560 },
  { id: "standard", layer: "Small & Medium Batches", name: "Standard", baseAmount: 500, smsCount: 1450 },
  { id: "pro", layer: "Large Coaching Centers", name: "Pro", baseAmount: 1000, smsCount: 3000 },
  { id: "premium", layer: "Large Coaching Centers", name: "Premium", baseAmount: 2000, smsCount: 6250 },
  { id: "advanced", layer: "Mega Coaching & Schools", name: "Advanced", baseAmount: 5000, smsCount: 16500 },
  { id: "enterprise", layer: "Mega Coaching & Schools", name: "Enterprise", baseAmount: 10000, smsCount: 35000 },
];

// What BatchFee itself pays the SMS gateway per message. Update this to the
// real gateway rate; it is only used for the platform profit summary.
const SMS_UNIT_COST_PAISA = 20;

const MAX_MY_REQUESTS = 20;
const MAX_PLATFORM_REQUESTS = 100;
const MAX_SMS_BATCH = 400;
const MAX_SERVER_SMS_BATCH = 100;
const MAX_SMS_REPORT = 50;

function requiredString(data, field, maxLength = 128) {
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

function validOperationId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{16,128}$/.test(value);
}

function normalisedIdentifier(value, maxLength = 128) {
  return typeof value === "string" && value.trim().length > 0 && value.trim().length <= maxLength
    ? value.trim() : "";
}

function safeMillis(value, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? Math.floor(numeric) : fallback;
}

function walletDefaults(data) {
  const out = {};
  out.sms_balance = Number.isInteger(data.sms_balance) && data.sms_balance >= 0 ? data.sms_balance : 0;
  out.total_sms_purchased =
    Number.isInteger(data.total_sms_purchased) && data.total_sms_purchased >= 0 ? data.total_sms_purchased : 0;
  out.total_sms_used = Number.isInteger(data.total_sms_used) && data.total_sms_used >= 0 ? data.total_sms_used : 0;
  out.sms_send_method = SMS_SEND_METHODS.has(data.sms_send_method) ? data.sms_send_method : "carrier";
  return out;
}

function walletDto(data) {
  const wallet = walletDefaults(data);
  return {
    smsBalance: wallet.sms_balance,
    totalSmsPurchased: wallet.total_sms_purchased,
    totalSmsUsed: wallet.total_sms_used,
    smsSendMethod: wallet.sms_send_method,
  };
}

function packageById(packageId) {
  return SMS_PACKAGES.find((pkg) => pkg.id === packageId) || null;
}

function rechargeQuote(pkg) {
  const chargeAmount = Math.round(pkg.baseAmount * (SMS_RECHARGE_CHARGE_PERCENT / 100));
  return {
    packageId: pkg.id,
    layer: pkg.layer,
    name: pkg.name,
    baseAmount: pkg.baseAmount,
    chargePercent: SMS_RECHARGE_CHARGE_PERCENT,
    chargeAmount,
    payableAmount: pkg.baseAmount + chargeAmount,
    smsCount: pkg.smsCount,
  };
}

function publicPackage(pkg) {
  return rechargeQuote(pkg);
}

function publicRechargeRequest(id, data) {
  return {
    requestId: id,
    status: typeof data.status === "string" ? data.status : "pending",
    packageId: typeof data.packageId === "string" ? data.packageId : "",
    packageName: typeof data.packageName === "string" ? data.packageName : "",
    layer: typeof data.layer === "string" ? data.layer : "",
    smsCount: Number.isInteger(data.smsCount) ? data.smsCount : 0,
    baseAmount: Number.isFinite(data.baseAmount) ? data.baseAmount : 0,
    chargeAmount: Number.isFinite(data.chargeAmount) ? data.chargeAmount : 0,
    payableAmount: Number.isFinite(data.payableAmount) ? data.payableAmount : 0,
    paymentMethod: typeof data.paymentMethod === "string" ? data.paymentMethod : "",
    senderPhone: typeof data.senderPhone === "string" ? data.senderPhone : "",
    instituteId: typeof data.instituteId === "string" ? data.instituteId : "",
    createdAtMs: safeMillis(data.createdAtMs),
    reviewedAtMs: safeMillis(data.reviewedAtMs),
    reviewerNote: typeof data.reviewerNote === "string" ? data.reviewerNote : "",
  };
}

async function authenticatedUser(db, auth) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const snap = await db.collection("app_users").doc(auth.uid).get();
  const user = snap.exists ? snap.data() || {} : {};
  if (user.status === "suspended") throw new HttpsError("permission-denied", "This account is inactive.");
  return { uid: auth.uid, user };
}

function platformRoleFor(user) {
  if (!user || user.status === "suspended") return null;
  if (["SuperAdmin", "superAdmin", "super_admin"].includes(user.role) && !user.platformRole) return "root";
  return typeof user.platformRole === "string" && PLATFORM_ROLES.has(user.platformRole)
    ? user.platformRole : null;
}

async function assertPlatformRole(db, auth, allowedRoles) {
  const actor = await authenticatedUser(db, auth);
  const role = platformRoleFor(actor.user);
  if (!role || !allowedRoles.includes(role)) {
    throw new HttpsError("permission-denied", "Platform access is required for this operation.");
  }
  return { ...actor, platformRole: role };
}

function normalizedTenantRole(user) {
  const role = typeof user.role === "string" ? user.role : "";
  if (["InstituteOwner", "owner", "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(role)) return "owner";
  if (role === "Staff") return "staff";
  return "";
}

async function resolveTenantActor(db, auth) {
  const actor = await authenticatedUser(db, auth);
  const role = normalizedTenantRole(actor.user);
  if (!role) throw new HttpsError("permission-denied", "This account cannot manage SMS settings.");

  let instituteId = normalisedIdentifier(actor.user.instituteId);
  let instituteSnap = instituteId ? await db.collection("institutes").doc(instituteId).get() : null;
  if (role === "owner" && (!instituteSnap || !instituteSnap.exists)) {
    // Older owner accounts may predate app_users.instituteId. Retain the
    // inexpensive legacy document-id convention, then look up ownerUid.
    const directOwner = await db.collection("institutes").doc(actor.uid).get();
    if (directOwner.exists) {
      instituteSnap = directOwner;
      instituteId = directOwner.id;
    } else {
      const byOwner = await db.collection("institutes").where("ownerUid", "==", actor.uid).limit(2).get();
      if (byOwner.size === 1) {
        instituteSnap = byOwner.docs[0];
        instituteId = instituteSnap.id;
      }
    }
  }
  if (!instituteSnap || !instituteSnap.exists || !instituteId) {
    throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
  }
  const institute = instituteSnap.data() || {};
  if (institute.isActive === false || institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "The institute is inactive.");
  }
  if (role === "owner" && institute.ownerUid && institute.ownerUid !== actor.uid && instituteId !== actor.uid) {
    throw new HttpsError("permission-denied", "This account is not the institute owner.");
  }
  let staff = null;
  if (role === "staff") {
    const staffSnap = await db.collection("institutes").doc(instituteId).collection("staffs").doc(actor.uid).get();
    if (!staffSnap.exists || staffSnap.get("status") !== "active" || staffSnap.get("archivedAtMs") != null) {
      throw new HttpsError("permission-denied", "Active staff access is required.");
    }
    staff = staffSnap.data() || {};
  }
  return { uid: actor.uid, role, instituteId, staff };
}

function permissionSet(value) {
  if (Array.isArray(value)) return new Set(value.map(String).map((item) => item.trim()).filter(Boolean));
  if (typeof value !== "string") return new Set();
  return new Set(value.split(",").map((item) => item.trim()).filter(Boolean));
}

function assertCanSendServerSms(actor) {
  if (actor.role === "owner") return;
  if (actor.role === "staff" && permissionSet(actor.staff && actor.staff.permissions).has("send_due_message")) return;
  throw new HttpsError("permission-denied", "SMS sending permission is required.");
}

/** Conservative SMS segment accounting: ASCII uses GSM-sized limits; Bengali/Unicode uses UCS-2 limits. */
function smsCreditCount(message) {
  const text = String(message || "");
  const ascii = /^[\x00-\x7F]*$/.test(text);
  const singleLimit = ascii ? 160 : 70;
  const joinedLimit = ascii ? 153 : 67;
  if (text.length <= singleLimit) return 1;
  return Math.ceil(text.length / joinedLimit);
}

function smsPayloadHash(row) {
  return createHash("sha256").update(JSON.stringify({
    recipient: row.recipient,
    message: row.message,
    purpose: row.purpose,
    targetKey: row.targetKey,
    credits: row.credits,
  })).digest("hex");
}

/** Initializes any missing wallet field with its server-side default and returns the canonical wallet. */
async function getWallet({ db, request }) {
  const actor = await resolveTenantActor(db, request.auth);
  const ref = db.collection("institutes").doc(actor.instituteId);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
    const data = snap.data() || {};
    const patch = {};
    for (const [field, fallback] of Object.entries(WALLET_FIELDS)) {
      if (data[field] == null) patch[field] = fallback;
    }
    if (Object.keys(patch).length > 0) tx.update(ref, patch);
  });
  const snap = await ref.get();
  return walletDto(snap.data() || {});
}

/** Owner-only idempotent send-method change with an immutable audit entry. */
async function setSendMethod({ db, request, operationId, now }) {
  const actor = await resolveTenantActor(db, request.auth);
  if (actor.role !== "owner") {
    throw new HttpsError("permission-denied", "Only the institute owner can change the SMS sending method.");
  }
  const method = requiredString(request.data, "smsSendMethod", 16).toLowerCase();
  if (!SMS_SEND_METHODS.has(method)) {
    throw new HttpsError("invalid-argument", "The SMS sending method must be carrier or server.");
  }

  const docRef = db.collection("institutes").doc(actor.instituteId);
  const auditRef = docRef.collection("sms_wallet_audit").doc(operationId);

  const wallet = await db.runTransaction(async (tx) => {
    const snap = await tx.get(docRef);
    if (!snap.exists) throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
    const data = snap.data() || {};
    const current = walletDefaults(data);
    if (current.sms_send_method === method) {
      // Same-actor replay or an already-applied change: report the canonical
      // state without writing a second audit entry.
      return current;
    }
    const replay = await tx.get(auditRef);
    if (replay.exists) return current;
    tx.update(docRef, { sms_send_method: method });
    tx.set(auditRef, {
      instituteId: actor.instituteId,
      action: "set_send_method",
      before: current.sms_send_method,
      after: method,
      actorUid: actor.uid,
      createdAtMs: now,
      operationId,
    });
    return { ...current, sms_send_method: method };
  });

  return walletDto(wallet);
}

/** Returns the server-quoted package list; clients never hardcode prices. */
async function listPackages({ db, request }) {
  await resolveTenantActor(db, request.auth);
  return { chargePercent: SMS_RECHARGE_CHARGE_PERCENT, packages: SMS_PACKAGES.map(publicPackage) };
}

/** Owner-only idempotent recharge request. Request ID equals the operation ID. */
async function submitRechargeRequest({ db, request, operationId, now }) {
  const actor = await resolveTenantActor(db, request.auth);
  if (actor.role !== "owner") {
    throw new HttpsError("permission-denied", "Only the institute owner can submit an SMS recharge request.");
  }
  const packageId = requiredString(request.data, "packageId", 32).toLowerCase();
  const pkg = packageById(packageId);
  if (!pkg) throw new HttpsError("invalid-argument", "Unknown SMS package.");
  const paymentMethod = requiredString(request.data, "paymentMethod", 16).toLowerCase();
  if (!SMS_PAYMENT_METHODS.has(paymentMethod)) {
    throw new HttpsError("invalid-argument", "Payment method must be bKash or Nagad.");
  }
  const senderPhone = requiredString(request.data, "senderPhone", 24);
  const quote = rechargeQuote(pkg);

  const ref = db.collection("institutes").doc(actor.instituteId)
    .collection("sms_recharge_requests").doc(operationId);
  const existing = await ref.get();
  if (existing.exists) {
    return { request: publicRechargeRequest(operationId, existing.data() || {}) };
  }
  const payload = {
    instituteId: actor.instituteId,
    status: "pending",
    packageId: quote.packageId,
    packageName: quote.name,
    layer: quote.layer,
    smsCount: quote.smsCount,
    baseAmount: quote.baseAmount,
    chargePercent: quote.chargePercent,
    chargeAmount: quote.chargeAmount,
    payableAmount: quote.payableAmount,
    paymentMethod,
    senderPhone,
    requestedByUid: actor.uid,
    createdAtMs: now,
    reviewedBy: "",
    reviewedAtMs: 0,
    reviewerNote: "",
  };
  await ref.create(payload);
  return { request: publicRechargeRequest(operationId, payload) };
}

async function listMyRechargeRequests({ db, request }) {
  const actor = await resolveTenantActor(db, request.auth);
  const snapshot = await db.collection("institutes").doc(actor.instituteId)
    .collection("sms_recharge_requests")
    .orderBy("createdAtMs", "desc")
    .limit(MAX_MY_REQUESTS)
    .get();
  return {
    requests: snapshot.docs.map((doc) => publicRechargeRequest(doc.id, doc.data() || {})),
  };
}

async function listRechargeRequests({ db, request }) {
  await assertPlatformRole(db, request.auth, ["root", "billing"]);
  const snapshot = await db.collectionGroup("sms_recharge_requests")
    .orderBy("createdAtMs", "desc")
    .limit(MAX_PLATFORM_REQUESTS)
    .get();
  return {
    requests: snapshot.docs.map((doc) => publicRechargeRequest(doc.id, doc.data() || {})),
  };
}

/** Root/Billing review: only the trusted approval credits the institute wallet. */
async function reviewRechargeRequest({ db, request, operationId, now }) {
  const reviewer = await assertPlatformRole(db, request.auth, ["root", "billing"]);
  const instituteId = requiredString(request.data, "instituteId", 128);
  const requestId = requiredString(request.data, "requestId", 128);
  const decision = requiredString(request.data, "decision", 16).toLowerCase();
  if (!SMS_REVIEW_DECISIONS.has(decision)) {
    throw new HttpsError("invalid-argument", "The review decision must be approve or reject.");
  }
  const note = optionalString(request.data, "note", 500);

  const ref = db.collection("institutes").doc(instituteId)
    .collection("sms_recharge_requests").doc(requestId);
  const result = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("not-found", "This recharge request does not exist.");
    const data = snap.data() || {};
    if (data.status !== "pending") {
      if (data.reviewedBy === reviewer.uid && data.reviewDecision === decision) {
        return { request: publicRechargeRequest(requestId, data), alreadyReviewed: true };
      }
      throw new HttpsError("failed-precondition", "This recharge request was already reviewed.");
    }

    // Re-quote the package server-side; a stale or forged amount is ignored.
    const pkg = packageById(data.packageId);
    if (!pkg || data.smsCount !== pkg.smsCount) {
      throw new HttpsError("failed-precondition", "This request has no valid server quote. Ask the owner to resubmit.");
    }

    const instituteRef = db.collection("institutes").doc(data.instituteId);
    const auditRef = instituteRef.collection("sms_wallet_audit").doc(operationId);
    const activityRef = instituteRef.collection("platform_activity_events").doc(operationId);

    if (decision === "approve") {
      const instituteSnap = await tx.get(instituteRef);
      if (!instituteSnap.exists) {
        throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
      }
      const wallet = walletDefaults(instituteSnap.data() || {});
      tx.update(instituteRef, {
        sms_balance: wallet.sms_balance + data.smsCount,
        total_sms_purchased: wallet.total_sms_purchased + data.smsCount,
      });
      tx.update(ref, {
        status: "approved",
        reviewedBy: reviewer.uid,
        reviewDecision: decision,
        reviewedAtMs: now,
        reviewerNote: note,
      });
      tx.set(auditRef, {
        instituteId: data.instituteId,
        action: "recharge_approved",
        requestId,
        packageId: data.packageId,
        smsCount: data.smsCount,
        payableAmount: data.payableAmount,
        paymentMethod: data.paymentMethod,
        actorUid: reviewer.uid,
        createdAtMs: now,
        operationId,
      });
      tx.create(activityRef, {
        action: "sms_recharge_approved",
        actorUid: reviewer.uid,
        actorRole: reviewer.platformRole,
        targetType: "institute",
        targetId: data.instituteId,
        outcome: "completed",
        summary: `${data.smsCount} SMS credited (${data.packageName}, BDT ${data.payableAmount})`,
        supportReason: "",
        occurredAtMs: now,
      });
    } else {
      tx.update(ref, {
        status: "rejected",
        reviewedBy: reviewer.uid,
        reviewDecision: decision,
        reviewedAtMs: now,
        reviewerNote: note,
      });
      tx.create(activityRef, {
        action: "sms_recharge_rejected",
        actorUid: reviewer.uid,
        actorRole: reviewer.platformRole,
        targetType: "institute",
        targetId: data.instituteId,
        outcome: "completed",
        summary: `${data.packageName} recharge rejected (BDT ${data.payableAmount})`,
        supportReason: "",
        occurredAtMs: now,
      });
    }
    return {
      request: publicRechargeRequest(requestId, {
        ...data,
        status: decision === "approve" ? "approved" : "rejected",
        reviewedBy: reviewer.uid,
        reviewDecision: decision,
        reviewedAtMs: now,
        reviewerNote: note,
      }),
      alreadyReviewed: false,
    };
  });
  return result;
}

/** Root-only platform accounting: collected, credited, used, cost, and profit. */
async function smsAccounting({ db, request }) {
  await assertPlatformRole(db, request.auth, ["root"]);

  const approved = await db.collectionGroup("sms_recharge_requests")
    .where("status", "==", "approved")
    .get();
  const pending = await db.collectionGroup("sms_recharge_requests")
    .where("status", "==", "pending")
    .get();

  let totalCollectedTaka = 0;
  let totalCreditedSms = 0;
  let approvedCount = 0;
  for (const doc of approved.docs) {
    const data = doc.data() || {};
    totalCollectedTaka += Number.isFinite(data.payableAmount) ? data.payableAmount : 0;
    totalCreditedSms += Number.isInteger(data.smsCount) ? data.smsCount : 0;
    approvedCount += 1;
  }

  const institutes = await db.collection("institutes").get();
  let totalUsedSms = 0;
  let outstandingBalance = 0;
  for (const doc of institutes.docs) {
    const data = doc.data() || {};
    totalUsedSms += Number.isInteger(data.total_sms_used) ? data.total_sms_used : 0;
    outstandingBalance += Number.isInteger(data.sms_balance) ? data.sms_balance : 0;
  }

  const smsUnitCostPaisa = SMS_UNIT_COST_PAISA;
  const totalCostTaka = (totalCreditedSms * smsUnitCostPaisa) / 100;
  return {
    rechargeRequestCount: approvedCount,
    pendingRequestCount: pending.size,
    totalCollectedTaka,
    totalCreditedSms,
    totalUsedSms,
    outstandingBalance,
    smsUnitCostPaisa,
    totalCostTaka,
    profitTaka: totalCollectedTaka - totalCostTaka,
  };
}

function publicSmsMessage(id, data) {
  return {
    messageId: id,
    recipient: typeof data.recipient === "string" ? data.recipient : "",
    purpose: typeof data.purpose === "string" ? data.purpose : "",
    channel: SMS_MESSAGE_CHANNELS.has(data.channel) ? data.channel : "carrier",
    status: SMS_MESSAGE_STATUSES.has(data.status) ? data.status : "sent",
    createdAtMs: safeMillis(data.createdAtMs),
    updatedAtMs: safeMillis(data.updatedAtMs),
    deliveredAtMs: safeMillis(data.deliveredAtMs),
    failureReason: typeof data.failureReason === "string" ? data.failureReason : "",
  };
}

/**
 * Records outbound SMS messages for the institute. Carrier hand-offs are
 * recorded as sent (the phone's own SMS app performs the delivery, which the
 * app cannot observe); server sends start pending and are updated through
 * gateway callbacks via updateSmsMessageStatus.
 */
async function recordSmsBatch({ db, request, operationId, now }) {
  const actor = await resolveTenantActor(db, request.auth);
  const messages = request.data && Array.isArray(request.data.messages) ? request.data.messages : [];
  if (messages.length === 0 || messages.length > MAX_SMS_BATCH) {
    throw new HttpsError("invalid-argument", `A batch must contain 1-${MAX_SMS_BATCH} messages.`);
  }
  const channel = request.data && request.data.channel ? String(request.data.channel) : "carrier";
  if (!SMS_MESSAGE_CHANNELS.has(channel)) {
    throw new HttpsError("invalid-argument", "The SMS channel must be carrier or server.");
  }
  const rows = messages.map((message) => ({
    recipient: requiredString(message, "recipient", 24),
    purpose: optionalString(message, "purpose", 120),
  }));

  const collection = db.collection("institutes").doc(actor.instituteId).collection("sms_messages");
  const firstRef = collection.doc(`${operationId}-0001`);
  const existing = await firstRef.get();
  if (existing.exists) {
    // Same-batch replay after a lost acknowledgement: never duplicate rows.
    return { recorded: 0, replayed: true };
  }

  const status = channel === "carrier" ? "sent" : "pending";
  await db.runTransaction(async (tx) => {
    rows.forEach((row, index) => {
      tx.set(collection.doc(`${operationId}-${String(index + 1).padStart(4, "0")}`), {
        instituteId: actor.instituteId,
        recipient: row.recipient,
        purpose: row.purpose,
        channel,
        status,
        createdAtMs: now,
        updatedAtMs: now,
        deliveredAtMs: 0,
        failureReason: "",
        operationId,
      });
    });
  });
  return { recorded: rows.length, replayed: false };
}

function publicServerSmsResult(id, data) {
  return {
    messageId: id,
    targetKey: typeof data.targetKey === "string" ? data.targetKey : "",
    recipient: typeof data.recipient === "string" ? data.recipient : "",
    purpose: typeof data.purpose === "string" ? data.purpose : "",
    status: SMS_MESSAGE_STATUSES.has(data.status) ? data.status : "pending",
    providerStatus: typeof data.providerStatus === "string" ? data.providerStatus : "",
    providerMessageId: typeof data.providerMessageId === "string" ? data.providerMessageId : "",
    credits: Number.isInteger(data.credits) && data.credits > 0 ? data.credits : 1,
    failureReason: typeof data.failureReason === "string" ? data.failureReason : "",
  };
}

function documentId(ref) {
  if (ref && typeof ref.id === "string" && ref.id) return ref.id;
  const path = ref && typeof ref.path === "string" ? ref.path : "";
  return path.split("/").filter(Boolean).at(-1) || "";
}

async function settleServerSms({ db, instituteId, ref, providerResult, providerError, now }) {
  return db.runTransaction(async (tx) => {
    const messageSnap = await tx.get(ref);
    if (!messageSnap.exists) throw new HttpsError("internal", "The reserved SMS record is missing.");
    const current = messageSnap.data() || {};
    if (current.status === "sent" || current.status === "delivered" || current.status === "failed") {
      return publicServerSmsResult(documentId(ref), current);
    }

    if (providerResult && providerResult.accepted) {
      const patch = {
        status: providerResult.status === "pending" ? "pending" : "sent",
        providerStatus: providerResult.providerStatus || "",
        providerMessageId: providerResult.messageId || "",
        failureReason: "",
        updatedAtMs: now,
      };
      tx.update(ref, patch);
      return publicServerSmsResult(documentId(ref), { ...current, ...patch });
    }

    const ambiguous = !!(providerError && providerError.ambiguous);
    const failureReason = providerError && typeof providerError.message === "string"
      ? providerError.message.slice(0, 240)
      : providerResult && providerResult.failureReason
        ? String(providerResult.failureReason).slice(0, 240)
        : "The SMS provider rejected this message.";
    const providerStatus = providerError && providerError.code
      ? String(providerError.code).slice(0, 32)
      : providerResult && providerResult.providerStatus
        ? String(providerResult.providerStatus).slice(0, 32)
        : "UNKNOWN";

    // A timeout/network break may happen after the provider accepted the SMS.
    // Keep the debit and mark it pending so a retry cannot send a duplicate.
    if (ambiguous) {
      const patch = { status: "pending", providerStatus, failureReason, updatedAtMs: now };
      tx.update(ref, patch);
      return publicServerSmsResult(documentId(ref), { ...current, ...patch });
    }

    const instituteRef = db.collection("institutes").doc(instituteId);
    const instituteSnap = await tx.get(instituteRef);
    const wallet = walletDefaults(instituteSnap.data() || {});
    const credits = Number.isInteger(current.credits) && current.credits > 0 ? current.credits : 1;
    const patch = {
      status: "failed",
      providerStatus,
      failureReason,
      refundedAtMs: now,
      updatedAtMs: now,
    };
    tx.update(instituteRef, {
      sms_balance: wallet.sms_balance + credits,
      total_sms_used: Math.max(0, wallet.total_sms_used - credits),
    });
    tx.update(ref, patch);
    const messageId = documentId(ref);
    tx.set(instituteRef.collection("sms_wallet_audit").doc(`${messageId}-refund`), {
      instituteId,
      action: "server_sms_refund",
      messageId,
      smsCount: credits,
      createdAtMs: now,
    });
    return publicServerSmsResult(messageId, { ...current, ...patch });
  });
}

async function mapWithConcurrency(items, limit, mapper) {
  const results = new Array(items.length);
  let cursor = 0;
  async function worker() {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await mapper(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

/**
 * Authenticated, idempotent server-side SMS dispatch. Credits are reserved in
 * one transaction before any provider call. Definite provider rejections are
 * refunded; ambiguous network/timeouts stay pending to prevent duplicate SMS.
 */
async function sendServerSmsBatch({ db, request, smsProvider, now = Date.now() }) {
  if (!smsProvider || typeof smsProvider.sendTextSms !== "function") {
    throw new HttpsError("failed-precondition", "The SMS provider is not configured.");
  }
  const actor = await resolveTenantActor(db, request.auth);
  assertCanSendServerSms(actor);
  const operationId = requiredString(request.data, "operationId", 128);
  if (!validOperationId(operationId)) throw new HttpsError("invalid-argument", "Invalid SMS operation ID.");
  const input = request.data && Array.isArray(request.data.messages) ? request.data.messages : [];
  if (input.length === 0 || input.length > MAX_SERVER_SMS_BATCH) {
    throw new HttpsError("invalid-argument", `A server SMS batch must contain 1-${MAX_SERVER_SMS_BATCH} messages.`);
  }
  const rows = input.map((message) => {
    const row = {
      recipient: requiredString(message, "recipient", 24),
      message: requiredString(message, "message", 480),
      purpose: optionalString(message, "purpose", 120),
      targetKey: optionalString(message, "targetKey", 128),
    };
    row.credits = smsCreditCount(row.message);
    row.payloadHash = smsPayloadHash(row);
    return row;
  });
  const totalCredits = rows.reduce((sum, row) => sum + row.credits, 0);
  const instituteRef = db.collection("institutes").doc(actor.instituteId);
  const collection = instituteRef.collection("sms_messages");
  const refs = rows.map((_, index) => collection.doc(`${operationId}-${String(index + 1).padStart(4, "0")}`));

  const reservation = await db.runTransaction(async (tx) => {
    const instituteSnap = await tx.get(instituteRef);
    if (!instituteSnap.exists) throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
    const messageSnaps = [];
    for (const ref of refs) messageSnaps.push(await tx.get(ref));
    const existingCount = messageSnaps.filter((snap) => snap.exists).length;
    if (existingCount === refs.length) {
      const mismatched = messageSnaps.some((snap, index) => snap.get("payloadHash") !== rows[index].payloadHash);
      if (mismatched) throw new HttpsError("already-exists", "This SMS operation ID was already used with different data.");
      return { replayed: true, results: messageSnaps.map((snap, index) => publicServerSmsResult(documentId(refs[index]), snap.data() || {})) };
    }
    if (existingCount > 0) throw new HttpsError("aborted", "The previous SMS batch is incomplete. Contact support before retrying.");

    const wallet = walletDefaults(instituteSnap.data() || {});
    if (wallet.sms_send_method !== "server") {
      throw new HttpsError("failed-precondition", "Select BatchFee Server as the SMS sending method first.");
    }
    if (wallet.sms_balance < totalCredits) {
      throw new HttpsError("resource-exhausted", `Insufficient SMS balance. Required ${totalCredits}, available ${wallet.sms_balance}.`);
    }
    tx.update(instituteRef, {
      sms_balance: wallet.sms_balance - totalCredits,
      total_sms_used: wallet.total_sms_used + totalCredits,
    });
    rows.forEach((row, index) => tx.create(refs[index], {
      instituteId: actor.instituteId,
      recipient: row.recipient,
      targetKey: row.targetKey,
      purpose: row.purpose,
      channel: "server",
      status: "pending",
      providerStatus: "RESERVED",
      providerMessageId: "",
      credits: row.credits,
      failureReason: "",
      payloadHash: row.payloadHash,
      operationId,
      createdByUid: actor.uid,
      createdAtMs: now,
      updatedAtMs: now,
      deliveredAtMs: 0,
    }));
    tx.set(instituteRef.collection("sms_wallet_audit").doc(`${operationId}-reserve`), {
      instituteId: actor.instituteId,
      action: "server_sms_reserved",
      smsCount: totalCredits,
      messageCount: rows.length,
      actorUid: actor.uid,
      operationId,
      createdAtMs: now,
    });
    return { replayed: false };
  });

  if (reservation.replayed) {
    const walletSnap = await instituteRef.get();
    return { replayed: true, wallet: walletDto(walletSnap.data() || {}), results: reservation.results };
  }

  const results = await mapWithConcurrency(rows, 4, async (row, index) => {
    let providerResult = null;
    let providerError = null;
    try {
      providerResult = await smsProvider.sendTextSms({ number: row.recipient, message: row.message });
    } catch (error) {
      providerError = error;
    }
    try {
      return await settleServerSms({
        db,
        instituteId: actor.instituteId,
        ref: refs[index],
        providerResult,
        providerError,
        now: Date.now(),
      });
    } catch (_) {
      // The provider may already have accepted the SMS even when persisting its
      // acknowledgement fails. Keep the reservation pending and return a
      // non-retryable result so the client cannot create a second operation.
      return publicServerSmsResult(documentId(refs[index]), {
        ...row,
        status: "pending",
        providerStatus: "SETTLEMENT_PENDING",
        failureReason: "SMS gateway response is awaiting reconciliation.",
      });
    }
  });
  const walletSnap = await instituteRef.get();
  return { replayed: false, wallet: walletDto(walletSnap.data() || {}), results };
}

function createServerSmsHandler({ db, smsProvider }) {
  return async (request) => sendServerSmsBatch({ db, request, smsProvider });
}

/** Status counts and the latest messages for the institute's own SMS report. */
async function listSmsReport({ db, request }) {
  const actor = await resolveTenantActor(db, request.auth);
  const snapshot = await db.collection("institutes").doc(actor.instituteId)
    .collection("sms_messages")
    .orderBy("createdAtMs", "desc")
    .limit(MAX_SMS_REPORT)
    .get();
  const counts = { sent: 0, delivered: 0, pending: 0, failed: 0 };
  for (const doc of snapshot.docs) {
    const status = doc.get("status");
    if (Object.prototype.hasOwnProperty.call(counts, status)) counts[status] += 1;
  }
  return {
    counts,
    messages: snapshot.docs.map((doc) => publicSmsMessage(doc.id, doc.data() || {})),
  };
}

/**
 * Server-only status transition for SMS gateway callbacks. This is never
 * exposed as a tenant callable action; only a future trusted webhook/function
 * may call it so clients can never mark their own messages as delivered.
 */
async function updateSmsMessageStatus(db, instituteId, messageId, status, options = {}) {
  if (!SMS_MESSAGE_STATUSES.has(status)) {
    throw new HttpsError("invalid-argument", "Invalid SMS message status.");
  }
  const now = safeMillis(options.now, Date.now());
  const patch = { status, updatedAtMs: now };
  if (status === "delivered") patch.deliveredAtMs = safeMillis(options.deliveredAtMs, now);
  if (status === "failed") patch.failureReason = optionalString({ failureReason: options.failureReason }, "failureReason", 240);
  const ref = db.collection("institutes").doc(instituteId).collection("sms_messages").doc(messageId);
  await ref.update(patch);
}

function createSmsWalletHandler({ db }) {
  return async (request) => {
    const action = requiredString(request.data, "action", 64);
    const operationId = requiredString(request.data, "operationId", 128);
    if (!SMS_WALLET_ACTIONS.has(action) || !validOperationId(operationId)) {
      throw new HttpsError("invalid-argument", "Invalid SMS wallet operation.");
    }
    const now = Date.now();
    if (action === "get_wallet") return getWallet({ db, request });
    if (action === "set_send_method") return setSendMethod({ db, request, operationId, now });
    if (action === "list_packages") return listPackages({ db, request });
    if (action === "submit_recharge_request") return submitRechargeRequest({ db, request, operationId, now });
    if (action === "list_my_recharge_requests") return listMyRechargeRequests({ db, request });
    if (action === "list_recharge_requests") return listRechargeRequests({ db, request });
    if (action === "review_recharge_request") return reviewRechargeRequest({ db, request, operationId, now });
    if (action === "record_sms_batch") return recordSmsBatch({ db, request, operationId, now });
    if (action === "list_sms_report") return listSmsReport({ db, request });
    return smsAccounting({ db, request });
  };
}

module.exports = {
  SMS_WALLET_ACTIONS,
  SMS_SEND_METHODS,
  SMS_PAYMENT_METHODS,
  SMS_MESSAGE_STATUSES,
  SMS_MESSAGE_CHANNELS,
  SMS_PACKAGES,
  SMS_RECHARGE_CHARGE_PERCENT,
  SMS_UNIT_COST_PAISA,
  MAX_SERVER_SMS_BATCH,
  WALLET_FIELDS,
  createServerSmsHandler,
  createSmsWalletHandler,
  updateSmsMessageStatus,
  walletDefaults,
  rechargeQuote,
  publicPackage,
  publicRechargeRequest,
  publicSmsMessage,
  sendServerSmsBatch,
  smsCreditCount,
};
