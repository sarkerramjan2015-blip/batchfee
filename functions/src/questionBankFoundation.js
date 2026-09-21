"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const {
  QUESTION_WALLET_DOCUMENT,
  FREE_LIFETIME_AI_ATTEMPTS,
  QUESTION_RATE_POISHA,
  actorUsageId,
  normalizedWallet,
  normalizedUsage,
} = require("./questionBilling");

// Version this text whenever the meaning of an opt-in changes. A previous
// opt-in never silently authorizes a broader future contribution policy.
const CONTRIBUTION_POLICY_VERSION = "2026-09-19.v2";
const QUESTION_SCHEMA_VERSION = 1;

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

function foundationDto(consent, walletData = null, usageData = null) {
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
    if (action !== "get_foundation" && action !== "accept_ai_tnc") {
      throw new HttpsError("invalid-argument", "Invalid question bank action.");
    }
    const instituteRef = db.collection("institutes").doc(instituteId);
    const consentRef = instituteRef.collection("question_contribution_consents").doc(uid);
    const walletRef = instituteRef.collection("question_bank_wallet").doc(QUESTION_WALLET_DOCUMENT);
    const usageRef = instituteRef.collection("question_bank_usage").doc(actorUsageId(uid));
    if (action === "get_foundation") {
      const [snapshot, walletSnapshot, usageSnapshot] = await Promise.all([
        consentRef.get(), walletRef.get(), usageRef.get(),
      ]);
      return foundationDto(
        snapshot.exists ? snapshot.data() : null,
        walletSnapshot.exists ? walletSnapshot.data() : null,
        usageSnapshot.exists ? usageSnapshot.data() : null,
      );
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
    const [walletSnapshot, usageSnapshot] = await Promise.all([walletRef.get(), usageRef.get()]);
    return foundationDto(
      result,
      walletSnapshot.exists ? walletSnapshot.data() : null,
      usageSnapshot.exists ? usageSnapshot.data() : null,
    );
  };
}

function pendingQuestionId(instituteId, questionId) {
  return createHash("sha256").update(`${instituteId}:${questionId}`).digest("hex");
}

function cleanString(value, maxLength = 8000) {
  return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
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
  pendingQuestionId,
  anonymousQuestionPayload,
  createQuestionBankFoundationHandler,
  createAnonymousQuestionSyncHandler,
};
