"use strict";

const { HttpsError } = require("firebase-functions/v2/https");
const { CONTRIBUTION_POLICY_VERSION, QUESTION_SCHEMA_VERSION } = require("./questionBankFoundation");
const { parseMediaReference } = require("./mediaSecurityCore");
const {
  QUESTION_WALLET_DOCUMENT,
  QUESTION_RATE_POISHA,
  MANUAL_QUESTION_RATE_POISHA,
  normalizedWallet,
  questionCostPoisha,
} = require("./questionBilling");

const PROPOSED_RATE_POISHA = QUESTION_RATE_POISHA;
const SUPPORTED_PATTERN_KEYS = new Set([
  "standard",
  "english_1st_seen_comprehension",
  "english_1st_unseen_comprehension",
  "english_1st_writing",
  "english_2nd_grammar",
  "english_2nd_composition",
  "bangla_2nd_grammar_mcq",
  "bangla_2nd_written",
]);

function cleanString(value, maxLength) {
  return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
}

function validId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,80}$/.test(value);
}

function requiredId(value, label) {
  if (!validId(value)) throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  return value;
}

function requiredString(value, label, maxLength) {
  const result = cleanString(value, maxLength);
  if (!result) throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  return result;
}

function normalizePatternKey(value) {
  const key = cleanString(value, 80).toLowerCase();
  return SUPPORTED_PATTERN_KEYS.has(key) ? key : "standard";
}

function proposedCostPoisha(questionType, count) {
  return questionCostPoisha(questionType, count);
}

function normalizeQuestion(raw, questionType, instituteId) {
  const sourceQuestionId = requiredId(raw && raw.sourceQuestionId, "source question");
  const questionText = requiredString(raw && raw.questionText, "question text", 8000);
  const correctAnswer = requiredString(raw && raw.correctAnswer, "correct answer", 2000);
  const explanation = cleanString(raw && raw.explanation, 4000);
  const difficulty = requiredString(raw && raw.difficulty, "difficulty", 20).toLowerCase();
  if (!["easy", "medium", "hard"].includes(difficulty)) {
    throw new HttpsError("invalid-argument", "Unsupported difficulty.");
  }
  const marks = Number(raw && raw.marks);
  if (!Number.isSafeInteger(marks) || marks < 1 || marks > 100) {
    throw new HttpsError("invalid-argument", "Marks must be between 1 and 100.");
  }
  const options = Array.isArray(raw && raw.options) ? raw.options.map((option) =>
    requiredString(option, "option", 1000)) : [];
  if (questionType === "mcq") {
    if (options.length !== 4 || new Set(options).size !== 4 || !options.includes(correctAnswer)) {
      throw new HttpsError("invalid-argument", "Every MCQ needs four distinct options and a matching answer.");
    }
  } else if (options.length) {
    throw new HttpsError("invalid-argument", "Only MCQs can contain options.");
  }
  const rawImageReference = cleanString(raw && raw.imageReference, 512);
  const imageReference = rawImageReference || null;
  if (imageReference) {
    const parsed = parseMediaReference(imageReference);
    if (!parsed || parsed.instituteId !== instituteId) {
      throw new HttpsError("invalid-argument", "Invalid question image reference.");
    }
  }
  return { sourceQuestionId, questionText, options, correctAnswer, explanation, difficulty, marks, imageReference };
}

function normalizeManualSetup(raw) {
  const totalMarks = Number(raw && raw.totalMarks);
  const durationMinutes = Number(raw && raw.durationMinutes);
  const shortQuestionMarks = Number(raw && raw.shortQuestionMarks || 2);
  if (!Number.isSafeInteger(totalMarks) || totalMarks < 1 || totalMarks > 1000) {
    throw new HttpsError("invalid-argument", "Manual question setup requires total marks between 1 and 1000.");
  }
  if (!Number.isSafeInteger(durationMinutes) || durationMinutes < 1 || durationMinutes > 1440) {
    throw new HttpsError("invalid-argument", "Manual question setup requires a valid duration.");
  }
  if (!Number.isSafeInteger(shortQuestionMarks) || shortQuestionMarks < 1 || shortQuestionMarks > 100) {
    throw new HttpsError("invalid-argument", "Short-question marks must be between 1 and 100.");
  }
  const language = cleanString(raw && raw.language, 10).toLowerCase() || "bn";
  if (!["bn", "en"].includes(language)) {
    throw new HttpsError("invalid-argument", "Unsupported question language.");
  }
  return {
    examName: requiredString(raw && raw.examName, "exam name", 120),
    totalMarks,
    durationMinutes,
    shortQuestionMarks,
    className: requiredString(raw && raw.className, "class name", 120),
    subject: requiredString(raw && raw.subject, "subject", 160),
    chapter: requiredString(raw && raw.chapter, "chapter", 200),
    chapterName: cleanString(raw && raw.chapterName, 160),
    topic: cleanString(raw && raw.topic, 160),
    patternKey: normalizePatternKey(raw && raw.patternKey),
    patternVariant: cleanString(raw && raw.patternVariant, 120),
    language,
  };
}

function canonicalFinalizationRequest(data) {
  const instituteId = requiredId(data && data.instituteId, "institute");
  const generationOperationId = requiredId(data && data.generationOperationId, "generation operation");
  const operationId = requiredId(data && data.operationId, "finalization operation");
  const sourceType = cleanString(data && data.sourceType, 32).toLowerCase() || "ai_assisted";
  if (!["ai_assisted", "manual"].includes(sourceType)) {
    throw new HttpsError("invalid-argument", "Unsupported question source.");
  }
  const questionType = requiredString(data && data.questionType, "question type", 20).toLowerCase();
  if (!Object.hasOwn(PROPOSED_RATE_POISHA, questionType)) {
    throw new HttpsError("invalid-argument", "Unsupported question type.");
  }
  if (!Array.isArray(data && data.questions) || data.questions.length < 1 || data.questions.length > 30) {
    throw new HttpsError("invalid-argument", "Select between one and 30 questions.");
  }
  const questions = data.questions.map((item) => normalizeQuestion(item, questionType, instituteId));
  if (new Set(questions.map((question) => question.sourceQuestionId)).size !== questions.length) {
    throw new HttpsError("invalid-argument", "A generated question can only be finalized once.");
  }
  return {
    instituteId,
    generationOperationId,
    operationId,
    sourceType,
    manualSetup: sourceType === "manual" ? normalizeManualSetup(data && data.manualSetup) : null,
    questionType,
    questions,
    costPoisha: sourceType === "manual" ?
      MANUAL_QUESTION_RATE_POISHA * questions.length :
      proposedCostPoisha(questionType, questions.length),
  };
}

function finalizedQuestionId(operationId, sourceQuestionId) {
  return `finalized_${operationId}_${sourceQuestionId}`;
}

/**
 * Saves teacher-reviewed questions to the private bank atomically and lets the
 * existing document trigger anonymously enqueue them for Super Admin review.
 * The first five lifetime AI attempts are free. Later AI attempts debit the
 * separate question wallet per the rate card. Manually created questions always
 * pay the BDT 1.00 platform fee from the same wallet at finalization. Every
 * wallet debit also records an immutable platform revenue event.
 */
function createQuestionFinalizationHandler({ db, authorize, now = Date.now }) {
  return async function finalizeExamQuestions(request) {
    const uid = request.auth && request.auth.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const input = canonicalFinalizationRequest(request.data || {});
    await authorize(request.auth, input.instituteId, "manage_exams", true);

    const instituteRef = db.collection("institutes").doc(input.instituteId);
    const generationRef = instituteRef.collection("question_generation_jobs").doc(input.generationOperationId);
    const consentRef = instituteRef.collection("question_contribution_consents").doc(uid);
    const finalizationRef = instituteRef.collection("question_finalization_operations").doc(input.operationId);
    const walletRef = instituteRef.collection("question_bank_wallet").doc(QUESTION_WALLET_DOCUMENT);
    const walletLedgerRef = instituteRef.collection("question_bank_wallet_ledger").doc(`debit_${input.operationId}`);
    const revenueEventRef = db.collection("platform_question_revenue").doc(`charge_${input.operationId}`);
    const revenueSummaryRef = db.collection("platform_question_revenue").doc("_summary");

    return db.runTransaction(async (tx) => {
      const [generationSnap, consentSnap, finalizationSnap, walletSnap, revenueSummarySnap] = await Promise.all([
        tx.get(generationRef), tx.get(consentRef), tx.get(finalizationRef), tx.get(walletRef),
        tx.get(revenueSummaryRef),
      ]);
      if (finalizationSnap.exists) {
        const saved = finalizationSnap.data();
        if (saved.actorUid !== uid || saved.instituteId !== input.instituteId ||
            saved.generationOperationId !== input.generationOperationId ||
            saved.sourceType !== input.sourceType) {
          throw new HttpsError("already-exists", "Finalization operation belongs to another request.");
        }
        return saved.result;
      }
      const consent = consentSnap.exists ? consentSnap.data() : null;
      if (!consent || consent.aiTncAccepted !== true || consent.policyVersion !== CONTRIBUTION_POLICY_VERSION) {
        throw new HttpsError("failed-precondition", "Review and accept the current contribution terms first.");
      }
      let setup;
      let billingMode = input.sourceType === "manual" ? "manual" : "wallet";
      if (input.sourceType === "ai_assisted") {
        if (!generationSnap.exists || generationSnap.get("actorUid") !== uid ||
            generationSnap.get("status") !== "complete") {
          throw new HttpsError("failed-precondition", "Generate a completed preview before finalizing.");
        }
        if (generationSnap.get("finalizationOperationId")) {
          throw new HttpsError("failed-precondition", "This preview was already finalized.");
        }
        if (generationSnap.get("questionType") !== input.questionType) {
          throw new HttpsError("invalid-argument", "Question type does not match this preview.");
        }
        setup = generationSnap.get("setup");
        const generated = generationSnap.get("result") && generationSnap.get("result").questions;
        if (!setup || !Array.isArray(generated)) {
          throw new HttpsError("failed-precondition", "This preview is missing required setup information. Generate it again.");
        }
        const generatedIds = new Set(generated.map((question) => question && question.id).filter(Boolean));
        if (input.questions.some((question) => !generatedIds.has(question.sourceQuestionId))) {
          throw new HttpsError("invalid-argument", "A selected question is not part of this preview.");
        }
        const generationData = generationSnap.data() || {};
        // Jobs created before wallet billing was activated do not have billing metadata.
        // Keep those already-generated previews free instead of surprising the teacher
        // with a retroactive charge. Only an explicit wallet marker may debit funds.
        billingMode = generationData.billing && generationData.billing.mode === "wallet" ?
          "wallet" : "lifetime_free";
      } else {
        setup = input.manualSetup;
      }
      const totalMarks = input.questions.reduce((sum, question) => sum + question.marks, 0);
      const expectedMarks = input.questionType === "mcq" ? 1 :
        input.questionType === "creative" ? 10 : setup.shortQuestionMarks || 2;
      if (input.questions.some((question) => question.marks !== expectedMarks)) {
        throw new HttpsError("invalid-argument", `Each ${input.questionType} question must use ${expectedMarks} mark(s).`);
      }
      if (!Number.isSafeInteger(setup.totalMarks) || totalMarks > setup.totalMarks) {
        throw new HttpsError("invalid-argument", "Selected question marks exceed the exam total.");
      }

      const timestamp = now();
      // Manual questions always pay the BDT 1 platform fee from the question
      // wallet; AI questions pay the rate card only when the preview was
      // created outside the lifetime free attempts.
      const chargedCostPoisha = input.sourceType === "manual" ? input.costPoisha :
        billingMode === "wallet" ? input.costPoisha : 0;
      const wallet = normalizedWallet(walletSnap.exists ? walletSnap.data() : null);
      if (chargedCostPoisha > wallet.balancePoisha) {
        throw new HttpsError(
          "resource-exhausted",
          `Insufficient question wallet balance. Required BDT ${(chargedCostPoisha / 100).toFixed(2)}, available BDT ${(wallet.balancePoisha / 100).toFixed(2)}. Top up the question wallet to continue.`,
        );
      }
      const remainingBalancePoisha = wallet.balancePoisha - chargedCostPoisha;
      const billingStatus = input.sourceType === "manual" ? "manual_platform_fee" :
        billingMode === "lifetime_free" ? "lifetime_free" : "wallet_debited";
      const result = {
        operationId: input.operationId,
        questionCount: input.questions.length,
        costPoisha: chargedCostPoisha,
        quotedCostPoisha: input.costPoisha,
        chargedCostPoisha,
        remainingBalancePoisha,
        billingStatus,
      };
      input.questions.forEach((question) => {
        const questionRef = instituteRef.collection("question_bank")
          .doc(finalizedQuestionId(input.operationId, question.sourceQuestionId));
        tx.create(questionRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          status: "finalized",
          reviewStatus: "teacher_reviewed",
          sourceType: input.sourceType,
          generationOperationId: input.sourceType === "ai_assisted" ? input.generationOperationId : null,
          manualEntryOperationId: input.sourceType === "manual" ? input.generationOperationId : null,
          finalizationOperationId: input.operationId,
          sourceQuestionId: question.sourceQuestionId,
          createdBy: uid,
          createdAtMs: timestamp,
          finalizedAtMs: timestamp,
          updatedAtMs: timestamp,
          examName: setup.examName,
          curriculum: "",
          syllabusYear: "",
          className: setup.className,
          subject: setup.subject,
          chapter: setup.chapter,
          chapterName: setup.chapterName || "",
          topic: setup.topic || "",
          patternKey: setup.patternKey || "standard",
          patternVariant: setup.patternVariant || "",
          shortQuestionMarks: setup.shortQuestionMarks || 2,
          language: setup.language || "bn",
          type: input.questionType,
          ...question,
          pricing: {
            currency: "BDT",
            quotedCostPoisha: input.sourceType === "manual" ?
              MANUAL_QUESTION_RATE_POISHA : PROPOSED_RATE_POISHA[input.questionType],
            chargedCostPoisha: chargedCostPoisha > 0 ?
              (input.sourceType === "manual" ? MANUAL_QUESTION_RATE_POISHA : PROPOSED_RATE_POISHA[input.questionType]) : 0,
            billingStatus: result.billingStatus,
          },
        });
      });
      if (chargedCostPoisha > 0) {
        const nextWallet = {
          balancePoisha: remainingBalancePoisha,
          totalCreditedPoisha: wallet.totalCreditedPoisha,
          totalDebitedPoisha: wallet.totalDebitedPoisha + chargedCostPoisha,
          updatedAtMs: timestamp,
        };
        if (walletSnap.exists) tx.update(walletRef, nextWallet);
        else tx.create(walletRef, nextWallet);
        tx.create(walletLedgerRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          type: "debit",
          amountPoisha: chargedCostPoisha,
          balanceAfterPoisha: remainingBalancePoisha,
          instituteId: input.instituteId,
          actorUid: uid,
          generationOperationId: input.generationOperationId,
          finalizationOperationId: input.operationId,
          questionType: input.questionType,
          questionCount: input.questions.length,
          createdAtMs: timestamp,
        });
        const summarySource = revenueSummarySnap.exists ? revenueSummarySnap.data() : {};
        const safeInt = (value) => Number.isSafeInteger(value) ? value : 0;
        tx.create(revenueEventRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          kind: "question_charge",
          amountPoisha: chargedCostPoisha,
          instituteId: input.instituteId,
          actorUid: uid,
          sourceType: input.sourceType,
          questionType: input.questionType,
          questionCount: input.questions.length,
          finalizationOperationId: input.operationId,
          createdAtMs: timestamp,
        });
        const nextRevenueSummary = {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          totalQuestionChargesPoisha: safeInt(summarySource.totalQuestionChargesPoisha) + chargedCostPoisha,
          totalTopupFeePoisha: safeInt(summarySource.totalTopupFeePoisha),
          totalTopupCreditPoisha: safeInt(summarySource.totalTopupCreditPoisha),
          topupCount: safeInt(summarySource.topupCount),
          chargeCount: safeInt(summarySource.chargeCount) + 1,
          updatedAtMs: timestamp,
        };
        if (revenueSummarySnap.exists) tx.update(revenueSummaryRef, nextRevenueSummary);
        else tx.create(revenueSummaryRef, nextRevenueSummary);
      }
      if (input.sourceType === "ai_assisted") {
        tx.update(generationRef, {
          finalizationOperationId: input.operationId,
          billingStatus,
          chargedCostPoisha,
          finalizedAtMs: timestamp,
          updatedAtMs: timestamp,
        });
      }
      tx.create(finalizationRef, {
        schemaVersion: QUESTION_SCHEMA_VERSION,
        instituteId: input.instituteId,
        actorUid: uid,
        generationOperationId: input.generationOperationId,
        sourceType: input.sourceType,
        operationId: input.operationId,
        questionCount: input.questions.length,
        costPoisha: chargedCostPoisha,
        quotedCostPoisha: input.costPoisha,
        chargedCostPoisha,
        remainingBalancePoisha,
        billingStatus: result.billingStatus,
        createdAtMs: timestamp,
        result,
      });
      return result;
    });
  };
}

module.exports = {
  PROPOSED_RATE_POISHA,
  canonicalFinalizationRequest,
  proposedCostPoisha,
  createQuestionFinalizationHandler,
};
