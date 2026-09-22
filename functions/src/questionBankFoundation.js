"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const {
  QUESTION_WALLET_DOCUMENT,
  FREE_LIFETIME_AI_ATTEMPTS,
  QUESTION_RATE_POISHA,
  MANUAL_QUESTION_RATE_POISHA,
  actorUsageId,
  normalizedWallet,
  normalizedUsage,
} = require("./questionBilling");

// Version this text whenever the meaning of an opt-in changes. A previous
// opt-in never silently authorizes a broader future contribution policy.
const CONTRIBUTION_POLICY_VERSION = "2026-09-19.v2";
const QUESTION_SCHEMA_VERSION = 1;
// Owner-approved top-up rules (2026-09-21): minimum BDT 50, no upper business
// bound besides the safety cap, and a 1.8% processing fee per transaction.
const TOPUP_MIN_AMOUNT_POISHA = 5000;
const TOPUP_MAX_AMOUNT_POISHA = 100_000_000;
const TOPUP_PROCESSING_FEE_PERCENT = 1.8;
const TOPUP_PENDING_DOCUMENT = "pending";
const PREVIOUS_QUESTIONS_PAGE_SIZE = 50;
const TOPUP_COLLECTION = "question_bank_topup_requests";

const QUESTION_TAXONOMY = Object.freeze({
  questionTypes: ["mcq", "short", "creative"],
  difficulties: ["easy", "medium", "hard"],
  languages: ["bn", "en"],
  sourceTypes: ["manual", "teacher_note", "licensed_material", "ai_assisted"],
  reviewStatuses: ["draft", "teacher_reviewed", "pending_curation", "curated", "retired"],
  requiredAcademicFields: ["className", "subject", "chapter"],
  optionalAcademicFields: ["curriculum", "syllabusYear", "chapterName", "topic"],
});

const CONTRIBUTION_TERMS = [
  "To improve our AI services and platform quality, questions finalized through this feature may be used anonymously for BatchFee's global research and question database.",
  "Only academic metadata and question content are copied for moderation. Institute and teacher names are never included.",
  "Only upload original material or content you have permission to use. Never include student personal data.",
  "Anonymous questions must pass Super Admin moderation before joining the global question bank.",
];

function validOperationId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,80}$/.test(value);
}

function consentDto(data) {
  const current = data && data.policyVersion === CONTRIBUTION_POLICY_VERSION;
  return {
    accepted: Boolean(current && data.aiTncAccepted === true),
    policyVersion: CONTRIBUTION_POLICY_VERSION,
    acceptedAtMs: current && data.aiTncAccepted === true ? data.acceptedAtMs || null : null,
    updatedAtMs: data && Number.isSafeInteger(data.updatedAtMs) ? data.updatedAtMs : null,
  };
}

function topupRequestDto(data) {
  if (!data || data.status !== "pending") return null;
  return {
    status: "pending",
    amountPoisha: Number.isSafeInteger(data.amountPoisha) ? data.amountPoisha : 0,
    feePoisha: Number.isSafeInteger(data.feePoisha) ? data.feePoisha : 0,
    payablePoisha: Number.isSafeInteger(data.payablePoisha) ? data.payablePoisha : 0,
    paymentMethod: cleanString(data && data.paymentMethod, 16) || "bkash",
    senderNumber: cleanString(data && data.senderNumber, 20) || "",
    requestedAtMs: Number.isSafeInteger(data.requestedAtMs) ? data.requestedAtMs : 0,
  };
}

function foundationDto(consent, walletData = null, usageData = null, pendingTopupData = null) {
  const wallet = normalizedWallet(walletData);
  const usage = normalizedUsage(usageData);
  return {
    schemaVersion: QUESTION_SCHEMA_VERSION,
    taxonomy: QUESTION_TAXONOMY,
    aiTerms: {
      ...consentDto(consent),
      terms: CONTRIBUTION_TERMS,
      automaticAnonymousSync: true,
      perQuestionApprovalRequired: false,
    },
    aiBilling: {
      enabled: true,
      currency: "BDT",
      minorUnit: "poisha",
      pricingStatus: "active",
      walletSeparateFromSms: true,
      clientBalanceWritesAllowed: false,
      balancePoisha: wallet.balancePoisha,
      freeLifetimeAttemptLimit: FREE_LIFETIME_AI_ATTEMPTS,
      freeAttemptsUsed: usage.freeAttemptsUsed,
      freeAttemptsRemaining: usage.freeAttemptsRemaining,
      ratesPoisha: QUESTION_RATE_POISHA,
      manualRatePoisha: MANUAL_QUESTION_RATE_POISHA,
    },
    topupPolicy: {
      minAmountPoisha: TOPUP_MIN_AMOUNT_POISHA,
      maxAmountPoisha: TOPUP_MAX_AMOUNT_POISHA,
      processingFeePercent: TOPUP_PROCESSING_FEE_PERCENT,
      pendingRequest: topupRequestDto(pendingTopupData),
    },
  };
}

/** Reads the current policy and records the authenticated actor's one-time T&C acceptance.
 * No question, scan, payment or trial credit is created here.
 */
function createQuestionBankFoundationHandler({ db, authorize, now = Date.now }) {
  return async function questionBankFoundation(request) {
    const data = request.data || {};
    const uid = request.auth && request.auth.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const instituteId = data.instituteId;
    if (typeof instituteId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(instituteId)) {
      throw new HttpsError("invalid-argument", "Invalid institute.");
    }
    // Uses the existing owner/admin or active staff with manage_exams check.
    await authorize(request.auth, instituteId, "manage_exams", true);
    const action = data.action;
    if (!["get_foundation", "accept_ai_tnc", "list_previous_questions", "request_topup"].includes(action)) {
      throw new HttpsError("invalid-argument", "Invalid question bank action.");
    }
    const instituteRef = db.collection("institutes").doc(instituteId);
    const consentRef = instituteRef.collection("question_contribution_consents").doc(uid);
    const walletRef = instituteRef.collection("question_bank_wallet").doc(QUESTION_WALLET_DOCUMENT);
    const usageRef = instituteRef.collection("question_bank_usage").doc(actorUsageId(uid));
    const topupPendingRef = instituteRef.collection(TOPUP_COLLECTION).doc(TOPUP_PENDING_DOCUMENT);
    if (action === "get_foundation") {
      const [snapshot, walletSnapshot, usageSnapshot, topupSnapshot] = await Promise.all([
        consentRef.get(), walletRef.get(), usageRef.get(), topupPendingRef.get(),
      ]);
      return foundationDto(
        snapshot.exists ? snapshot.data() : null,
        walletSnapshot.exists ? walletSnapshot.data() : null,
        usageSnapshot.exists ? usageSnapshot.data() : null,
        topupSnapshot.exists ? topupSnapshot.data() : null,
      );
    }

    if (action === "list_previous_questions") {
      const requested = Number(data.limit);
      const limit = Number.isSafeInteger(requested) ?
        Math.min(Math.max(requested, 1), PREVIOUS_QUESTIONS_PAGE_SIZE) : 25;
      const page = Number(data.page);
      const pageIndex = Number.isSafeInteger(page) && page >= 0 && page <= 1000 ? page : 0;
      const snapshot = await instituteRef.collection("question_bank")
        .where("status", "==", "finalized")
        .orderBy("finalizedAtMs", "desc")
        .offset(pageIndex * limit)
        .limit(limit)
        .get();
      const questions = snapshot.docs
        .map((document) => previousQuestionDto(document.id, document.data()))
        .filter((question) => question != null);
      return { questions, page: pageIndex, limit, hasMore: questions.length === limit };
    }

    if (action === "request_topup") {
      const operationId = data.operationId;
      if (!validOperationId(operationId)) {
        throw new HttpsError("invalid-argument", "Invalid top-up request.");
      }
      const amountPoisha = topupAmountPoisha(data.amountPoisha);
      const paymentMethod = topupPaymentMethod(data.paymentMethod);
      const senderNumber = normalizeBdMobile(data.senderNumber);
      if (!senderNumber) {
        throw new HttpsError("invalid-argument", "Enter the bKash/Nagad number the money was sent from.");
      }
      const feePoisha = Math.round(amountPoisha * TOPUP_PROCESSING_FEE_PERCENT / 100);
      const payablePoisha = amountPoisha + feePoisha;
      const historyRef = instituteRef.collection(TOPUP_COLLECTION).doc(operationId);
      const pendingTopup = await db.runTransaction(async (tx) => {
        const [historySnap, pendingSnap] = await Promise.all([
          tx.get(historyRef), tx.get(topupPendingRef),
        ]);
        if (historySnap.exists) {
          const saved = historySnap.data();
          if (saved.actorUid !== uid || saved.instituteId !== instituteId ||
              saved.amountPoisha !== amountPoisha || saved.senderNumber !== senderNumber ||
              saved.paymentMethod !== paymentMethod) {
            throw new HttpsError("already-exists", "Operation ID belongs to another top-up request.");
          }
          return topupRequestDto(saved.status === "pending" ? saved : null);
        }
        if (pendingSnap.exists && pendingSnap.get("status") === "pending") {
          throw new HttpsError(
            "failed-precondition",
            "A top-up request is already pending. Wait for it to be approved or rejected first.",
          );
        }
        const timestamp = now();
        const record = {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          kind: "history",
          status: "pending",
          instituteId,
          actorUid: uid,
          operationId,
          amountPoisha,
          feePoisha,
          payablePoisha,
          paymentMethod,
          senderNumber,
          requestedAtMs: timestamp,
          updatedAtMs: timestamp,
        };
        tx.set(historyRef, record);
        tx.set(topupPendingRef, { ...record, kind: "pending" });
        return topupRequestDto(record);
      });
      return {
        topupPolicy: {
          minAmountPoisha: TOPUP_MIN_AMOUNT_POISHA,
          maxAmountPoisha: TOPUP_MAX_AMOUNT_POISHA,
          processingFeePercent: TOPUP_PROCESSING_FEE_PERCENT,
          pendingRequest: pendingTopup,
        },
      };
    }

    const operationId = data.operationId;
    if (!validOperationId(operationId)) {
      throw new HttpsError("invalid-argument", "Invalid consent request.");
    }
    if (data.policyVersion !== CONTRIBUTION_POLICY_VERSION) {
      throw new HttpsError("failed-precondition", "Contribution terms changed. Refresh and review them again.");
    }
    if (data.confirmedRights !== true) {
      throw new HttpsError("failed-precondition", "Confirm that you have the right to share your questions.");
    }
    const eventRef = instituteRef.collection("question_contribution_consent_events").doc(operationId);
    const result = await db.runTransaction(async (tx) => {
      const event = await tx.get(eventRef);
      if (event.exists) {
        const prior = event.data();
        if (prior.actorUid !== uid || prior.instituteId !== instituteId ||
            prior.policyVersion !== data.policyVersion) {
          throw new HttpsError("already-exists", "Operation ID belongs to another consent.");
        }
        return prior;
      }
      const timestamp = now();
      const next = {
        actorUid: uid,
        instituteId,
        aiTncAccepted: true,
        policyVersion: CONTRIBUTION_POLICY_VERSION,
        acceptedAtMs: timestamp,
        updatedAtMs: timestamp,
      };
      tx.set(consentRef, next);
      tx.create(eventRef, { ...next, confirmedRights: true });
      return next;
    });
    const [walletSnapshot, usageSnapshot, topupSnapshot] = await Promise.all([
      walletRef.get(), usageRef.get(), topupPendingRef.get(),
    ]);
    return foundationDto(
      result,
      walletSnapshot.exists ? walletSnapshot.data() : null,
      usageSnapshot.exists ? usageSnapshot.data() : null,
      topupSnapshot.exists ? topupSnapshot.data() : null,
    );
  };
}

function pendingQuestionId(instituteId, questionId) {
  return createHash("sha256").update(`${instituteId}:${questionId}`).digest("hex");
}

function cleanString(value, maxLength = 8000) {
  return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
}

function topupAmountPoisha(value) {
  const amount = Number(value);
  if (!Number.isSafeInteger(amount) || amount < TOPUP_MIN_AMOUNT_POISHA || amount > TOPUP_MAX_AMOUNT_POISHA) {
    throw new HttpsError(
      "invalid-argument",
      `Top-up amount must be between BDT ${(TOPUP_MIN_AMOUNT_POISHA / 100).toFixed(2)} and BDT ${(TOPUP_MAX_AMOUNT_POISHA / 100).toFixed(2)}.`,
    );
  }
  return amount;
}

function topupPaymentMethod(value) {
  const method = cleanString(value, 16).toLowerCase();
  if (!["bkash", "nagad"].includes(method)) {
    throw new HttpsError("invalid-argument", "Choose bKash or Nagad.");
  }
  return method;
}

// Accepts 01XXXXXXXXX, +8801XXXXXXXXX and 8801XXXXXXXXX; stores +8801XXXXXXXXX.
function normalizeBdMobile(value) {
  const digits = cleanString(value, 20).replace(/\D/g, "");
  if (/^8801[3-9]\d{8}$/.test(digits)) return `+${digits}`;
  if (/^01[3-9]\d{8}$/.test(digits)) return `+880${digits.slice(1)}`;
  return "";
}

/** Owner-facing DTO of the institute's own finalized questions, for reuse. */
function previousQuestionDto(id, data) {
  const questionText = cleanString(data && data.questionText, 8000);
  if (!questionText) return null;
  return {
    id,
    type: cleanString(data && data.type, 30),
    questionText,
    options: Array.isArray(data && data.options) ?
      data.options.map((option) => cleanString(option, 1000)).filter(Boolean).slice(0, 4) : [],
    correctAnswer: cleanString(data && data.correctAnswer, 2000),
    explanation: cleanString(data && data.explanation, 4000),
    difficulty: cleanString(data && data.difficulty, 20),
    marks: Number.isSafeInteger(data && data.marks) ? data.marks : 1,
    className: cleanString(data && data.className, 120),
    subject: cleanString(data && data.subject, 160),
    chapter: cleanString(data && data.chapter, 200),
    chapterName: cleanString(data && data.chapterName, 160),
    topic: cleanString(data && data.topic, 160),
    examName: cleanString(data && data.examName, 120),
    finalizedAtMs: Number.isSafeInteger(data && data.finalizedAtMs) ? data.finalizedAtMs : 0,
  };
}

/** Strict allow-list: no tenant, actor, source URL or student identity can cross this boundary. */
function anonymousQuestionPayload(question, nowMs) {
  const options = Array.isArray(question.options) ?
    question.options.map((item) => cleanString(item, 1000)).filter(Boolean).slice(0, 6) : [];
  return {
    schemaVersion: QUESTION_SCHEMA_VERSION,
    curriculum: cleanString(question.curriculum, 120),
    syllabusYear: cleanString(question.syllabusYear, 20),
    className: cleanString(question.className, 120),
    subject: cleanString(question.subject, 160),
    chapter: cleanString(question.chapter, 200),
    chapterName: cleanString(question.chapterName, 160),
    topic: cleanString(question.topic, 200),
    patternKey: cleanString(question.patternKey, 80) || "standard",
    patternVariant: cleanString(question.patternVariant, 120),
    shortQuestionMarks: Number.isSafeInteger(question.shortQuestionMarks) ? question.shortQuestionMarks : 2,
    type: cleanString(question.type, 30),
    language: cleanString(question.language, 10),
    difficulty: cleanString(question.difficulty, 30),
    questionText: cleanString(question.questionText),
    options,
    correctAnswer: cleanString(question.correctAnswer, 2000),
    explanation: cleanString(question.explanation),
    marks: Number.isFinite(question.marks) ? question.marks : null,
    moderationStatus: "pending",
    anonymous: true,
    syncedAtMs: nowMs,
  };
}

/**
 * Mirrors only finalized, consented questions to a server-only moderation queue.
 * The deterministic hashed ID makes trigger retries idempotent without exposing tenant IDs.
 */
function createAnonymousQuestionSyncHandler({ db, now = Date.now }) {
  return async function syncFinalizedQuestion(event) {
    const instituteId = event.params.instituteId;
    const questionId = event.params.questionId;
    const destination = db.collection("global_pending_review")
      .doc(pendingQuestionId(instituteId, questionId));
    const after = event.data && event.data.after;
    if (!after || !after.exists) {
      await destination.delete();
      return;
    }
    const question = after.data() || {};
    if (question.status !== "finalized" || typeof question.createdBy !== "string") {
      await destination.delete();
      return;
    }
    const consent = await db.collection("institutes").doc(instituteId)
      .collection("question_contribution_consents").doc(question.createdBy).get();
    const consentData = consent.exists ? consent.data() : null;
    if (!consentData || consentData.policyVersion !== CONTRIBUTION_POLICY_VERSION ||
        consentData.aiTncAccepted !== true) {
      await destination.delete();
      return;
    }
    const payload = anonymousQuestionPayload(question, now());
    if (!payload.questionText || !QUESTION_TAXONOMY.questionTypes.includes(payload.type)) {
      await destination.delete();
      return;
    }
    await destination.set(payload);
  };
}

module.exports = {
  CONTRIBUTION_POLICY_VERSION,
  QUESTION_SCHEMA_VERSION,
  QUESTION_TAXONOMY,
  CONTRIBUTION_TERMS,
  consentDto,
  foundationDto,
  topupRequestDto,
  previousQuestionDto,
  pendingQuestionId,
  anonymousQuestionPayload,
  createQuestionBankFoundationHandler,
  createAnonymousQuestionSyncHandler,
  TOPUP_MIN_AMOUNT_POISHA,
  TOPUP_MAX_AMOUNT_POISHA,
  TOPUP_PROCESSING_FEE_PERCENT,
  TOPUP_PENDING_DOCUMENT,
  TOPUP_COLLECTION,
};
