"use strict";

const { createHash } = require("node:crypto");

const MONEY_EPSILON = 0.001;
const MAX_MONEY = 1_000_000_000;

function normalizedText(value) {
  return typeof value === "string" ? value.trim().toLowerCase().replace(/\s+/g, " ") : "";
}

function toMoney(value, field = "amount", { allowZero = true } = {}) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > MAX_MONEY) {
    throw new TypeError(`Invalid ${field}.`);
  }
  const minorUnits = Math.round(value * 100);
  if (Math.abs(value - minorUnits / 100) > MONEY_EPSILON) {
    throw new TypeError(`${field} supports at most two decimal places.`);
  }
  if (!allowZero && minorUnits === 0) throw new TypeError(`${field} must be greater than zero.`);
  return minorUnits / 100;
}

function feeBusinessKey({ studentId, batchId, feePeriod, feeType }) {
  const source = [
    normalizedText(studentId),
    normalizedText(batchId || "direct"),
    normalizedText(feePeriod),
    normalizedText(feeType || "monthly_fee"),
  ].join("\u001f");
  return createHash("sha256").update(source).digest("hex");
}

function paymentReferenceKey(paymentMethod, transactionId) {
  const normalizedReference = normalizedText(transactionId);
  if (!normalizedReference) return null;
  return createHash("sha256")
    .update(`${normalizedText(paymentMethod)}\u001f${normalizedReference}`)
    .digest("hex");
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function requestHash(value) {
  return createHash("sha256").update(stableStringify(value)).digest("hex");
}

function ledgerStatus(totalAmount, paidAmount) {
  const dueAmount = Math.max(0, Math.round((totalAmount - paidAmount) * 100) / 100);
  return {
    paidAmount: Math.round(paidAmount * 100) / 100,
    dueAmount,
    status: dueAmount <= MONEY_EPSILON
      ? "paid"
      : paidAmount > MONEY_EPSILON ? "partially_paid" : "unpaid",
  };
}

function receiptNumber(sequence) {
  if (!Number.isSafeInteger(sequence) || sequence < 1) throw new TypeError("Invalid receipt sequence.");
  return `REC-${String(sequence).padStart(10, "0")}`;
}

module.exports = {
  MONEY_EPSILON,
  feeBusinessKey,
  ledgerStatus,
  normalizedText,
  paymentReferenceKey,
  receiptNumber,
  requestHash,
  stableStringify,
  toMoney,
};
