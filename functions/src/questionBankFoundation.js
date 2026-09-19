"use strict";

const { HttpsError } = require("firebase-functions/v2/https");

// Version this text whenever the meaning of an opt-in changes. A previous
// opt-in never silently authorizes a broader future contribution policy.
const CONTRIBUTION_POLICY_VERSION = "2026-09-19.v1";
const QUESTION_SCHEMA_VERSION = 1;

const QUESTION_TAXONOMY = Object.freeze({
  questionTypes: ["mcq", "short", "creative"],
  difficulties: ["easy", "medium", "hard"],
  languages: ["bn", "en"],
  sourceTypes: ["manual", "teacher_note", "licensed_material", "ai_assisted"],
  reviewStatuses: ["draft", "teacher_reviewed", "pending_curation", "curated", "retired"],
  requiredAcademicFields: ["curriculum", "syllabusYear", "className", "subject", "chapter", "topic"],
});

const CONTRIBUTION_TERMS = [
  "Questions stay private to your institute unless you later choose individual questions to submit.",
  "Enabling this preference does not submit existing or future questions automatically.",
  "Submit only original questions or material you have permission to share. Do not submit student personal data.",
  "A submitted question needs review before it can join the central question bank.",
  "You can turn this preference off at any time; turning it off stops future submissions.",
];

function validOperationId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,80}$/.test(value);
}

function consentDto(data) {
  const current = data && data.policyVersion === CONTRIBUTION_POLICY_VERSION;
  return {
    enabled: Boolean(current && data.enabled === true),
    policyVersion: CONTRIBUTION_POLICY_VERSION,
    acceptedAtMs: current && data.enabled === true ? data.updatedAtMs || null : null,
    updatedAtMs: data && Number.isSafeInteger(data.updatedAtMs) ? data.updatedAtMs : null,
  };
}

function foundationDto(consent) {
  return {
    schemaVersion: QUESTION_SCHEMA_VERSION,
    taxonomy: QUESTION_TAXONOMY,
    contribution: {
      ...consentDto(consent),
      terms: CONTRIBUTION_TERMS,
      perQuestionApprovalRequired: true,
    },
    aiBilling: {
      enabled: false,
      currency: "BDT",
      minorUnit: "poisha",
      pricingStatus: "not_configured",
      walletSeparateFromSms: true,
      clientBalanceWritesAllowed: false,
    },
  };
}

/** Phase 0 only: reads policy and records the caller's revocable preference.
 * No question, scan, payment, trial credit or central-bank entry is created.
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
    if (action !== "get_foundation" && action !== "set_contribution_preference") {
      throw new HttpsError("invalid-argument", "Invalid question bank action.");
    }
    const instituteRef = db.collection("institutes").doc(instituteId);
    const consentRef = instituteRef.collection("question_contribution_consents").doc(uid);
    if (action === "get_foundation") {
      const snapshot = await consentRef.get();
      return foundationDto(snapshot.exists ? snapshot.data() : null);
    }

    const operationId = data.operationId;
    if (!validOperationId(operationId) || typeof data.enabled !== "boolean") {
      throw new HttpsError("invalid-argument", "Invalid contribution preference request.");
    }
    if (data.policyVersion !== CONTRIBUTION_POLICY_VERSION) {
      throw new HttpsError("failed-precondition", "Contribution terms changed. Refresh and review them again.");
    }
    if (data.enabled && data.confirmedRights !== true) {
      throw new HttpsError("failed-precondition", "Confirm that you have the right to share your questions.");
    }
    const eventRef = instituteRef.collection("question_contribution_consent_events").doc(operationId);
    const result = await db.runTransaction(async (tx) => {
      const event = await tx.get(eventRef);
      if (event.exists) {
        const prior = event.data();
        if (prior.actorUid !== uid || prior.instituteId !== instituteId ||
            prior.enabled !== data.enabled || prior.policyVersion !== data.policyVersion) {
          throw new HttpsError("already-exists", "Operation ID belongs to another preference change.");
        }
        return prior;
      }
      const timestamp = now();
      const next = {
        actorUid: uid,
        instituteId,
        enabled: data.enabled,
        policyVersion: CONTRIBUTION_POLICY_VERSION,
        updatedAtMs: timestamp,
      };
      tx.set(consentRef, next);
      tx.create(eventRef, { ...next, confirmedRights: data.enabled === true });
      return next;
    });
    return foundationDto(result);
  };
}

module.exports = {
  CONTRIBUTION_POLICY_VERSION,
  QUESTION_SCHEMA_VERSION,
  QUESTION_TAXONOMY,
  CONTRIBUTION_TERMS,
  consentDto,
  foundationDto,
  createQuestionBankFoundationHandler,
};
