"use strict";

// Smart Due Automation & SMS Credit Control.
//
// Policy:      institutes/{instituteId}/due_automation/policy
// History:     institutes/{instituteId}/due_reminders/{reminderId}
// Run summary: institutes/{instituteId}/due_automation_runs/{dayKey}
//
// A Cloud Scheduler sweep runs hourly (Asia/Dhaka) and never relies on the
// owner's phone being online. Every send is credit-checked inside a Firestore
// transaction against the server-authoritative SMS wallet; insufficient
// credit records a first-class "skipped" row instead of silently dropping.

const { HttpsError } = require("firebase-functions/v2/https");
const { createHash } = require("node:crypto");
const { smsCreditCount, walletDefaults, dhakaUsageKeys } = require("./smsWallet");

const DUE_AUTOMATION_ACTIONS = new Set(["get_state", "save_policy", "preview_estimate", "list_history"]);
const VIEWER_ACTIONS = new Set(["get_state", "preview_estimate", "list_history"]);
const CHANNELS = new Set(["sms", "whatsapp"]);
const FEE_TYPE_FAMILIES = new Set(["monthly_fee", "admission_fee", "course_fee", "exam_fee"]);
const REMINDER_STATUSES = new Set(["queued", "sent", "pending", "failed", "skipped"]);

const MAX_BEFORE_AFTER_ENTRIES = 5;
const MAX_EXCLUSIONS = 300;
const MAX_DAILY_SMS_LIMIT = 10000;
const MAX_HISTORY_ROWS = 200;
const MAX_PREVIEW_ROWS = 500;
const MAX_RECENT_REMINDERS = 30;
const MAX_RUN_SUMMARIES = 30;
const DEDUPE_WINDOW_DAYS = 60;
const INSTITUTE_CONCURRENCY = 3;
const DAY_MS = 86400000;
// Transient conditions that a later sweep may retry (e.g. after a top-up).
const RETRYABLE_SKIP_REASONS = new Set(["insufficient_credit", "daily_limit_reached", "send_method_not_server"]);
const STUCK_QUEUED_MS = 3 * 3600 * 1000;

const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const DEFAULT_BEFORE_DAYS = [7, 3, 1];
const DEFAULT_AFTER_DAYS = [3, 7, 15];

const DEFAULT_DUE_TEMPLATE = [
  "Dear Guardian,",
  "",
  "{studentName} has a pending fee of BDT {amount} for {period}.",
  "",
  "Please pay at your earliest convenience.",
  "",
  "- {instituteName}",
  "Contact: {instituteContact}",
].join("\n");

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------

function requiredString(data, field, maxLength = 128) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  return value;
}

function safeMillis(value, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? Math.floor(numeric) : fallback;
}

function dedupeHash(...parts) {
  return createHash("sha256").update(parts.filter((part) => part != null).map(String).join("|")).digest("hex").slice(0, 24);
}

function dedupeKeyFor({ studentId, feePeriod, trigger, channel }) {
  return dedupeHash("due", studentId, feePeriod, trigger, channel);
}

function dhakaParts(now = Date.now()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Dhaka",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    hour12: false,
  }).formatToParts(new Date(now));
  const value = (type) => parts.find((part) => part.type === type)?.value || "";
  return {
    year: Number(value("year")),
    month: Number(value("month")) - 1,
    day: Number(value("day")),
    hour: Number(value("hour")),
  };
}

function dayKeyMinusDays(dayKey, days) {
  const [year, month, day] = dayKey.split("-").map(Number);
  const ms = Date.UTC(year, month - 1, day) - days * DAY_MS;
  const date = new Date(ms);
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}

function dayNumberForKey(key) {
  const [year, month, day] = String(key).split("-").map(Number);
  return Date.UTC(year, month - 1, day) / DAY_MS;
}

function dhakaDateLabel(now = Date.now()) {
  const parts = dhakaParts(now);
  return `${parts.day} ${MONTH_NAMES[parts.month]} ${parts.year}`;
}

function formatTaka(value) {
  const amount = Math.round((Number(value) || 0) * 100) / 100;
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2);
}

function mapWithConcurrency(items, limit, mapper) {
  const results = new Array(items.length);
  let cursor = 0;
  async function worker() {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await mapper(items[index], index);
    }
  }
  return Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker)).then(() => results);
}

// ---------------------------------------------------------------------------
// Monthly due calculator port (mirrors MonthlyDueCalculator.kt)
// ---------------------------------------------------------------------------

function periodKeyOf(period) {
  const cleaned = String(period || "").trim();
  const month = MONTH_NAMES.findIndex((name) => cleaned.toLowerCase().startsWith(name.toLowerCase()));
  const yearMatch = /\d{4}/.exec(cleaned);
  const year = yearMatch ? Number(yearMatch[0]) : NaN;
  return month >= 0 && Number.isInteger(year) ? year * 12 + month : null;
}

function periodForMs(ms) {
  if (!ms || ms <= 0) return "";
  const parts = dhakaParts(ms);
  return `${MONTH_NAMES[parts.month]} ${parts.year}`;
}

/** Start of a billing month in Dhaka time (Dhaka midnight as UTC ms). */
function periodStartMs(period) {
  const key = periodKeyOf(period);
  if (key == null) return 0;
  const year = Math.floor(key / 12);
  const month = key % 12;
  return Date.UTC(year, month, 1) - 6 * 3600 * 1000;
}

/** End of the last instant of a billing month (Dhaka time as UTC ms). */
function monthEndMs(period) {
  const start = periodStartMs(period);
  if (!start) return 0;
  // periodStartMs returns Dhaka midnight of the first day (UTC-6). The next
  // Dhaka midnight minus one millisecond is the true final instant of the
  // billing month — advancing the UTC month here lands on the correct
  // boundary because the Dhaka offset is constant (+06:00, no DST).
  const nextStart = start + 6 * 3600 * 1000; // Dhaka midnight as its naive date
  const naive = new Date(nextStart);
  const next = Date.UTC(naive.getUTCFullYear(), naive.getUTCMonth() + 1, 1) - 6 * 3600 * 1000;
  return next - 1;
}

function billingPeriodsCoveredBy(feePeriod) {
  const text = String(feePeriod || "");
  const regex = /\b([a-zA-Z]{3,9})\s+(\d{4})\b/g;
  const keys = [];
  let match;
  while ((match = regex.exec(text)) !== null) {
    const month = MONTH_NAMES.indexOf(match[1][0].toUpperCase() + match[1].slice(1).toLowerCase());
    const year = Number(match[2]);
    if (month >= 0 && Number.isInteger(year)) keys.push(year * 12 + month);
  }
  if (keys.length === 0) return [];
  const first = keys[0];
  const last = keys[keys.length - 1];
  if (last < first || last - first > 35) return [];
  const periods = [];
  for (let key = first; key <= last; key += 1) {
    periods.push(`${MONTH_NAMES[key % 12]} ${Math.floor(key / 12)}`);
  }
  return periods;
}

function isMonthlyFeeType(feeType) {
  return [
    "monthly", "monthly_fee", "monthly fee",
    "advance", "advance_fee", "advance fee",
    "due", "due_fee", "due fee",
    "running_month", "running month",
    "mixed_period", "mixed period",
    "overdue",
  ].includes(String(feeType || "").trim().toLowerCase());
}

function isPastMonth(period, now = Date.now()) {
  const key = periodKeyOf(period);
  if (key == null) return false;
  const parts = dhakaParts(now);
  return key < parts.year * 12 + parts.month;
}

function firstMonthBillableDays(ms) {
  if (!ms || ms <= 0) return 0;
  const day = Math.min(dhakaParts(ms).day, 30);
  return Math.max(1, 31 - day);
}

function calculateFirstMonthFee(monthlyFeeAmount, ms) {
  if (monthlyFeeAmount <= 0 || !ms || ms <= 0) return 0;
  return Math.round((monthlyFeeAmount / 30) * firstMonthBillableDays(ms));
}

function parseCustomFeePolicyTimeline(value) {
  const raw = String(value || "");
  if (!raw) return [];
  return raw.split("|").map((piece) => {
    const [periodPart, amountPart] = piece.split("=", 2);
    const period = (periodPart || "").trim();
    if (periodKeyOf(period) == null) return null;
    const amount = (amountPart || "").trim();
    if (amount.toUpperCase() === "BATCH") return { period, amount: null };
    const numeric = Number(amount);
    return numeric > 0 ? { period, amount: numeric } : null;
  }).filter(Boolean).sort((a, b) => (periodKeyOf(a.period) || 0) - (periodKeyOf(b.period) || 0));
}

function customMonthlyFeeForPeriod({ period, customMonthlyFeeAmount, customFeeEffectiveFromPeriod, customFeePolicyTimeline }) {
  const targetKey = periodKeyOf(period);
  if (targetKey == null) return null;
  const timeline = parseCustomFeePolicyTimeline(customFeePolicyTimeline);
  if (timeline.length > 0) {
    const applicable = [...timeline].reverse().find((entry) => (periodKeyOf(entry.period) ?? Number.MAX_SAFE_INTEGER) <= targetKey);
    return applicable ? applicable.amount : null;
  }
  const legacyApplies = customMonthlyFeeAmount != null && customMonthlyFeeAmount > 0 &&
    typeof customFeeEffectiveFromPeriod === "string" && customFeeEffectiveFromPeriod &&
    (periodKeyOf(customFeeEffectiveFromPeriod) ?? Number.MAX_SAFE_INTEGER) <= targetKey;
  return legacyApplies ? customMonthlyFeeAmount : null;
}

function monthlyFeeAmountForPeriod({ period, monthlyFeeAmount, firstMonthFeePeriod, firstMonthFeeAmount, customMonthlyFeeAmount, customFeeEffectiveFromPeriod, customFeePolicyTimeline, firstMonthStartDateMs }) {
  const customForPeriod = customMonthlyFeeForPeriod({ period, customMonthlyFeeAmount, customFeeEffectiveFromPeriod, customFeePolicyTimeline });
  const isFirstPeriod = typeof firstMonthFeePeriod === "string" && firstMonthFeePeriod &&
    firstMonthFeeAmount != null && period.toLowerCase() === firstMonthFeePeriod.toLowerCase();
  if (!isFirstPeriod) return customForPeriod != null ? customForPeriod : monthlyFeeAmount;
  if (customForPeriod == null) return firstMonthFeeAmount;
  if (firstMonthStartDateMs && firstMonthStartDateMs > 0) return calculateFirstMonthFee(customForPeriod, firstMonthStartDateMs);
  return Math.round((firstMonthFeeAmount / monthlyFeeAmount) * customForPeriod);
}

/**
 * Port of MonthlyDueCalculator.computeMonthlyOutstandingItems. Returns the
 * unpaid virtual months for one enrollment; never mutates any document.
 */
function computeMonthlyOutstandingItems({ billingStartMs, monthlyFeeAmount, existingMonthlyFees, firstMonthFeePeriod, firstMonthFeeAmount, customMonthlyFeeAmount, customFeeEffectiveFromPeriod, customFeePolicyTimeline, billingEndedAtMs, now }) {
  if (!billingStartMs || billingStartMs <= 0 || monthlyFeeAmount <= 0) return [];
  const startKey = periodKeyOf(periodForMs(billingStartMs));
  const nowParts = dhakaParts(now);
  let endKey = nowParts.year * 12 + nowParts.month;
  if (billingEndedAtMs && billingEndedAtMs > 0) {
    const endedKey = periodKeyOf(periodForMs(billingEndedAtMs));
    if (endedKey != null && endedKey < endKey) endKey = endedKey;
  }
  if (startKey == null || startKey > endKey) return [];

  const admissionPeriod = periodForMs(billingStartMs);
  const resolvedFirstPeriod = typeof firstMonthFeePeriod === "string" &&
    firstMonthFeePeriod.toLowerCase() === admissionPeriod.toLowerCase()
    ? firstMonthFeePeriod : admissionPeriod;
  const resolvedFirstAmount = firstMonthFeeAmount != null &&
    typeof firstMonthFeePeriod === "string" &&
    firstMonthFeePeriod.toLowerCase() === resolvedFirstPeriod.toLowerCase()
    ? firstMonthFeeAmount : calculateFirstMonthFee(monthlyFeeAmount, billingStartMs);

  const covered = new Set();
  for (const fee of existingMonthlyFees) {
    for (const period of billingPeriodsCoveredBy(fee.feePeriod)) covered.add(period);
  }

  const items = [];
  for (let key = startKey; key < endKey; key += 1) {
    const period = `${MONTH_NAMES[key % 12]} ${Math.floor(key / 12)}`;
    if (covered.has(period)) continue;
    const required = monthlyFeeAmountForPeriod({
      period,
      monthlyFeeAmount,
      firstMonthFeePeriod: resolvedFirstPeriod,
      firstMonthFeeAmount: resolvedFirstAmount,
      customMonthlyFeeAmount,
      customFeeEffectiveFromPeriod,
      customFeePolicyTimeline,
      firstMonthStartDateMs: billingStartMs,
    });
    if (required > 0) items.push({ period, amount: required });
  }
  return items;
}

/** Port of MonthlyDueCalculator.isMonthlyFeeWithinEnrollmentWindow. */
function isMonthlyFeeWithinEnrollmentWindow({ feePeriod, studentAdmissionDateMs, enrollmentJoinedAtMs, firstMonthFeePeriod, billingEndedAtMs }) {
  const startMs = typeof firstMonthFeePeriod === "string" && periodStartMs(firstMonthFeePeriod)
    ? periodStartMs(firstMonthFeePeriod)
    : (studentAdmissionDateMs > 0 ? studentAdmissionDateMs : (enrollmentJoinedAtMs > 0 ? enrollmentJoinedAtMs : 0));
  const startKey = startMs > 0 ? periodKeyOf(periodForMs(startMs)) : null;
  if (startKey == null) return false;
  const endKey = billingEndedAtMs && billingEndedAtMs > 0 ? periodKeyOf(periodForMs(billingEndedAtMs)) : null;
  const coveredKeys = billingPeriodsCoveredBy(feePeriod).map(periodKeyOf);
  return coveredKeys.length > 0 && coveredKeys.every((key) =>
    key >= startKey && (endKey == null || key < endKey));
}

// ---------------------------------------------------------------------------
// Policy parsing / validation
// ---------------------------------------------------------------------------

function policyDefaults(raw) {
  const data = raw && typeof raw === "object" ? raw : {};
  const listOf = (value, fallback) => Array.isArray(value)
    ? value.filter((entry) => Number.isInteger(entry) && entry >= 1 && entry <= 120)
    : [...fallback];
  const strings = (value) => Array.isArray(value)
    ? [...new Set(value.filter((entry) => typeof entry === "string" && entry.trim()).map((entry) => entry.trim()))]
    : [];
  const channels = strings(data.channels).filter((channel) => CHANNELS.has(channel));
  return {
    enabled: data.enabled === true,
    beforeDueDays: listOf(data.beforeDueDays, DEFAULT_BEFORE_DAYS),
    sendOnDueDay: data.sendOnDueDay !== false,
    afterDueDays: listOf(data.afterDueDays, DEFAULT_AFTER_DAYS),
    channels: channels.length > 0 ? channels : ["sms"],
    feeTypes: strings(data.feeTypes).filter((family) => FEE_TYPE_FAMILIES.has(family)),
    excludedStudentIds: strings(data.excludedStudentIds),
    excludedBatchIds: strings(data.excludedBatchIds),
    sendWindowStartHour: Number.isInteger(data.sendWindowStartHour) ? data.sendWindowStartHour : 9,
    sendWindowEndHour: Number.isInteger(data.sendWindowEndHour) ? data.sendWindowEndHour : 19,
    dailySmsLimit: Number.isInteger(data.dailySmsLimit) ? data.dailySmsLimit : 0,
    templateId: typeof data.templateId === "string" ? data.templateId.trim() : "",
    lastRunDayKey: typeof data.lastRunDayKey === "string" ? data.lastRunDayKey : "",
    lastRunSentCount: Number.isInteger(data.lastRunSentCount) ? data.lastRunSentCount : 0,
    lastRunAtMs: safeMillis(data.lastRunAtMs),
    updatedAtMs: safeMillis(data.updatedAtMs),
  };
}

function parseDayList(value, fallback) {
  if (value == null) return [...fallback];
  if (!Array.isArray(value) || value.length > MAX_BEFORE_AFTER_ENTRIES) {
    throw new HttpsError("invalid-argument", `A reminder day list holds at most ${MAX_BEFORE_AFTER_ENTRIES} entries.`);
  }
  const entries = [...new Set(value.map((entry) => Number(entry)))]
    .filter((entry) => Number.isInteger(entry) && entry >= 1 && entry <= 120);
  if (entries.length !== new Set(value.map((entry) => Number(entry))).size) {
    throw new HttpsError("invalid-argument", "Reminder days must be whole numbers between 1 and 120.");
  }
  return entries;
}

function parseStringList(value, field, maximum = MAX_EXCLUSIONS) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > maximum) {
    throw new HttpsError("invalid-argument", `"${field}" holds at most ${maximum} entries.`);
  }
  const entries = [...new Set(value.map((entry) => typeof entry === "string" ? entry.trim() : ""))];
  if (entries.some((entry) => !entry || entry.length > 128)) {
    throw new HttpsError("invalid-argument", `Invalid "${field}" entries.`);
  }
  return entries;
}

function validatePolicyInput(value) {
  const data = value && typeof value === "object" ? value : {};
  const channels = parseStringList(data.channels, "channels", 2).filter((channel) => CHANNELS.has(channel));
  if (channels.length === 0) throw new HttpsError("invalid-argument", "Select at least one reminder channel (SMS or WhatsApp).");
  const feeTypes = parseStringList(data.feeTypes, "feeTypes", FEE_TYPE_FAMILIES.size)
    .filter((family) => FEE_TYPE_FAMILIES.has(family));
  const startHour = Number(data.sendWindowStartHour);
  const endHour = Number(data.sendWindowEndHour);
  if (!Number.isInteger(startHour) || startHour < 0 || startHour > 22) {
    throw new HttpsError("invalid-argument", "The send window start hour must be 0-22.");
  }
  if (!Number.isInteger(endHour) || endHour < 1 || endHour > 23 || endHour <= startHour) {
    throw new HttpsError("invalid-argument", "The send window end hour must be later than the start hour.");
  }
  const dailySmsLimit = data.dailySmsLimit == null || data.dailySmsLimit === "" ? 0 : Number(data.dailySmsLimit);
  if (!Number.isInteger(dailySmsLimit) || dailySmsLimit < 0 || dailySmsLimit > MAX_DAILY_SMS_LIMIT) {
    throw new HttpsError("invalid-argument", `The daily SMS limit must be 0-${MAX_DAILY_SMS_LIMIT}.`);
  }
  const templateId = typeof data.templateId === "string" ? data.templateId.trim() : "";
  if (templateId.length > 128) throw new HttpsError("invalid-argument", "Invalid template selection.");
  return {
    enabled: data.enabled === true,
    beforeDueDays: parseDayList(data.beforeDueDays, DEFAULT_BEFORE_DAYS),
    sendOnDueDay: data.sendOnDueDay !== false,
    afterDueDays: parseDayList(data.afterDueDays, DEFAULT_AFTER_DAYS),
    channels,
    feeTypes,
    excludedStudentIds: parseStringList(data.excludedStudentIds, "excludedStudentIds"),
    excludedBatchIds: parseStringList(data.excludedBatchIds, "excludedBatchIds"),
    sendWindowStartHour: startHour,
    sendWindowEndHour: endHour,
    dailySmsLimit,
    templateId,
  };
}

function publicPolicy(raw) {
  const policy = policyDefaults(raw);
  return {
    enabled: policy.enabled,
    beforeDueDays: policy.beforeDueDays,
    sendOnDueDay: policy.sendOnDueDay,
    afterDueDays: policy.afterDueDays,
    channels: policy.channels,
    feeTypes: policy.feeTypes,
    excludedStudentIds: policy.excludedStudentIds,
    excludedBatchIds: policy.excludedBatchIds,
    sendWindowStartHour: policy.sendWindowStartHour,
    sendWindowEndHour: policy.sendWindowEndHour,
    dailySmsLimit: policy.dailySmsLimit,
    templateId: policy.templateId,
    lastRunDayKey: policy.lastRunDayKey,
    lastRunSentCount: policy.lastRunSentCount,
    lastRunAtMs: policy.lastRunAtMs,
    updatedAtMs: policy.updatedAtMs,
  };
}

function publicReminder(id, data) {
  return {
    reminderId: id,
    studentId: typeof data.studentId === "string" ? data.studentId : "",
    studentName: typeof data.studentName === "string" ? data.studentName : "",
    recipient: typeof data.recipient === "string" ? data.recipient : "",
    batchName: typeof data.batchName === "string" ? data.batchName : "",
    feePeriods: Array.isArray(data.feePeriods) ? data.feePeriods : [],
    dueAmount: Number(data.dueAmount) || 0,
    dueDateMs: safeMillis(data.dueDateMs),
    trigger: typeof data.trigger === "string" ? data.trigger : "",
    channel: CHANNELS.has(data.channel) ? data.channel : "sms",
    status: REMINDER_STATUSES.has(data.status) ? data.status : "queued",
    skipReason: typeof data.skipReason === "string" ? data.skipReason : "",
    failureReason: typeof data.failureReason === "string" ? data.failureReason : "",
    credits: Number.isInteger(data.credits) ? data.credits : 0,
    createdAtMs: safeMillis(data.createdAtMs),
    sentAtMs: safeMillis(data.sentAtMs),
  };
}

// ---------------------------------------------------------------------------
// Actor resolution (mirrors the SMS wallet's owner/staff gate)
// ---------------------------------------------------------------------------

async function authenticatedUser(db, auth) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const snap = await db.collection("app_users").doc(auth.uid).get();
  const user = snap.exists ? snap.data() || {} : {};
  if (user.status === "suspended") throw new HttpsError("permission-denied", "This account is inactive.");
  return { uid: auth.uid, user };
}

function normalizedTenantRole(user) {
  const role = typeof user.role === "string" ? user.role : "";
  if (["InstituteOwner", "owner", "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(role)) return "owner";
  if (role === "Staff") return "staff";
  return "";
}

function permissionSet(value) {
  if (Array.isArray(value)) return new Set(value.map(String).map((item) => item.trim()).filter(Boolean));
  if (typeof value !== "string") return new Set();
  return new Set(value.split(",").map((item) => item.trim()).filter(Boolean));
}

async function resolveActor(db, auth) {
  const actor = await authenticatedUser(db, auth);
  const role = normalizedTenantRole(actor.user);
  if (!role) throw new HttpsError("permission-denied", "This account cannot manage due automation.");

  let instituteId = typeof actor.user.instituteId === "string" ? actor.user.instituteId.trim() : "";
  let instituteSnap = instituteId ? await db.collection("institutes").doc(instituteId).get() : null;
  if (role === "owner" && (!instituteSnap || !instituteSnap.exists)) {
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

function assertCanViewAutomation(actor) {
  if (actor.role === "owner") return;
  if (actor.role === "staff" && permissionSet(actor.staff && actor.staff.permissions).has("send_due_message")) return;
  throw new HttpsError("permission-denied", "Due message permission is required.");
}

// ---------------------------------------------------------------------------
// Due item computation
// ---------------------------------------------------------------------------

function feeTypeFamily(feeType) {
  if (isMonthlyFeeType(feeType)) return "monthly_fee";
  const normalized = String(feeType || "").trim().toLowerCase().replace(/\s+/g, "_");
  return FEE_TYPE_FAMILIES.has(normalized) ? normalized : "other";
}

function feeFamilyAllowed(policy, feeType) {
  if (policy.feeTypes.length === 0) return true;
  return policy.feeTypes.includes(feeTypeFamily(feeType));
}

function isMessagableStudent(student) {
  if (!student || student.archivedAtMs != null) return false;
  if (["archived", "inactive", "blocked"].includes(student.status)) return false;
  return typeof student.phone === "string" && student.phone.trim();
}

/**
 * Builds the complete due list exactly like the app's calculateDueFeeReport:
 * materialized one-time fees, materialized monthly installments, and the
 * virtual monthly / admission / course charges that have no fee row yet.
 */
function computeDueItems({ fees, studentsById, batchesById, enrollmentsByStudent, policy, now }) {
  const items = [];

  // 1. Outstanding one-time charges (non-monthly ledger rows).
  for (const fee of fees) {
    if (fee.cancelledAtMs != null) continue;
    if (Number(fee.dueAmount || 0) <= 0) continue;
    if (isMonthlyFeeType(fee.feeType)) continue;
    if (!feeFamilyAllowed(policy, fee.feeType)) continue;
    items.push({
      feeId: fee.id || "",
      studentId: fee.studentId,
      batchId: fee.batchId || "",
      feePeriods: [fee.feePeriod || "Fee"],
      dueAmount: Number(fee.dueAmount),
      dueDateMs: safeMillis(fee.dueDateMs),
      kind: "fee",
      feeType: fee.feeType,
    });
  }

  // 2. Materialized monthly installments that are already due.
  for (const fee of fees) {
    if (fee.cancelledAtMs != null) continue;
    if (Number(fee.dueAmount || 0) <= 0) continue;
    if (!isMonthlyFeeType(fee.feeType)) continue;
    const covered = billingPeriodsCoveredBy(fee.feePeriod);
    const lastPeriod = covered[covered.length - 1];
    if (!lastPeriod || !isPastMonth(lastPeriod, now)) continue;
    if (!feeFamilyAllowed(policy, fee.feeType)) continue;
    const student = studentsById[fee.studentId];
    if (!student) continue;
    const enrollments = (enrollmentsByStudent[fee.studentId] || []).filter((entry) => entry.batchId === fee.batchId);
    const withinWindow = enrollments.some((entry) => isMonthlyFeeWithinEnrollmentWindow({
      feePeriod: fee.feePeriod,
      studentAdmissionDateMs: student.admissionDateMs,
      enrollmentJoinedAtMs: entry.joinedAtMs,
      firstMonthFeePeriod: entry.firstMonthFeePeriod,
      billingEndedAtMs: entry.leftAtMs,
    }));
    if (!withinWindow) continue;
    items.push({
      feeId: fee.id || "",
      studentId: fee.studentId,
      batchId: fee.batchId || "",
      feePeriods: [fee.feePeriod || lastPeriod],
      dueAmount: Number(fee.dueAmount),
      dueDateMs: monthEndMs(lastPeriod),
      kind: "monthly",
      feeType: fee.feeType,
    });
  }

  // 3. Virtual monthly arrears, admission and course charges per enrollment.
  for (const student of Object.values(studentsById)) {
    if (!isMessagableStudent(student)) continue;
    const enrollments = enrollmentsByStudent[student.id] || [];
    for (const enrollment of enrollments) {
      const batch = batchesById[enrollment.batchId];
      if (!batch) continue;

      if (batch.billingMode !== "course" && Number(batch.monthlyFeeAmount || 0) > 0) {
        if (!feeFamilyAllowed(policy, "monthly_fee")) continue;
        const billingStartMs = typeof enrollment.firstMonthFeePeriod === "string" && periodStartMs(enrollment.firstMonthFeePeriod)
          ? periodStartMs(enrollment.firstMonthFeePeriod)
          : (student.admissionDateMs > 0 ? student.admissionDateMs : (enrollment.joinedAtMs > 0 ? enrollment.joinedAtMs : 0));
        const existingMonthly = fees.filter((fee) =>
          fee.cancelledAtMs == null && fee.studentId === student.id &&
          fee.batchId === batch.id && isMonthlyFeeType(fee.feeType));
        const months = computeMonthlyOutstandingItems({
          billingStartMs,
          monthlyFeeAmount: Number(batch.monthlyFeeAmount),
          existingMonthlyFees: existingMonthly,
          firstMonthFeePeriod: enrollment.firstMonthFeePeriod,
          firstMonthFeeAmount: enrollment.firstMonthFeeAmount,
          customMonthlyFeeAmount: enrollment.customMonthlyFeeAmount,
          customFeeEffectiveFromPeriod: enrollment.customFeeEffectiveFromPeriod,
          customFeePolicyTimeline: enrollment.customFeePolicyTimeline,
          billingEndedAtMs: enrollment.leftAtMs,
          now,
        });
        for (const month of months) {
          items.push({
            feeId: "",
            studentId: student.id,
            batchId: batch.id,
            feePeriods: [month.period],
            dueAmount: month.amount,
            dueDateMs: monthEndMs(month.period),
            kind: "monthly",
            feeType: "monthly_fee",
          });
        }
      }

      if (enrollment.status === "active") {
        const admissionExists = fees.some((fee) => fee.cancelledAtMs == null &&
          fee.studentId === student.id && fee.batchId === batch.id &&
          String(fee.feeType || "").toLowerCase() === "admission_fee");
        if (!admissionExists && Number(batch.admissionFeeAmount || 0) > 0 && feeFamilyAllowed(policy, "admission_fee")) {
          items.push({
            feeId: "",
            studentId: student.id,
            batchId: batch.id,
            feePeriods: ["Admission"],
            dueAmount: Number(batch.admissionFeeAmount),
            dueDateMs: safeMillis(enrollment.joinedAtMs) || safeMillis(student.admissionDateMs),
            kind: "admission",
            feeType: "admission_fee",
          });
        }

        const courseExists = fees.some((fee) => fee.cancelledAtMs == null &&
          fee.studentId === student.id && fee.batchId === batch.id &&
          String(fee.feeType || "").toLowerCase() === "course_fee");
        if (!courseExists && batch.billingMode === "course" &&
            Number(batch.courseFeeAmount || 0) > 0 && feeFamilyAllowed(policy, "course_fee")) {
          items.push({
            feeId: "",
            studentId: student.id,
            batchId: batch.id,
            feePeriods: ["Course"],
            dueAmount: Number(batch.courseFeeAmount),
            dueDateMs: safeMillis(batch.startDateMs) || safeMillis(enrollment.joinedAtMs),
            kind: "course",
            feeType: "course_fee",
          });
        }
      }
    }
  }

  return items;
}

/**
 * Escalation-level trigger. Each (student, period, level) fires at most once
 * thanks to the dedupe key: before-due levels 7/3/1 escalate as the due date
 * approaches, overdue levels 3/7/15 escalate after it. Returns null when the
 * item is not inside any configured cohort.
 */
function triggerForDue({ dueDateMs, policy, now }) {
  if (!dueDateMs || dueDateMs <= 0) return null;
  const dueDayKey = dhakaUsageKeys(dueDateMs).dayKey;
  const todayKey = dhakaUsageKeys(now).dayKey;
  const days = dayNumberForKey(dueDayKey) - dayNumberForKey(todayKey);
  if (days >= 0) {
    if (days === 0 && policy.sendOnDueDay) return "due_today";
    const candidates = policy.beforeDueDays.filter((n) => n >= days);
    if (candidates.length > 0) return `before_${Math.min(...candidates)}d`;
    return null;
  }
  const overdue = -days;
  const candidates = policy.afterDueDays.filter((n) => n <= overdue);
  if (candidates.length > 0) return `overdue_${Math.max(...candidates)}d`;
  return null;
}

// ---------------------------------------------------------------------------
// Message building
// ---------------------------------------------------------------------------

function applyTemplate(template, values) {
  let out = String(template || "").trim();
  for (const [key, value] of Object.entries(values)) {
    out = out.split(`{${key}}`).join(value == null ? "" : String(value));
  }
  return out.split(/\r?\n/).map((line) => line.trimEnd()).filter((line) => {
    const trimmed = line.trim();
    if (trimmed === "" || trimmed === "-") return false;
    if (/^contact:\s*$/i.test(trimmed)) return false;
    if (/^[A-Za-z ]+:\s*$/.test(trimmed)) return false;
    return true;
  }).join("\n");
}

async function loadDueTemplate(db, instituteId, policy) {
  const templates = db.collection("institutes").doc(instituteId).collection("reminder_templates");
  if (policy.templateId) {
    const snap = await templates.doc(policy.templateId).get();
    const stored = snap.exists ? String(snap.get("messageTemplate") || "").trim() : "";
    if (stored) return stored;
  }
  const typed = await templates.where("type", "==", "DueFee").limit(1).get();
  const first = typed.docs[0];
  const stored = first ? String(first.get("messageTemplate") || "").trim() : "";
  return stored || DEFAULT_DUE_TEMPLATE;
}

function buildDueMessage({ template, item, institute, now }) {
  return applyTemplate(template || DEFAULT_DUE_TEMPLATE, {
    guardianName: "Guardian",
    studentName: item.studentName || "",
    amount: formatTaka(item.dueAmount),
    period: (item.feePeriods || []).join(", "),
    date: dhakaDateLabel(now),
    instituteName: institute.instituteName || institute.name || "",
    instituteContact: institute.phone || institute.whatsappNumber || "",
  });
}

// ---------------------------------------------------------------------------
// Dispatch: reserve credits, send, settle, refund (auth-less, single message)
// ---------------------------------------------------------------------------

function isRetryableReminder(status, skipReason, createdAtMs, now = Date.now()) {
  if (status === "failed") return true;
  if (status === "skipped" && RETRYABLE_SKIP_REASONS.has(skipReason)) return true;
  // A queued reminder older than a few hours means the process died between
  // reserving credits and calling the provider; retry with a fresh attempt.
  if (status === "queued" && now - safeMillis(createdAtMs, now) > STUCK_QUEUED_MS) return true;
  return false;
}

async function recordSkippedReminder(db, instituteRef, reminderId, base, skipReason, now) {
  await db.runTransaction(async (tx) => {
    const ref = instituteRef.collection("due_reminders").doc(reminderId);
    const snap = await tx.get(ref);
    if (snap.exists) tx.update(ref, { ...base, status: "skipped", skipReason, failureReason: "", updatedAtMs: now });
    else tx.create(ref, { ...base, status: "skipped", skipReason, updatedAtMs: now });
  });
}

async function dispatchDueReminder({ db, smsProvider, instituteId, institute, policyRef, policy, item, trigger, channel, template, now }) {
  const recipient = typeof item.studentPhone === "string" ? item.studentPhone.trim() : "";
  const message = buildDueMessage({ template, item, institute, now });
  const credits = smsCreditCount(message);
  const dedupeKey = dedupeKeyFor({ studentId: item.studentId, feePeriod: (item.feePeriods || []).join(","), trigger, channel });
  const reminderId = `due_${dedupeKey}`;
  const dayKey = dhakaUsageKeys(now).dayKey;
  const instituteRef = db.collection("institutes").doc(instituteId);
  const reminderRef = instituteRef.collection("due_reminders").doc(reminderId);
  // One sms_messages row per attempt; retries of failed/skipped reminders get
  // their own row so the SMS history mirrors every real gateway call.
  const smsRef = instituteRef.collection("sms_messages").doc(`${reminderId}-${now.toString(36)}`);
  const base = {
    instituteId,
    studentId: item.studentId,
    studentName: item.studentName || "",
    recipient,
    batchName: item.batchName || "",
    feePeriods: item.feePeriods || [],
    dueAmount: item.dueAmount,
    dueDateMs: item.dueDateMs,
    trigger,
    channel,
    message,
    credits,
    smsMessageId: smsRef.id,
    dedupeKey,
    runDayKey: dayKey,
    createdAtMs: now,
  };

  // WhatsApp automation needs a WhatsApp Business API provider, which is not
  // wired into the backend yet. Record the skip so the history stays honest.
  if (channel === "whatsapp") {
    await recordSkippedReminder(db, instituteRef, reminderId, base, "whatsapp_provider_unavailable", now);
    return { status: "skipped", reason: "whatsapp_provider_unavailable", credits: 0 };
  }
  if (!recipient) {
    await recordSkippedReminder(db, instituteRef, reminderId, base, "no_phone", now);
    return { status: "skipped", reason: "no_phone", credits: 0 };
  }

  const reservation = await db.runTransaction(async (tx) => {
    const [instituteSnap, policySnap, reminderSnap] = await Promise.all([
      tx.get(instituteRef),
      tx.get(policyRef),
      tx.get(reminderRef),
    ]);
    if (!instituteSnap.exists) throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
    if (reminderSnap.exists &&
        !isRetryableReminder(reminderSnap.get("status"), reminderSnap.get("skipReason"), reminderSnap.get("createdAtMs"), now)) {
      return { replayed: true };
    }
    const wallet = walletDefaults(instituteSnap.data() || {}, now);
    const current = policySnap.exists ? policySnap.data() || {} : {};
    const sentToday = current.lastRunDayKey === dayKey && Number.isInteger(current.lastRunSentCount)
      ? current.lastRunSentCount : 0;
    if (Number.isInteger(current.dailySmsLimit) && current.dailySmsLimit > 0 && sentToday >= current.dailySmsLimit) {
      return { skipped: "daily_limit_reached" };
    }
    if (wallet.sms_send_method !== "server") return { skipped: "send_method_not_server" };
    if (wallet.sms_balance < credits) return { skipped: "insufficient_credit" };

    tx.update(instituteRef, {
      sms_balance: wallet.sms_balance - credits,
      total_sms_used: wallet.total_sms_used + credits,
      sms_used_today: wallet.sms_used_today + credits,
      sms_usage_day_key: wallet.sms_usage_day_key,
      sms_used_this_month: wallet.sms_used_this_month + credits,
      sms_usage_month_key: wallet.sms_usage_month_key,
    });
    tx.create(smsRef, {
      instituteId,
      recipient,
      targetKey: item.studentId,
      purpose: "due_automation",
      messageBody: message,
      channel: "server",
      status: "pending",
      providerStatus: "RESERVED",
      providerMessageId: "",
      credits,
      failureReason: "",
      payloadHash: dedupeHash("sms", recipient, message, "due_automation", item.studentId, credits),
      operationId: reminderId,
      createdByUid: "due-automation",
      createdAtMs: now,
      updatedAtMs: now,
      deliveredAtMs: 0,
    });
    if (reminderSnap.exists) {
      tx.update(reminderRef, { ...base, status: "queued", skipReason: "", failureReason: "", updatedAtMs: now });
    } else {
      tx.create(reminderRef, { ...base, status: "queued" });
    }
    tx.update(policyRef, { lastRunDayKey: dayKey, lastRunSentCount: sentToday + 1, lastRunAtMs: now });
    tx.set(instituteRef.collection("sms_wallet_audit").doc(`${reminderId}-reserve`), {
      instituteId,
      action: "server_sms_reserved",
      smsCount: credits,
      messageCount: 1,
      actorUid: "due-automation",
      operationId: reminderId,
      createdAtMs: now,
    });
    return { reserved: true };
  });

  if (reservation.replayed) return { status: "deduped", credits: 0 };
  if (reservation.skipped) {
    await recordSkippedReminder(db, instituteRef, reminderId, base, reservation.skipped, now);
    return { status: "skipped", reason: reservation.skipped, credits: 0 };
  }

  let providerResult = null;
  let providerError = null;
  try {
    providerResult = await smsProvider.sendTextSms({ number: recipient, message });
  } catch (error) {
    providerError = error;
  }

  return db.runTransaction(async (tx) => {
    const messageSnap = await tx.get(smsRef);
    const current = messageSnap.data() || {};
    if (providerResult && providerResult.accepted) {
      const patch = {
        status: providerResult.status === "pending" ? "pending" : "sent",
        providerStatus: providerResult.providerStatus || "",
        providerMessageId: providerResult.messageId || "",
        failureReason: "",
        updatedAtMs: now,
      };
      tx.update(smsRef, patch);
      tx.update(reminderRef, { status: "sent", sentAtMs: now, updatedAtMs: now });
      return { status: "sent", credits };
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

    if (ambiguous) {
      tx.update(smsRef, { status: "pending", providerStatus, failureReason, updatedAtMs: now });
      tx.update(reminderRef, { status: "pending", failureReason, updatedAtMs: now });
      return { status: "pending", credits };
    }

    const instituteSnap = await tx.get(instituteRef);
    const wallet = walletDefaults(instituteSnap.data() || {}, now);
    const messageUsageKeys = dhakaUsageKeys(safeMillis(current.createdAtMs, now));
    const currentUsageKeys = dhakaUsageKeys(now);
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
    tx.update(smsRef, { status: "failed", providerStatus, failureReason, refundedAtMs: now, updatedAtMs: now });
    tx.update(reminderRef, { status: "failed", failureReason, updatedAtMs: now });
    tx.set(instituteRef.collection("sms_wallet_audit").doc(`${reminderId}-refund`), {
      instituteId,
      action: "server_sms_refund",
      messageId: reminderId,
      smsCount: credits,
      createdAtMs: now,
    });
    return { status: "failed", credits };
  }).catch(() => ({ status: "pending", credits }));
}

/** Mirrors gateway delivery onto reminder rows so history shows delivered/failed. */
async function enrichReminderDelivery(db, instituteRef, rows, limit = 30) {
  if (rows.length === 0) return rows;
  const ids = rows.slice(0, limit).map((row) => row.smsMessageId).filter(Boolean);
  if (ids.length === 0) return rows;
  const snaps = await Promise.all(ids.map((id) => instituteRef.collection("sms_messages").doc(id).get()));
  const byId = new Map(snaps.filter((snap) => snap.exists).map((snap) => [snap.id, snap.data() || {}]));
  return rows.map((row) => {
    if (row.status !== "sent") return row;
    const sms = byId.get(row.smsMessageId);
    if (!sms || !["delivered", "failed"].includes(sms.status)) return row;
    return {
      ...row,
      status: sms.status,
      failureReason: typeof sms.failureReason === "string" ? sms.failureReason : row.failureReason,
    };
  });
}

// ---------------------------------------------------------------------------
// Callable handler
// ---------------------------------------------------------------------------

async function getDueAutomationState({ db, request, now }) {
  const actor = await resolveActor(db, request.auth);
  assertCanViewAutomation(actor);
  const instituteRef = db.collection("institutes").doc(actor.instituteId);
  const policyRef = instituteRef.collection("due_automation").doc("policy");
  const [instituteSnap, policySnap, recentSnap, runsSnap] = await Promise.all([
    instituteRef.get(),
    policyRef.get(),
    instituteRef.collection("due_reminders").orderBy("createdAtMs", "desc").limit(MAX_RECENT_REMINDERS).get(),
    instituteRef.collection("due_automation_runs").orderBy("runAtMs", "desc").limit(MAX_RUN_SUMMARIES).get(),
  ]);
  const institute = instituteSnap.data() || {};
  const wallet = walletDefaults(institute, now);
  const policy = policyDefaults(policySnap.exists ? policySnap.data() : {});
  const dayKey = dhakaUsageKeys(now).dayKey;
  const sentToday = policy.lastRunDayKey === dayKey ? policy.lastRunSentCount : 0;
  return {
    policy: publicPolicy(policy),
    wallet: {
      smsBalance: wallet.sms_balance,
      totalSmsPurchased: wallet.total_sms_purchased,
      totalSmsUsed: wallet.total_sms_used,
      smsUsedToday: wallet.sms_used_today,
      smsUsedThisMonth: wallet.sms_used_this_month,
      smsSendMethod: wallet.sms_send_method,
    },
    estimatedDaysRemaining: estimatedDaysRemaining({ wallet, institute, now }),
    today: {
      sent: sentToday,
      remainingLimit: policy.dailySmsLimit > 0 ? Math.max(0, policy.dailySmsLimit - sentToday) : null,
      remainingCredits: wallet.sms_balance,
    },
    recentReminders: await enrichReminderDelivery(
      db,
      instituteRef,
      recentSnap.docs.map((doc) => publicReminder(doc.id, doc.data() || {})),
    ),
    recentRuns: runsSnap.docs.map((doc) => ({ runId: doc.id, ...doc.data() })),
  };
}

/** Honest estimate: remaining balance divided by the observed daily burn. */
function estimatedDaysRemaining({ wallet, institute, now }) {
  if (wallet.sms_balance <= 0) return 0;
  const dayKey = dhakaUsageKeys(now).dayKey;
  const dayOfMonth = Number(dayKey.slice(8, 10));
  let daily = wallet.sms_used_this_month / Math.max(1, dayOfMonth);
  if (daily < 0.05 && wallet.total_sms_used > 0) {
    const created = safeMillis(institute.createdAtMs) || safeMillis(institute.createdAt);
    const daysActive = created > 0 ? Math.max(1, Math.floor((now - created) / DAY_MS)) : 30;
    daily = wallet.total_sms_used / daysActive;
  }
  if (daily < 0.05) daily = 1;
  return Math.min(365, Math.floor(wallet.sms_balance / daily));
}

async function saveDueAutomationPolicy({ db, request, now }) {
  const actor = await resolveActor(db, request.auth);
  if (actor.role !== "owner") {
    throw new HttpsError("permission-denied", "Only the institute owner can change automation settings.");
  }
  const patch = validatePolicyInput(request.data && request.data.policy);
  const ref = db.collection("institutes").doc(actor.instituteId).collection("due_automation").doc("policy");
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const current = snap.exists ? snap.data() || {} : {};
    tx.set(ref, { ...current, ...patch, updatedAtMs: now, updatedByUid: actor.uid }, { merge: true });
  });
  return { saved: true };
}

async function previewDueAutomation({ db, request, now }) {
  const actor = await resolveActor(db, request.auth);
  assertCanViewAutomation(actor);
  const instituteRef = db.collection("institutes").doc(actor.instituteId);
  const policyRef = instituteRef.collection("due_automation").doc("policy");
  const saved = policyRef.get();
  const data = await loadInstituteDueData(db, actor.instituteId, now);
  const policySnap = await saved;
  const policy = policyDefaults(policySnap.exists ? policySnap.data() : {});
  const matched = await matchDueReminders({ db, instituteId: actor.instituteId, policy, data, now });
  const institute = data.institute;
  const template = await loadDueTemplate(db, actor.instituteId, policy);
  const wallet = walletDefaults(institute, now);
  const smsItems = matched.filter((entry) => entry.channel === "sms").slice(0, MAX_PREVIEW_ROWS);
  const whatsappCount = matched.filter((entry) => entry.channel === "whatsapp").slice(0, MAX_PREVIEW_ROWS).length;
  const estimatedCredits = smsItems.reduce((sum, entry) =>
    sum + smsCreditCount(buildDueMessage({ template, item: entry.item, institute, now })), 0);
  const dayKey = dhakaUsageKeys(now).dayKey;
  const sentToday = policy.lastRunDayKey === dayKey ? policy.lastRunSentCount : 0;
  return {
    matchedCount: matched.length,
    smsCount: smsItems.length,
    whatsappCount,
    estimatedCredits,
    availableCredits: wallet.sms_balance,
    dailySmsLimit: policy.dailySmsLimit,
    remainingTodayCapacity: policy.dailySmsLimit > 0 ? Math.max(0, policy.dailySmsLimit - sentToday) : null,
    sample: matched.slice(0, 20).map((entry) => ({
      studentId: entry.item.studentId,
      studentName: entry.item.studentName,
      batchName: entry.item.batchName,
      feePeriods: entry.item.feePeriods,
      dueAmount: entry.item.dueAmount,
      dueDateMs: entry.item.dueDateMs,
      trigger: entry.trigger,
      channel: entry.channel,
    })),
  };
}

async function listDueAutomationHistory({ db, request }) {
  const actor = await resolveActor(db, request.auth);
  assertCanViewAutomation(actor);
  const instituteRef = db.collection("institutes").doc(actor.instituteId);
  const [remindersSnap, runsSnap] = await Promise.all([
    instituteRef.collection("due_reminders").orderBy("createdAtMs", "desc").limit(MAX_HISTORY_ROWS).get(),
    instituteRef.collection("due_automation_runs").orderBy("runAtMs", "desc").limit(MAX_RUN_SUMMARIES).get(),
  ]);
  return {
    reminders: await enrichReminderDelivery(
      db,
      instituteRef,
      remindersSnap.docs.map((doc) => publicReminder(doc.id, doc.data() || {})),
      MAX_HISTORY_ROWS,
    ),
    runs: runsSnap.docs.map((doc) => ({ runId: doc.id, ...doc.data() })),
    historyTruncated: remindersSnap.size === MAX_HISTORY_ROWS,
  };
}

function createDueAutomationHandler({ db }) {
  return async (request) => {
    const action = requiredString(request.data, "action", 64);
    if (!DUE_AUTOMATION_ACTIONS.has(action)) {
      throw new HttpsError("invalid-argument", "Invalid due automation operation.");
    }
    const now = Date.now();
    if (action === "get_state") return getDueAutomationState({ db, request, now });
    if (action === "save_policy") return saveDueAutomationPolicy({ db, request, now });
    if (action === "preview_estimate") return previewDueAutomation({ db, request, now });
    return listDueAutomationHistory({ db, request });
  };
}

// ---------------------------------------------------------------------------
// Scheduled runner
// ---------------------------------------------------------------------------

async function loadInstituteDueData(db, instituteId, now) {
  const instituteRef = db.collection("institutes").doc(instituteId);
  const [instituteSnap, feesSnap, studentsSnap, batchesSnap, enrollmentsSnap] = await Promise.all([
    instituteRef.get(),
    instituteRef.collection("fees").where("dueAmount", ">", 0).get(),
    instituteRef.collection("students").get(),
    instituteRef.collection("batches").get(),
    instituteRef.collection("batch_students").get(),
  ]);
  const studentsById = {};
  for (const doc of studentsSnap.docs) studentsById[doc.id] = { id: doc.id, ...(doc.data() || {}) };
  const batchesById = {};
  for (const doc of batchesSnap.docs) batchesById[doc.id] = { id: doc.id, ...(doc.data() || {}) };
  const enrollmentsByStudent = {};
  for (const doc of enrollmentsSnap.docs) {
    const entry = { id: doc.id, ...(doc.data() || {}) };
    (enrollmentsByStudent[entry.studentId] = enrollmentsByStudent[entry.studentId] || []).push(entry);
  }
  return {
    institute: instituteSnap.exists ? instituteSnap.data() || {} : null,
    fees: feesSnap.docs.map((doc) => ({ id: doc.id, ...(doc.data() || {}) })),
    studentsById,
    batchesById,
    enrollmentsByStudent,
    now,
  };
}

async function matchDueReminders({ db, instituteId, policy, data, now }) {
  const dayKey = dhakaUsageKeys(now).dayKey;
  const windowStartKey = dayKeyMinusDays(dayKey, DEDUPE_WINDOW_DAYS);
  const recent = await db.collection("institutes").doc(instituteId).collection("due_reminders")
    .where("runDayKey", ">=", windowStartKey).get();
  const seen = new Set(recent.docs
    .filter((doc) => !isRetryableReminder(doc.get("status"), doc.get("skipReason"), doc.get("createdAtMs"), now))
    .map((doc) => doc.get("dedupeKey"))
    .filter(Boolean));

  const excludedStudents = new Set(policy.excludedStudentIds);
  const excludedBatches = new Set(policy.excludedBatchIds);
  const items = computeDueItems({ ...data, policy, now });
  const matched = [];
  for (const item of items) {
    if (excludedStudents.has(item.studentId)) continue;
    if (item.batchId && excludedBatches.has(item.batchId)) continue;
    const trigger = triggerForDue({ dueDateMs: item.dueDateMs, policy, now });
    if (!trigger) continue;
    const student = data.studentsById[item.studentId];
    if (!student) continue;
    item.studentName = typeof student.fullName === "string" ? student.fullName : "";
    item.studentPhone = typeof student.phone === "string" ? student.phone.trim() : "";
    item.batchName = item.batchId && data.batchesById[item.batchId]
      ? data.batchesById[item.batchId].name || "" : "";
    for (const channel of policy.channels) {
      const key = dedupeKeyFor({ studentId: item.studentId, feePeriod: (item.feePeriods || []).join(","), trigger, channel });
      if (seen.has(key)) continue;
      matched.push({ item, trigger, channel });
    }
  }
  matched.sort((a, b) => (a.item.dueDateMs || 0) - (b.item.dueDateMs || 0) ||
    String(a.item.studentName || "").localeCompare(String(b.item.studentName || "")));
  return matched;
}

async function processInstitute({ db, smsProvider, policyDoc, now }) {
  const instituteId = policyDoc.ref.parent.parent.id;
  const policy = policyDefaults(policyDoc.data());
  const hour = dhakaParts(now).hour;
  if (hour < policy.sendWindowStartHour || hour >= policy.sendWindowEndHour) {
    return { instituteId, status: "outside_window" };
  }
  const data = await loadInstituteDueData(db, instituteId, now);
  if (!data.institute || data.institute.isActive === false || data.institute.deletionState === "retained") {
    return { instituteId, status: "inactive" };
  }
  const matched = await matchDueReminders({ db, instituteId, policy, data, now });
  if (matched.length === 0) return { instituteId, status: "nothing_due", matched: 0 };

  const template = await loadDueTemplate(db, instituteId, policy);
  const policyRef = db.collection("institutes").doc(instituteId).collection("due_automation").doc("policy");
  const dayKey = dhakaUsageKeys(now).dayKey;
  const counters = { sent: 0, failed: 0, skipped: 0, creditsUsed: 0, skippedReasons: {} };
  for (const entry of matched) {
    const result = await dispatchDueReminder({
      db,
      smsProvider,
      instituteId,
      institute: data.institute,
      policyRef,
      policy,
      item: entry.item,
      trigger: entry.trigger,
      channel: entry.channel,
      template,
      now,
    });
    if (result.status === "sent") { counters.sent += 1; counters.creditsUsed += result.credits; }
    else if (result.status === "failed") counters.failed += 1;
    else if (result.status === "skipped") {
      counters.skipped += 1;
      counters.skippedReasons[result.reason] = (counters.skippedReasons[result.reason] || 0) + 1;
    }
  }
  await db.collection("institutes").doc(instituteId).collection("due_automation_runs").doc(dayKey).set({
    dayKey,
    runAtMs: now,
    matchedCount: matched.length,
    sentCount: counters.sent,
    failedCount: counters.failed,
    skippedCount: counters.skipped,
    skippedReasons: counters.skippedReasons,
    creditsUsed: counters.creditsUsed,
  }, { merge: true });
  return { instituteId, status: "processed", matched: matched.length, ...counters };
}

async function runDueAutomationSweep({ db, smsProvider, now = Date.now() }) {
  const summary = { scanned: 0, processed: 0, matched: 0, sent: 0, failed: 0, skipped: 0, skippedReasons: {}, errors: 0 };
  if (!smsProvider || typeof smsProvider.sendTextSms !== "function") {
    return { ...summary, status: "provider_unavailable" };
  }
  // Enumerate institutes and read each policy doc directly. This deliberately
  // avoids a collection-group query so the sweep needs no new Firestore index.
  const institutes = await db.collection("institutes").get();
  const candidates = institutes.docs.filter((doc) => {
    const data = doc.data() || {};
    return data.isActive !== false && data.deletionState !== "retained";
  });
  summary.scanned = candidates.length;
  const policies = (await mapWithConcurrency(candidates, 6, async (doc) => {
    const snap = await doc.ref.collection("due_automation").doc("policy").get();
    if (!snap.exists) return null;
    const data = snap.data() || {};
    return data.enabled === true ? snap : null;
  })).filter(Boolean);
  const results = await mapWithConcurrency(policies, INSTITUTE_CONCURRENCY, async (policyDoc) => {
    try {
      return await processInstitute({ db, smsProvider, policyDoc, now });
    } catch (error) {
      return { instituteId: policyDoc.ref.parent.parent.id, status: "error", message: String(error && error.message || error).slice(0, 200) };
    }
  });
  for (const result of results) {
    if (result.status === "error") { summary.errors += 1; continue; }
    if (result.status !== "processed") continue;
    summary.processed += 1;
    summary.matched += result.matched || 0;
    summary.sent += result.sent || 0;
    summary.failed += result.failed || 0;
    summary.skipped += result.skipped || 0;
    for (const [reason, count] of Object.entries(result.skippedReasons || {})) {
      summary.skippedReasons[reason] = (summary.skippedReasons[reason] || 0) + count;
    }
  }
  return summary;
}

function createDueAutomationRunner({ db, smsProvider }) {
  return () => runDueAutomationSweep({ db, smsProvider });
}

module.exports = {
  DUE_AUTOMATION_ACTIONS,
  DEFAULT_DUE_TEMPLATE,
  createDueAutomationHandler,
  createDueAutomationRunner,
  runDueAutomationSweep,
  policyDefaults,
  validatePolicyInput,
  publicPolicy,
  computeDueItems,
  triggerForDue,
  buildDueMessage,
  applyTemplate,
  estimatedDaysRemaining,
  computeMonthlyOutstandingItems,
  billingPeriodsCoveredBy,
  isMonthlyFeeType,
  isPastMonth,
  dedupeKeyFor,
  dhakaDateLabel,
};
