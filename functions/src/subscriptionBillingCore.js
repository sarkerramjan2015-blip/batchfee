"use strict";

const { createHash } = require("node:crypto");

const DAY_MS = 24 * 60 * 60 * 1000;
const DURATION_DISCOUNTS = new Map([
  [1, 1],
  [6, 0.9],
  [12, 0.8],
]);

function roundedMoney(value) {
  return Math.round(value * 100) / 100;
}

function quoteForPlan(monthlyPrice, durationMonths) {
  const price = Number(monthlyPrice);
  const discount = DURATION_DISCOUNTS.get(durationMonths);
  if (!Number.isFinite(price) || price <= 0 || !discount) {
    throw new RangeError("Plan or subscription duration is not eligible for payment.");
  }
  return roundedMoney(price * durationMonths * discount);
}

function addCalendarMonths(startMs, months) {
  if (!Number.isSafeInteger(startMs) || !Number.isInteger(months) || months < 1) {
    throw new RangeError("Invalid subscription period.");
  }
  const start = new Date(startMs);
  const year = start.getUTCFullYear();
  const month = start.getUTCMonth();
  const day = start.getUTCDate();
  const targetMonthIndex = month + months;
  const targetYear = year + Math.floor(targetMonthIndex / 12);
  const targetMonth = targetMonthIndex % 12;
  const lastDay = new Date(Date.UTC(targetYear, targetMonth + 1, 0)).getUTCDate();
  return Date.UTC(
    targetYear,
    targetMonth,
    Math.min(day, lastDay),
    start.getUTCHours(),
    start.getUTCMinutes(),
    start.getUTCSeconds(),
    start.getUTCMilliseconds(),
  );
}

function subscriptionStartMs(currentPeriodEndMs, nowMs) {
  const currentEnd = Number(currentPeriodEndMs);
  return Number.isSafeInteger(currentEnd) && currentEnd > nowMs ? currentEnd : nowMs;
}

function subscriptionStatusFor(planId, periodEndMs, nowMs) {
  if (!Number.isSafeInteger(periodEndMs) || periodEndMs <= nowMs) return "expired";
  return planId === "plan_free_trial" ? "trial" : "active";
}

function normalizeTransactionReference(reference) {
  if (typeof reference !== "string") throw new RangeError("Invalid transaction reference.");
  const normalized = reference.trim().toUpperCase().replace(/[\s_-]/g, "");
  if (!/^[A-Z0-9]{6,64}$/.test(normalized)) {
    throw new RangeError("Invalid transaction reference.");
  }
  return normalized;
}

function transactionReferenceHash(reference) {
  return createHash("sha256").update(normalizeTransactionReference(reference)).digest("hex");
}

function maskedTransactionReference(reference) {
  const normalized = normalizeTransactionReference(reference);
  return normalized.slice(-4);
}

/**
 * Normalise a Bangladeshi mobile number to +8801XXXXXXXXX.
 * A sender number identifies the payer for manual verification; it is not a
 * transaction identifier and must never be used as a global duplicate key.
 */
function normalizeBangladeshiMobileNumber(value) {
  if (typeof value !== "string") throw new RangeError("Enter a valid Bangladeshi sending number.");
  let digits = value.trim().replace(/[\s()\-]/g, "");
  if (digits.startsWith("+")) digits = digits.slice(1);
  if (digits.startsWith("880")) digits = `0${digits.slice(3)}`;
  if (digits.startsWith("1") && digits.length === 10) digits = `0${digits}`;
  if (!/^01[3-9]\d{8}$/.test(digits)) {
    throw new RangeError("Enter a valid Bangladeshi sending number.");
  }
  return `+88${digits}`;
}

module.exports = {
  DAY_MS,
  addCalendarMonths,
  maskedTransactionReference,
  normalizeBangladeshiMobileNumber,
  normalizeTransactionReference,
  quoteForPlan,
  subscriptionStartMs,
  subscriptionStatusFor,
  transactionReferenceHash,
};
