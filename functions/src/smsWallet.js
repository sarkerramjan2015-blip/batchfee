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
const SMS_REVIEW_DECISIONS = new Set(["approve", "approve_partial", "reject"]);
const SMS_MESSAGE_STATUSES = new Set(["pending", "sent", "delivered", "failed"]);
const SMS_MESSAGE_CHANNELS = new Set(["carrier", "server"]);
const PLATFORM_ROLES = new Set(["root", "billing", "support", "operations", "read_only"]);

const WALLET_FIELDS = {
  sms_balance: 0,
  total_sms_purchased: 0,
  total_sms_used: 0,
  sms_used_today: 0,
  sms_usage_day_key: "",
  sms_used_this_month: 0,
  sms_usage_month_key: "",
  sms_send_method: "server",
};

// BatchFee's service charge collected from an institute owner. This is shown
// separately from the SMS sale rate so finance can reconcile both amounts.
const SMS_RECHARGE_CHARGE_PERCENT = 1.8;

// SMS_PACKAGES is the single authoritative price list. Clients never send
// amounts; they send a package ID and the server quotes the price again.
const SMS_PACKAGES = [
  // Base selling rates run from BDT 0.38 down to BDT 0.32 per SMS.
  // The 1.8% BatchFee service charge is added by rechargeQuote below.
  { id: "starter", layer: "Small & Medium Batches", name: "Starter", baseAmount: 100, smsCount: 265 },
  { id: "basic", layer: "Small & Medium Batches", name: "Basic", baseAmount: 200, smsCount: 540 },
  { id: "standard", layer: "Small & Medium Batches", name: "Standard", baseAmount: 500, smsCount: 1385 },
  { id: "pro", layer: "Large Coaching Centers", name: "Pro", baseAmount: 1000, smsCount: 2855 },
  { id: "premium", layer: "Large Coaching Centers", name: "Premium", baseAmount: 2000, smsCount: 5880 },
  { id: "advanced", layer: "Mega Coaching & Schools", name: "Advanced", baseAmount: 5000, smsCount: 15150 },
  { id: "enterprise", layer: "Mega Coaching & Schools", name: "Enterprise", baseAmount: 10000, smsCount: 31250 },
];

// ZendSMS's non-masking price is BDT 0.25 per segment (VAT included). The
// provider also charges 2% when BatchFee recharges its central wallet. The
// effective procurement cost therefore is BDT 0.255 per sellable SMS.
const SMS_UNIT_COST_PAISA = 25;
const SMS_PROVIDER_RECHARGE_CHARGE_PERCENT = 2;

const MAX_MY_REQUESTS = 20;
const MAX_PLATFORM_REQUESTS = 100;
const MAX_SMS_BATCH = 400;
const MAX_SERVER_SMS_BATCH = 100;
// Owners receive the latest rows for browsing, while period summaries inspect
// a bounded larger window. The response clearly tells the owner if an old,
// unusually large history has been truncated.
const MAX_SMS_REPORT_ROWS = 200;
const MAX_SMS_REPORT_METRICS = 5000;
const MAX_PLATFORM_SMS_EVENTS = 5000;
// The root dashboard receives a readable audit window, while the 5,000-event
// source window continues to power accurate recent delivery totals.
const MAX_PLATFORM_SMS_AUDIT_ROWS = 150;
const MAX_PLATFORM_SMS_TOPUPS = 100;

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

/** Actual amount verified by Root/Billing; never supplied by an institute owner. */
function verifiedReceivedAmount(data) {
  const value = Number(data && data.receivedAmount);
  if (!Number.isFinite(value) || value <= 0 || value > 1000000) {
    throw new HttpsError("invalid-argument", "Enter the verified received amount.");
  }
  return Math.round(value * 100) / 100;
}

function positiveWholeNumber(data, field, maximum = 100000000) {
  const value = Number(data && data[field]);
  if (!Number.isInteger(value) || value < 1 || value > maximum) {
    throw new HttpsError("invalid-argument", `Enter a valid ${field}.`);
  }
  return value;
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

function dhakaUsageKeys(now = Date.now()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Dhaka",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(now));
  const value = (type) => parts.find((part) => part.type === type)?.value || "";
  const year = value("year");
  const month = value("month");
  const day = value("day");
  return { dayKey: `${year}-${month}-${day}`, monthKey: `${year}-${month}` };
}

function walletDefaults(data, now = Date.now()) {
  const usageKeys = dhakaUsageKeys(now);
  const out = {};
  out.sms_balance = Number.isInteger(data.sms_balance) && data.sms_balance >= 0 ? data.sms_balance : 0;
  out.total_sms_purchased =
    Number.isInteger(data.total_sms_purchased) && data.total_sms_purchased >= 0 ? data.total_sms_purchased : 0;
  out.total_sms_used = Number.isInteger(data.total_sms_used) && data.total_sms_used >= 0 ? data.total_sms_used : 0;
  out.sms_usage_day_key = usageKeys.dayKey;
  out.sms_used_today = data.sms_usage_day_key === usageKeys.dayKey && Number.isInteger(data.sms_used_today) && data.sms_used_today >= 0
    ? data.sms_used_today : 0;
  out.sms_usage_month_key = usageKeys.monthKey;
  out.sms_used_this_month = data.sms_usage_month_key === usageKeys.monthKey && Number.isInteger(data.sms_used_this_month) && data.sms_used_this_month >= 0
    ? data.sms_used_this_month : 0;
  out.sms_send_method = SMS_SEND_METHODS.has(data.sms_send_method) ? data.sms_send_method : "server";
  return out;
}

function walletDto(data, now = Date.now()) {
  const wallet = walletDefaults(data, now);
  return {
    smsBalance: wallet.sms_balance,
    totalSmsPurchased: wallet.total_sms_purchased,
    totalSmsUsed: wallet.total_sms_used,
    smsUsedToday: wallet.sms_used_today,
    smsUsedThisMonth: wallet.sms_used_this_month,
    smsSendMethod: wallet.sms_send_method,
  };
}

function packageById(packageId) {
  return SMS_PACKAGES.find((pkg) => pkg.id === packageId) || null;
}

function rechargeQuote(pkg) {
  const chargeAmount = roundMoney(pkg.baseAmount * (SMS_RECHARGE_CHARGE_PERCENT / 100));
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

function roundMoney(value) {
  return Math.round(Number(value) * 100) / 100;
}

function providerEffectiveCostPerSmsBdt() {
  return (SMS_UNIT_COST_PAISA / 100) * (1 + SMS_PROVIDER_RECHARGE_CHARGE_PERCENT / 100);
}

function smsRatePaisa(amountBdt, smsCount) {
  return smsCount > 0 ? roundMoney((amountBdt * 100) / smsCount) : 0;
}

/**
 * Preserves the economics of each approved recharge. New approvals write this
 * immutable snapshot; old records use the stored quote as a safe fallback.
 */
function rechargeFinancialSnapshot(data, creditedSmsCount, receivedAmount) {
  const quotedBase = Number(data.baseAmount);
  const quotedCharge = Number(data.chargeAmount);
  const quotedPayable = Number(data.payableAmount);
  const hasQuote = Number.isFinite(quotedBase) && quotedBase >= 0
    && Number.isFinite(quotedCharge) && quotedCharge >= 0
    && Number.isFinite(quotedPayable) && quotedPayable > 0;
  const totalCollectedBdt = roundMoney(receivedAmount);
  const serviceChargeBdt = hasQuote
    ? roundMoney(totalCollectedBdt * (quotedCharge / quotedPayable))
    : 0;
  const smsSalesBdt = roundMoney(totalCollectedBdt - serviceChargeBdt);
  const providerCostBdt = roundMoney(creditedSmsCount * providerEffectiveCostPerSmsBdt());
  return {
    totalCollectedBdt,
    smsSalesBdt,
    serviceChargeBdt,
    providerCostBdt,
    grossProfitBdt: roundMoney(totalCollectedBdt - providerCostBdt),
    saleRatePaisa: smsRatePaisa(smsSalesBdt, creditedSmsCount),
    providerBaseRatePaisa: SMS_UNIT_COST_PAISA,
    providerRechargeChargePercent: SMS_PROVIDER_RECHARGE_CHARGE_PERCENT,
    providerEffectiveRatePaisa: smsRatePaisa(providerCostBdt, creditedSmsCount),
  };
}

function zeroFinancialPeriod() {
  return {
    creditedSms: 0,
    smsSalesBdt: 0,
    serviceChargeBdt: 0,
    totalCollectedBdt: 0,
    providerEstimatedCostBdt: 0,
    grossProfitBdt: 0,
    centralTopupSpendBdt: 0,
    centralTopupSms: 0,
  };
}

function addRechargeToFinancialPeriod(period, data) {
  const creditedSms = Number.isInteger(data.creditedSmsCount) ? data.creditedSmsCount
    : (Number.isInteger(data.smsCount) ? data.smsCount : 0);
  const receivedAmount = Number.isFinite(data.receivedAmount) ? data.receivedAmount
    : (Number.isFinite(data.payableAmount) ? data.payableAmount : 0);
  const saved = data.financials && typeof data.financials === "object" ? data.financials : null;
  const fallback = rechargeFinancialSnapshot(data, creditedSms, receivedAmount);
  const value = (field) => saved && Number.isFinite(saved[field]) ? saved[field] : fallback[field];
  period.creditedSms += creditedSms;
  period.smsSalesBdt += value("smsSalesBdt");
  period.serviceChargeBdt += value("serviceChargeBdt");
  period.totalCollectedBdt += value("totalCollectedBdt");
  period.providerEstimatedCostBdt += value("providerCostBdt");
  period.grossProfitBdt += value("grossProfitBdt");
}

function addCentralTopupToFinancialPeriod(period, data) {
  period.centralTopupSpendBdt += Number(data.paidAmountBdt) || 0;
  period.centralTopupSms += Number.isInteger(data.purchasedSms) ? data.purchasedSms : 0;
}

function finaliseFinancialPeriod(period) {
  const rounded = {};
  for (const [key, value] of Object.entries(period)) {
    rounded[key] = key.endsWith("Sms") ? value : roundMoney(value);
  }
  return {
    ...rounded,
    // Cash movement is informative, but not booked profit: a central top-up
    // creates SMS inventory that may be sold in a later period.
    cashNetAfterTopupsBdt: roundMoney(rounded.totalCollectedBdt - rounded.centralTopupSpendBdt),
    averageSmsSaleRatePaisa: smsRatePaisa(rounded.smsSalesBdt, rounded.creditedSms),
  };
}

function financialPeriodNames(occurredAtMs, now) {
  const current = dhakaUsageKeys(now);
  const occurred = dhakaUsageKeys(occurredAtMs);
  const weekStart = new Date(`${current.dayKey}T00:00:00Z`);
  weekStart.setUTCDate(weekStart.getUTCDate() - ((weekStart.getUTCDay() + 6) % 7));
  const weekKey = weekStart.toISOString().slice(0, 10);
  const periods = ["lifetime"];
  if (occurred.dayKey === current.dayKey) periods.push("today");
  if (occurred.dayKey >= weekKey && occurred.dayKey <= current.dayKey) periods.push("week");
  if (occurred.monthKey === current.monthKey) periods.push("month");
  return periods;
}

function publicPackage(pkg) {
  return rechargeQuote(pkg);
}

/**
 * A submitted quote is immutable. Keeping it independent from the current
 * rate card lets us change future package pricing without invalidating an
 * owner's already-submitted payment request.
 */
function storedRechargeQuote(data) {
  const smsCount = Number.isInteger(data.requestedSmsCount) ? data.requestedSmsCount : data.smsCount;
  const baseAmount = Number(data.baseAmount);
  const chargeAmount = Number(data.chargeAmount);
  const payableAmount = Number(data.payableAmount);
  const chargePercent = Number(data.chargePercent);
  if (!Number.isInteger(smsCount) || smsCount < 1 || !Number.isFinite(baseAmount) || baseAmount <= 0
    || !Number.isFinite(chargeAmount) || chargeAmount < 0 || !Number.isFinite(payableAmount) || payableAmount <= 0
    || !Number.isFinite(chargePercent) || chargePercent < 0 || chargePercent > 100
    || Math.abs(roundMoney(baseAmount + chargeAmount) - payableAmount) > 0.001) {
    throw new HttpsError("failed-precondition", "This request has no valid server quote. Ask the owner to resubmit.");
  }
  return { smsCount, baseAmount, chargeAmount, payableAmount, chargePercent };
}

function publicRechargeRequest(id, data) {
  const status = typeof data.status === "string" ? data.status : "pending";
  const requestedSmsCount = Number.isInteger(data.requestedSmsCount) ? data.requestedSmsCount
    : (Number.isInteger(data.smsCount) ? data.smsCount : 0);
  const creditedSmsCount = Number.isInteger(data.creditedSmsCount) ? data.creditedSmsCount
    : (status === "approved" ? requestedSmsCount : 0);
  return {
    requestId: id,
    status,
    packageId: typeof data.packageId === "string" ? data.packageId : "",
    packageName: typeof data.packageName === "string" ? data.packageName : "",
    layer: typeof data.layer === "string" ? data.layer : "",
    // Pending requests show the quote; approved requests show the credits actually granted.
    smsCount: status === "approved" ? creditedSmsCount : requestedSmsCount,
    requestedSmsCount,
    creditedSmsCount,
    baseAmount: Number.isFinite(data.baseAmount) ? data.baseAmount : 0,
    chargeAmount: Number.isFinite(data.chargeAmount) ? data.chargeAmount : 0,
    payableAmount: Number.isFinite(data.payableAmount) ? data.payableAmount : 0,
    receivedAmount: Number.isFinite(data.receivedAmount) ? data.receivedAmount : 0,
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
async function getWallet({ db, request, now = Date.now() }) {
  const actor = await resolveTenantActor(db, request.auth);
  const ref = db.collection("institutes").doc(actor.instituteId);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
    const data = snap.data() || {};
    const canonical = walletDefaults(data, now);
    const patch = {};
    for (const field of Object.keys(WALLET_FIELDS)) {
      if (data[field] !== canonical[field]) patch[field] = canonical[field];
    }
    if (Object.keys(patch).length > 0) tx.update(ref, patch);
  });
  const snap = await ref.get();
  return walletDto(snap.data() || {}, now);
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
    requestedSmsCount: quote.smsCount,
    creditedSmsCount: 0,
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

    // Use the quote frozen at owner submission time, never the live rate card.
    // This prevents a later price change from breaking an existing payment.
    const quote = storedRechargeQuote(data);
    const requestedSmsCount = quote.smsCount;

    const instituteRef = db.collection("institutes").doc(data.instituteId);
    const auditRef = instituteRef.collection("sms_wallet_audit").doc(operationId);
    const activityRef = instituteRef.collection("platform_activity_events").doc(operationId);
    let creditedSmsCount = 0;
    let receivedAmount = 0;

    if (decision === "approve" || decision === "approve_partial") {
      receivedAmount = verifiedReceivedAmount(request.data);
      if (decision === "approve" && receivedAmount < quote.payableAmount) {
        throw new HttpsError("failed-precondition", "The verified payment is below the quoted amount. Use partial approval or reject it.");
      }
      if (decision === "approve_partial" && receivedAmount >= quote.payableAmount) {
        throw new HttpsError("failed-precondition", "The full quoted amount was received. Use full approval instead.");
      }
      creditedSmsCount = decision === "approve" ? quote.smsCount
        : Math.floor((receivedAmount / quote.payableAmount) * quote.smsCount);
      if (!Number.isInteger(creditedSmsCount) || creditedSmsCount < 1 || creditedSmsCount > quote.smsCount) {
        throw new HttpsError("failed-precondition", "The verified payment is too small to credit an SMS segment.");
      }
      const financials = rechargeFinancialSnapshot(data, creditedSmsCount, receivedAmount);
      const instituteSnap = await tx.get(instituteRef);
      if (!instituteSnap.exists) {
        throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
      }
      const wallet = walletDefaults(instituteSnap.data() || {});
      tx.update(instituteRef, {
        sms_balance: wallet.sms_balance + creditedSmsCount,
        total_sms_purchased: wallet.total_sms_purchased + creditedSmsCount,
      });
      tx.update(ref, {
        status: "approved",
        reviewedBy: reviewer.uid,
        reviewDecision: decision,
        reviewedAtMs: now,
        reviewerNote: note,
        requestedSmsCount,
        creditedSmsCount,
        receivedAmount,
        financials,
      });
      tx.set(auditRef, {
        instituteId: data.instituteId,
        action: "recharge_approved",
        requestId,
        packageId: data.packageId,
        smsCount: creditedSmsCount,
        requestedSmsCount,
        creditedSmsCount,
        payableAmount: quote.payableAmount,
        receivedAmount,
        financials,
        partialApproval: decision === "approve_partial",
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
        summary: `${creditedSmsCount} SMS credited (${data.packageName}, BDT ${receivedAmount}${decision === "approve_partial" ? ", partial payment" : ""})`,
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
        status: decision === "approve" || decision === "approve_partial" ? "approved" : "rejected",
        reviewedBy: reviewer.uid,
        reviewDecision: decision,
        reviewedAtMs: now,
        reviewerNote: note,
        requestedSmsCount,
        creditedSmsCount: decision === "approve" || decision === "approve_partial" ? creditedSmsCount : 0,
        receivedAmount: decision === "approve" || decision === "approve_partial" ? receivedAmount : 0,
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
  let smsSalesTaka = 0;
  let serviceChargeTaka = 0;
  let totalCreditedSms = 0;
  let approvedCount = 0;
  for (const doc of approved.docs) {
    const data = doc.data() || {};
    const creditedSms = Number.isInteger(data.creditedSmsCount)
      ? data.creditedSmsCount : (Number.isInteger(data.smsCount) ? data.smsCount : 0);
    const receivedAmount = Number.isFinite(data.receivedAmount)
      ? data.receivedAmount : (Number.isFinite(data.payableAmount) ? data.payableAmount : 0);
    const financials = rechargeFinancialSnapshot(data, creditedSms, receivedAmount);
    const saved = data.financials && typeof data.financials === "object" ? data.financials : null;
    totalCollectedTaka += saved && Number.isFinite(saved.totalCollectedBdt) ? saved.totalCollectedBdt : financials.totalCollectedBdt;
    smsSalesTaka += saved && Number.isFinite(saved.smsSalesBdt) ? saved.smsSalesBdt : financials.smsSalesBdt;
    serviceChargeTaka += saved && Number.isFinite(saved.serviceChargeBdt) ? saved.serviceChargeBdt : financials.serviceChargeBdt;
    totalCreditedSms += creditedSms;
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
  const providerEffectiveUnitCostPaisa = SMS_UNIT_COST_PAISA * (1 + SMS_PROVIDER_RECHARGE_CHARGE_PERCENT / 100);
  const totalCostTaka = roundMoney(totalCreditedSms * providerEffectiveCostPerSmsBdt());
  return {
    rechargeRequestCount: approvedCount,
    pendingRequestCount: pending.size,
    totalCollectedTaka: roundMoney(totalCollectedTaka),
    smsSalesTaka: roundMoney(smsSalesTaka),
    serviceChargeTaka: roundMoney(serviceChargeTaka),
    totalCreditedSms,
    totalUsedSms,
    outstandingBalance,
    smsUnitCostPaisa,
    providerRechargeChargePercent: SMS_PROVIDER_RECHARGE_CHARGE_PERCENT,
    providerEffectiveUnitCostPaisa,
    totalCostTaka,
    profitTaka: roundMoney(totalCollectedTaka - totalCostTaka),
  };
}

const MAX_PENDING_DLR_SYNC = 25;

function publicPlatformSmsTopup(id, data) {
  return {
    topupId: id,
    supplier: typeof data.supplier === "string" ? data.supplier : "",
    paidAmountBdt: Number.isFinite(data.paidAmountBdt) ? data.paidAmountBdt : 0,
    purchasedSms: Number.isInteger(data.purchasedSms) ? data.purchasedSms : 0,
    reference: typeof data.reference === "string" ? data.reference : "",
    note: typeof data.note === "string" ? data.note : "",
    recordedByUid: typeof data.recordedByUid === "string" ? data.recordedByUid : "",
    recordedAtMs: safeMillis(data.recordedAtMs),
  };
}

/**
 * Root records a verified central supplier purchase. Zend has no endpoint for
 * lifetime purchase history, so this immutable ledger is the source of truth
 * for central-procurement totals; it never changes the institute wallets.
 */
async function recordPlatformSmsTopup({ db, request, now = Date.now() }) {
  const actor = await assertPlatformRole(db, request.auth, ["root"]);
  const operationId = requiredString(request.data, "operationId", 128);
  if (!validOperationId(operationId)) throw new HttpsError("invalid-argument", "Invalid SMS top-up operation.");
  const supplier = requiredString(request.data, "supplier", 80);
  const paidAmountBdt = verifiedReceivedAmount({ receivedAmount: request.data && request.data.paidAmountBdt });
  const purchasedSms = positiveWholeNumber(request.data, "purchasedSms");
  const reference = optionalString(request.data, "reference", 100);
  const note = optionalString(request.data, "note", 300);
  const payloadHash = createHash("sha256").update(JSON.stringify({ supplier, paidAmountBdt, purchasedSms, reference, note })).digest("hex");
  const ref = db.collection("platform_sms_topups").doc(operationId);
  const result = await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) {
      if (existing.get("payloadHash") !== payloadHash || existing.get("recordedByUid") !== actor.uid) {
        throw new HttpsError("already-exists", "This SMS top-up operation ID was already used with different data.");
      }
      return { replayed: true, topup: publicPlatformSmsTopup(operationId, existing.data() || {}) };
    }
    const data = {
      supplier,
      paidAmountBdt,
      purchasedSms,
      reference,
      note,
      payloadHash,
      recordedByUid: actor.uid,
      recordedAtMs: now,
    };
    tx.create(ref, data);
    return { replayed: false, topup: publicPlatformSmsTopup(operationId, data) };
  });
  return result;
}

function zendDeliveryStatus(status) {
  const value = typeof status === "string" ? status.trim().toUpperCase() : "";
  if (value === "DELIVERED") return "delivered";
  if (["FAILED", "UNDELIVERABLE", "EXPIRED", "REJECTED"].includes(value)) return "failed";
  return "pending";
}

/**
 * Reconcile a small, recent set of queued Zend messages whenever Root refreshes
 * the control centre. A DLR failure is deliberately not refunded here: the
 * gateway's charge/refund policy must remain the financial source of truth.
 */
async function syncPendingZendDelivery({ db, smsProvider, now }) {
  if (!smsProvider || typeof smsProvider.getDeliveryStatus !== "function") {
    return { attempted: 0, updated: 0 };
  }
  const pending = await db.collectionGroup("sms_messages")
    .where("channel", "==", "server")
    .where("status", "==", "pending")
    .limit(MAX_PENDING_DLR_SYNC)
    .get();
  let attempted = 0;
  let updated = 0;
  await mapWithConcurrency(pending.docs, 4, async (doc) => {
    const data = doc.data() || {};
    const instituteId = typeof data.instituteId === "string" ? data.instituteId : "";
    const providerMessageId = typeof data.providerMessageId === "string" ? data.providerMessageId : "";
    if (!instituteId || !providerMessageId) return;
    attempted += 1;
    try {
      const result = await smsProvider.getDeliveryStatus(providerMessageId);
      const status = zendDeliveryStatus(result.status);
      if (status === "pending") return;
      const deliveredAtMs = result.deliveredAt ? Date.parse(result.deliveredAt) : now;
      await updateSmsMessageStatus(db, instituteId, doc.id, status, {
        now,
        deliveredAtMs: Number.isFinite(deliveredAtMs) ? deliveredAtMs : now,
        providerStatus: result.status,
        failureReason: status === "failed" ? `Zend DLR: ${result.status}` : "",
      });
      updated += 1;
    } catch (_) {
      // A temporary DLR lookup failure must not make the whole dashboard fail.
    }
  });
  return { attempted, updated };
}

/**
 * Same DLR reconciliation, scoped to the signed-in institute. This is used by
 * the institute's own History refresh so an owner never has to wait for a
 * platform administrator to open analytics before seeing a delivered result.
 */
async function syncPendingZendDeliveryForInstitute({ db, smsProvider, instituteId, now }) {
  if (!smsProvider || typeof smsProvider.getDeliveryStatus !== "function") {
    return { attempted: 0, updated: 0 };
  }
  const pending = await db.collection("institutes").doc(instituteId).collection("sms_messages")
    .where("channel", "==", "server")
    .where("status", "==", "pending")
    .limit(MAX_PENDING_DLR_SYNC)
    .get();
  let attempted = 0;
  let updated = 0;
  await mapWithConcurrency(pending.docs, 4, async (doc) => {
    const data = doc.data() || {};
    const providerMessageId = typeof data.providerMessageId === "string" ? data.providerMessageId : "";
    if (!providerMessageId) return;
    attempted += 1;
    try {
      const result = await smsProvider.getDeliveryStatus(providerMessageId);
      const status = zendDeliveryStatus(result.status);
      if (status === "pending") return;
      const deliveredAtMs = result.deliveredAt ? Date.parse(result.deliveredAt) : now;
      await updateSmsMessageStatus(db, instituteId, doc.id, status, {
        now,
        deliveredAtMs: Number.isFinite(deliveredAtMs) ? deliveredAtMs : now,
        providerStatus: result.status,
        failureReason: status === "failed" ? `Zend DLR: ${result.status}` : "",
      });
      updated += 1;
    } catch (_) {
      // A transient provider lookup error should leave the original status intact.
    }
  });
  return { attempted, updated };
}

/** Tenant-scoped DLR refresh. No delivery result is trusted from the client. */
async function refreshMySmsDelivery({ db, request, smsProvider, now = Date.now() }) {
  const actor = await resolveTenantActor(db, request.auth);
  assertCanSendServerSms(actor);
  return syncPendingZendDeliveryForInstitute({ db, smsProvider, instituteId: actor.instituteId, now });
}

/** Root-only, provider-backed operations view. Wallet totals are authoritative;
 * message rows supply the weekly/DLR view and institute breakdowns. */
async function platformSmsAnalytics({ db, request, smsProvider, now = Date.now() }) {
  await assertPlatformRole(db, request.auth, ["root"]);
  const dlrSync = await syncPendingZendDelivery({ db, smsProvider, now });
  const [institutes, approved, events, centralTopups] = await Promise.all([
    db.collection("institutes").get(),
    db.collectionGroup("sms_recharge_requests").where("status", "==", "approved").get(),
    db.collectionGroup("sms_messages").where("channel", "==", "server").orderBy("createdAtMs", "desc").limit(MAX_PLATFORM_SMS_EVENTS).get(),
    db.collection("platform_sms_topups").orderBy("recordedAtMs", "desc").limit(MAX_PLATFORM_SMS_TOPUPS).get(),
  ]);
  const day = dhakaUsageKeys(now);
  const weekStart = new Date(`${day.dayKey}T00:00:00Z`);
  weekStart.setUTCDate(weekStart.getUTCDate() - ((weekStart.getUTCDay() + 6) % 7));
  const weekKey = weekStart.toISOString().slice(0, 10);
  const rows = new Map();
  let lifetimeSms = 0, todaySms = 0, monthSms = 0, outstandingSms = 0;
  for (const doc of institutes.docs) {
    const data = doc.data() || {};
    const wallet = walletDefaults(data, now);
    lifetimeSms += wallet.total_sms_used;
    todaySms += wallet.sms_used_today;
    monthSms += wallet.sms_used_this_month;
    outstandingSms += wallet.sms_balance;
    rows.set(doc.id, {
      instituteId: doc.id,
      instituteName: typeof data.instituteName === "string" ? data.instituteName : doc.id,
      totalSmsPurchased: wallet.total_sms_purchased,
      todaySms: wallet.sms_used_today,
      weekSms: 0,
      monthSms: wallet.sms_used_this_month,
      lifetimeSms: wallet.total_sms_used,
      walletBalance: wallet.sms_balance,
    });
  }
  let weekSms = 0, delivered = 0, pending = 0, failed = 0;
  for (const doc of events.docs) {
    const data = doc.data() || {}; const used = Number.isInteger(data.credits) ? data.credits : 1;
    const id = typeof data.instituteId === "string" ? data.instituteId : "";
    const row = rows.get(id) || { instituteId: id, instituteName: id || "Unknown institute", totalSmsPurchased: 0, todaySms: 0, weekSms: 0, monthSms: 0, lifetimeSms: 0, walletBalance: 0 };
    const keys = dhakaUsageKeys(safeMillis(data.createdAtMs));
    if (keys.dayKey >= weekKey) { weekSms += used; row.weekSms += used; }
    const status = data.status; if (status === "delivered") delivered += used; else if (status === "failed") failed += used; else pending += used;
    rows.set(id, row);
  }
  const financialPeriods = {
    today: zeroFinancialPeriod(),
    week: zeroFinancialPeriod(),
    month: zeroFinancialPeriod(),
    lifetime: zeroFinancialPeriod(),
  };
  let soldSms = 0, collectedTaka = 0; const buyers = new Set();
  for (const doc of approved.docs) {
    const d = doc.data() || {};
    const creditedSms = Number.isInteger(d.creditedSmsCount) ? d.creditedSmsCount : (d.smsCount || 0);
    const receivedAmount = Number(d.receivedAmount ?? d.payableAmount) || 0;
    soldSms += creditedSms;
    collectedTaka += receivedAmount;
    const approvedAtMs = safeMillis(d.reviewedAtMs) || safeMillis(d.createdAtMs);
    for (const period of financialPeriodNames(approvedAtMs, now)) {
      addRechargeToFinancialPeriod(financialPeriods[period], d);
    }
    if (typeof d.instituteId === "string" && d.instituteId) buyers.add(d.instituteId);
  }
  let providerBalance = null, providerError = "";
  try { providerBalance = await smsProvider.getBalance(); } catch (e) { providerError = e && e.message ? e.message.slice(0, 120) : "Zend balance unavailable."; }
  // The gateway balance is already loaded, so capacity uses its BDT 0.25
  // consumption rate. A future top-up/reorder includes the separate 2% charge.
  const centralCapacity = providerBalance ? Math.floor(providerBalance.balance * 100 / SMS_UNIT_COST_PAISA) : 0;
  const reorderSms = providerBalance ? Math.max(0, outstandingSms - centralCapacity) : 0;
  let centralTopupPaidBdt = 0;
  let centralTopupSms = 0;
  for (const doc of centralTopups.docs) {
    const topup = doc.data() || {};
    centralTopupPaidBdt += Number(topup.paidAmountBdt) || 0;
    centralTopupSms += Number.isInteger(topup.purchasedSms) ? topup.purchasedSms : 0;
    for (const period of financialPeriodNames(safeMillis(topup.recordedAtMs), now)) {
      addCentralTopupToFinancialPeriod(financialPeriods[period], topup);
    }
  }
  const financials = Object.fromEntries(
    Object.entries(financialPeriods).map(([name, period]) => [name, finaliseFinancialPeriod(period)]),
  );
  return {
    todaySms, weekSms, monthSms, lifetimeSms, delivered, pending, failed,
    outstandingSms, soldSms, collectedTaka, buyerInstituteCount: buyers.size,
    providerBalanceBdt: providerBalance ? providerBalance.balance : null,
    providerCurrency: providerBalance ? providerBalance.currency : "BDT",
    centralCapacitySms: centralCapacity,
    reorderSms,
    reorderAmountBdt: roundMoney(reorderSms * providerEffectiveCostPerSmsBdt()),
    providerCostPaisa: SMS_UNIT_COST_PAISA,
    providerRechargeChargePercent: SMS_PROVIDER_RECHARGE_CHARGE_PERCENT,
    providerEffectiveCostPaisa: SMS_UNIT_COST_PAISA * (1 + SMS_PROVIDER_RECHARGE_CHARGE_PERCENT / 100),
    centralTopupCount: centralTopups.size,
    centralTopupPaidBdt,
    centralTopupSms,
    financials,
    centralTopups: centralTopups.docs.slice(0, 10).map((doc) => publicPlatformSmsTopup(doc.id, doc.data() || {})),
    providerError,
    dlrSync,
    eventWindowTruncated: events.size === MAX_PLATFORM_SMS_EVENTS,
    // Every institute is returned so root can reconcile bought, used and
    // remaining credits institute-by-institute. The mobile UI is responsible
    // for search/filtering rather than silently hiding lower-usage rows.
    institutes: [...rows.values()].sort((a, b) => a.instituteName.localeCompare(b.instituteName)),
    recentMessages: events.docs.slice(0, MAX_PLATFORM_SMS_AUDIT_ROWS).map((doc) => {
      const message = publicSmsMessage(doc.id, doc.data() || {});
      const institute = rows.get(message.instituteId);
      return {
        ...message,
        instituteName: institute ? institute.instituteName : message.instituteId || "Unknown institute",
      };
    }),
    recentMessagesTruncated: events.size > MAX_PLATFORM_SMS_AUDIT_ROWS,
  };
}

function publicSmsMessage(id, data) {
  return {
    messageId: id,
    instituteId: typeof data.instituteId === "string" ? data.instituteId : "",
    recipient: typeof data.recipient === "string" ? data.recipient : "",
    purpose: typeof data.purpose === "string" ? data.purpose : "",
    messageBody: typeof data.messageBody === "string" ? data.messageBody : "",
    channel: SMS_MESSAGE_CHANNELS.has(data.channel) ? data.channel : "carrier",
    status: SMS_MESSAGE_STATUSES.has(data.status) ? data.status : "sent",
    providerStatus: typeof data.providerStatus === "string" ? data.providerStatus : "",
    // Carrier hand-offs have no BatchFee credit debit; server rows carry the
    // exact billable segment count. Legacy records without this field remain
    // readable but are marked as zero known server credits.
    credits: Number.isInteger(data.credits) && data.credits >= 0 ? data.credits : 0,
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
    messageBody: optionalString(message, "messageBody", 480),
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
        messageBody: row.messageBody,
        channel,
        status,
        credits: 0,
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
    const wallet = walletDefaults(instituteSnap.data() || {}, now);
    const credits = Number.isInteger(current.credits) && current.credits > 0 ? current.credits : 1;
    const messageUsageKeys = dhakaUsageKeys(safeMillis(current.createdAtMs, now));
    const currentUsageKeys = dhakaUsageKeys(now);
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
      sms_used_today: messageUsageKeys.dayKey === currentUsageKeys.dayKey
        ? Math.max(0, wallet.sms_used_today - credits) : wallet.sms_used_today,
      sms_usage_day_key: currentUsageKeys.dayKey,
      sms_used_this_month: messageUsageKeys.monthKey === currentUsageKeys.monthKey
        ? Math.max(0, wallet.sms_used_this_month - credits) : wallet.sms_used_this_month,
      sms_usage_month_key: currentUsageKeys.monthKey,
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

    const wallet = walletDefaults(instituteSnap.data() || {}, now);
    if (wallet.sms_send_method !== "server") {
      throw new HttpsError("failed-precondition", "Select BatchFee Server as the SMS sending method first.");
    }
    if (wallet.sms_balance < totalCredits) {
      throw new HttpsError("resource-exhausted", `Insufficient SMS balance. Required ${totalCredits}, available ${wallet.sms_balance}.`);
    }
    tx.update(instituteRef, {
      sms_balance: wallet.sms_balance - totalCredits,
      total_sms_used: wallet.total_sms_used + totalCredits,
      sms_used_today: wallet.sms_used_today + totalCredits,
      sms_usage_day_key: wallet.sms_usage_day_key,
      sms_used_this_month: wallet.sms_used_this_month + totalCredits,
      sms_usage_month_key: wallet.sms_usage_month_key,
    });
    rows.forEach((row, index) => tx.create(refs[index], {
      instituteId: actor.instituteId,
      recipient: row.recipient,
      targetKey: row.targetKey,
      purpose: row.purpose,
      messageBody: row.message,
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

function createPlatformSmsAnalyticsHandler({ db, smsProvider }) {
  return async (request) => platformSmsAnalytics({ db, request, smsProvider });
}

function createTenantSmsDeliveryRefreshHandler({ db, smsProvider }) {
  return async (request) => refreshMySmsDelivery({ db, request, smsProvider });
}

function createPlatformSmsTopupHandler({ db }) {
  return async (request) => recordPlatformSmsTopup({ db, request });
}

function emptySmsReportCounts() {
  return { sent: 0, delivered: 0, pending: 0, failed: 0, total: 0, credits: 0 };
}

function addSmsToReportCounts(counts, data) {
  const status = SMS_MESSAGE_STATUSES.has(data.status) ? data.status : "sent";
  const credits = Number.isInteger(data.credits) && data.credits >= 0 ? data.credits : 0;
  counts.total += 1;
  counts.credits += credits;
  counts[status] += 1;
}

/**
 * Owner-scoped message history. The details list is intentionally capped to
 * keep the app responsive; the same response exposes whether the historical
 * statistics were capped, rather than silently presenting incomplete data.
 */
async function listSmsReport({ db, request, smsProvider = null }) {
  const actor = await resolveTenantActor(db, request.auth);
  // Keep older app builds correct too: their existing list_sms_report request
  // refreshes the gateway DLR before reading the ledger. The provider result is
  // still server-owned; a client never supplies a delivery status.
  await syncPendingZendDeliveryForInstitute({
    db,
    smsProvider,
    instituteId: actor.instituteId,
    now: Date.now(),
  });
  const snapshot = await db.collection("institutes").doc(actor.instituteId)
    .collection("sms_messages")
    .orderBy("createdAtMs", "desc")
    .limit(MAX_SMS_REPORT_METRICS + 1)
    .get();
  const hasMoreHistory = snapshot.size > MAX_SMS_REPORT_METRICS;
  const history = snapshot.docs.slice(0, MAX_SMS_REPORT_METRICS);
  const periods = {
    today: emptySmsReportCounts(),
    week: emptySmsReportCounts(),
    month: emptySmsReportCounts(),
    lifetime: emptySmsReportCounts(),
  };
  const now = Date.now();
  for (const doc of history) {
    const data = doc.data() || {};
    for (const period of financialPeriodNames(safeMillis(data.createdAtMs), now)) {
      addSmsToReportCounts(periods[period], data);
    }
  }
  return {
    // Kept for older clients. New clients use the explicit period object.
    counts: periods.lifetime,
    periods,
    historyTruncated: hasMoreHistory,
    detailRowsTruncated: history.length > MAX_SMS_REPORT_ROWS,
    messages: history.slice(0, MAX_SMS_REPORT_ROWS).map((doc) => publicSmsMessage(doc.id, doc.data() || {})),
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
  if (options.providerStatus != null) patch.providerStatus = optionalString({ providerStatus: options.providerStatus }, "providerStatus", 32);
  const ref = db.collection("institutes").doc(instituteId).collection("sms_messages").doc(messageId);
  await ref.update(patch);
}

function createSmsWalletHandler({ db, smsProvider = null }) {
  return async (request) => {
    const action = requiredString(request.data, "action", 64);
    const operationId = requiredString(request.data, "operationId", 128);
    if (!SMS_WALLET_ACTIONS.has(action) || !validOperationId(operationId)) {
      throw new HttpsError("invalid-argument", "Invalid SMS wallet operation.");
    }
    const now = Date.now();
    if (action === "get_wallet") return getWallet({ db, request, now });
    if (action === "set_send_method") return setSendMethod({ db, request, operationId, now });
    if (action === "list_packages") return listPackages({ db, request });
    if (action === "submit_recharge_request") return submitRechargeRequest({ db, request, operationId, now });
    if (action === "list_my_recharge_requests") return listMyRechargeRequests({ db, request });
    if (action === "list_recharge_requests") return listRechargeRequests({ db, request });
    if (action === "review_recharge_request") return reviewRechargeRequest({ db, request, operationId, now });
    if (action === "record_sms_batch") return recordSmsBatch({ db, request, operationId, now });
    if (action === "list_sms_report") return listSmsReport({ db, request, smsProvider });
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
  createTenantSmsDeliveryRefreshHandler,
  createPlatformSmsAnalyticsHandler,
  createPlatformSmsTopupHandler,
  platformSmsAnalytics,
  refreshMySmsDelivery,
  recordPlatformSmsTopup,
  createSmsWalletHandler,
  updateSmsMessageStatus,
  walletDefaults,
  rechargeQuote,
  publicPackage,
  publicRechargeRequest,
  publicSmsMessage,
  sendServerSmsBatch,
  smsCreditCount,
  dhakaUsageKeys,
};
