"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");

const QUESTION_WALLET_DOCUMENT = "default";
const FREE_LIFETIME_AI_ATTEMPTS = 5;
const QUESTION_RATE_POISHA = Object.freeze({
  mcq: 25,
  short: 50,
  creative: 75,
});

function actorUsageId(uid) {
  return createHash("sha256").update(String(uid)).digest("hex").slice(0, 40);
}

function questionCostPoisha(questionType, count) {
  const rate = QUESTION_RATE_POISHA[questionType];
  if (!rate) throw new HttpsError("invalid-argument", "Unsupported question type.");
  if (!Number.isSafeInteger(count) || count < 0 || count > 30) {
    throw new HttpsError("invalid-argument", "Invalid question count.");
  }
  return rate * count;
}

function normalizedWallet(data) {
  const source = data && typeof data === "object" ? data : {};
  const safeAmount = (value) => Number.isSafeInteger(value) && value >= 0 ? value : 0;
  return {
    balancePoisha: safeAmount(source.balancePoisha),
    totalCreditedPoisha: safeAmount(source.totalCreditedPoisha),
    totalDebitedPoisha: safeAmount(source.totalDebitedPoisha),
    updatedAtMs: Number.isSafeInteger(source.updatedAtMs) ? source.updatedAtMs : null,
  };
}

function normalizedUsage(data) {
  const source = data && typeof data === "object" ? data : {};
  const attemptCount = Number.isSafeInteger(source.aiGenerationAttemptCount) &&
    source.aiGenerationAttemptCount >= 0 ? source.aiGenerationAttemptCount : 0;
  return {
    aiGenerationAttemptCount: attemptCount,
    freeAttemptsUsed: Math.min(attemptCount, FREE_LIFETIME_AI_ATTEMPTS),
    freeAttemptsRemaining: Math.max(0, FREE_LIFETIME_AI_ATTEMPTS - attemptCount),
  };
}

function billingModeForAttempt(attemptNumber) {
  return attemptNumber <= FREE_LIFETIME_AI_ATTEMPTS ? "lifetime_free" : "wallet";
}

module.exports = {
  QUESTION_WALLET_DOCUMENT,
  FREE_LIFETIME_AI_ATTEMPTS,
  QUESTION_RATE_POISHA,
  actorUsageId,
  questionCostPoisha,
  normalizedWallet,
  normalizedUsage,
  billingModeForAttempt,
};
