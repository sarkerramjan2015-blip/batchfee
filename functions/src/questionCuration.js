"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const { QUESTION_SCHEMA_VERSION } = require("./questionBankFoundation");

const MAX_PAGE_SIZE = 50;

function cleanString(value, maxLength) {
  return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
}

function validId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,100}$/.test(value);
}

function requiredId(value, label) {
  if (!validId(value)) throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  return value;
}

function publicQuestionDto(id, data) {
  const options = Array.isArray(data && data.options) ?
    data.options.map((option) => cleanString(option, 1000)).filter(Boolean).slice(0, 4) : [];
  return {
    id,
    curriculum: cleanString(data && data.curriculum, 120),
    syllabusYear: cleanString(data && data.syllabusYear, 20),
    className: cleanString(data && data.className, 120),
    subject: cleanString(data && data.subject, 160),
    chapter: cleanString(data && data.chapter, 200),
    topic: cleanString(data && data.topic, 200),
    type: cleanString(data && data.type, 30),
    language: cleanString(data && data.language, 10),
    difficulty: cleanString(data && data.difficulty, 30),
    questionText: cleanString(data && data.questionText, 8000),
    options,
    correctAnswer: cleanString(data && data.correctAnswer, 2000),
    explanation: cleanString(data && data.explanation, 4000),
    marks: Number.isSafeInteger(data && data.marks) ? data.marks : 0,
    syncedAtMs: Number.isSafeInteger(data && data.syncedAtMs) ? data.syncedAtMs : 0,
    curatedAtMs: Number.isSafeInteger(data && data.curatedAtMs) ? data.curatedAtMs : 0,
  };
}

/**
 * A stable, academic-only signature for duplicate prevention. It intentionally
 * excludes all tenant, contributor and source material fields so it cannot be
 * used to identify who submitted a question.
 */
function questionFingerprint(question) {
  const normalize = (value) => cleanString(value, 8000)
    .normalize("NFKC")
    .replace(/\s+/g, " ")
    .toLocaleLowerCase("en-US");
  const canonical = JSON.stringify({
    curriculum: normalize(question.curriculum),
    syllabusYear: normalize(question.syllabusYear),
    className: normalize(question.className),
    subject: normalize(question.subject),
    chapter: normalize(question.chapter),
    topic: normalize(question.topic),
    type: normalize(question.type),
    language: normalize(question.language),
    questionText: normalize(question.questionText),
    options: question.options.map(normalize),
    correctAnswer: normalize(question.correctAnswer),
  });
  return createHash("sha256").update(canonical).digest("hex");
}

function validatedQuestionPayload(id, data) {
  const question = publicQuestionDto(id, data);
  if (!question.className || !question.subject || !question.chapter || !question.questionText ||
      !question.correctAnswer || !["mcq", "short", "creative"].includes(question.type) ||
      !["bn", "en"].includes(question.language) ||
      !["easy", "medium", "hard"].includes(question.difficulty) ||
      question.marks < 1 || question.marks > 100) {
    throw new HttpsError("failed-precondition", "This pending question is incomplete and cannot be published.");
  }
  if (question.type === "mcq" &&
      (question.options.length !== 4 || new Set(question.options).size !== 4 ||
       !question.options.includes(question.correctAnswer))) {
    throw new HttpsError("failed-precondition", "This pending MCQ has invalid options or answer.");
  }
  if (question.type !== "mcq" && question.options.length) {
    throw new HttpsError("failed-precondition", "Only MCQs can contain options.");
  }
  return question;
}

function resultDto({ action, pendingId, publishedId = null, status, reviewedAtMs }) {
  return { action, pendingId, publishedId, status, reviewedAtMs };
}

/**
 * The moderation queue is anonymous by design. This handler only returns a
 * strict academic allow-list and publishes the same allow-list, never a tenant
 * path, user id, teacher name, institute name, or source scan reference.
 */
function createQuestionCurationHandler({ db, authorizeRoot, now = Date.now }) {
  return async function commitQuestionCurationOperation(request) {
    await authorizeRoot(request.auth);
    const data = request.data || {};
    const action = data.action;
    if (action === "list_pending") {
      const requested = Number(data.limit);
      const limit = Number.isSafeInteger(requested) ? Math.min(Math.max(requested, 1), MAX_PAGE_SIZE) : 25;
      const snapshot = await db.collection("global_pending_review")
        .where("moderationStatus", "==", "pending")
        .limit(limit)
        .get();
      const questions = snapshot.docs
        .map((document) => publicQuestionDto(document.id, document.data()))
        .filter((question) => question.questionText)
        .sort((left, right) => right.syncedAtMs - left.syncedAtMs);
      return { questions, limit };
    }
    if (action !== "approve" && action !== "reject") {
      throw new HttpsError("invalid-argument", "Invalid question curation action.");
    }
    const uid = request.auth && request.auth.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const pendingId = requiredId(data.pendingId, "pending question");
    const operationId = requiredId(data.operationId, "curation operation");
    const rejectReason = action === "reject" ? cleanString(data.reason, 500) : "";
    if (action === "reject" && !rejectReason) {
      throw new HttpsError("invalid-argument", "A brief rejection reason is required.");
    }
    const pendingRef = db.collection("global_pending_review").doc(pendingId);
    const globalRef = db.collection("global_question_bank").doc(pendingId);
    const operationRef = db.collection("question_curation_operations").doc(operationId);
    return db.runTransaction(async (tx) => {
      const [operationSnap, pendingSnap, globalSnap] = await Promise.all([
        tx.get(operationRef), tx.get(pendingRef), tx.get(globalRef),
      ]);
      if (operationSnap.exists) {
        const saved = operationSnap.data();
        if (saved.actorUid !== uid || saved.action !== action || saved.pendingId !== pendingId) {
          throw new HttpsError("already-exists", "Curation operation belongs to another request.");
        }
        return saved.result;
      }
      if (!pendingSnap.exists || pendingSnap.get("moderationStatus") !== "pending") {
        throw new HttpsError("failed-precondition", "This question is no longer awaiting review.");
      }
      const timestamp = now();
      if (action === "approve") {
        const question = validatedQuestionPayload(pendingId, pendingSnap.data());
        const fingerprint = questionFingerprint(question);
        const duplicateRef = db.collection("global_question_dedup").doc(fingerprint);
        const duplicateSnap = await tx.get(duplicateRef);
        if (duplicateSnap.exists) {
          throw new HttpsError(
            "already-exists",
            "An identical approved academic question already exists. Reject this review item as a duplicate instead.",
          );
        }
        if (globalSnap.exists) {
          throw new HttpsError("already-exists", "A global question already exists for this review item.");
        }
        const result = resultDto({
          action,
          pendingId,
          publishedId: pendingId,
          status: "approved",
          reviewedAtMs: timestamp,
        });
        tx.create(globalRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          status: "curated",
          moderationStatus: "approved",
          anonymous: true,
          publishedFromPendingId: pendingId,
          publishedAtMs: timestamp,
          curatedAtMs: timestamp,
          questionFingerprint: fingerprint,
          curriculum: cleanString(pendingSnap.get("curriculum"), 120),
          syllabusYear: cleanString(pendingSnap.get("syllabusYear"), 20),
          ...question,
        });
        tx.update(pendingRef, {
          moderationStatus: "approved",
          reviewedAtMs: timestamp,
          publishedId: pendingId,
        });
        tx.create(duplicateRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          globalQuestionId: pendingId,
          publishedAtMs: timestamp,
        });
        tx.create(operationRef, {
          action, pendingId, actorUid: uid, createdAtMs: timestamp, result,
        });
        return result;
      }
      const result = resultDto({
        action,
        pendingId,
        status: "rejected",
        reviewedAtMs: timestamp,
      });
      tx.update(pendingRef, {
        moderationStatus: "rejected",
        rejectionReason: rejectReason,
        reviewedAtMs: timestamp,
      });
      tx.create(operationRef, {
        action, pendingId, actorUid: uid, reason: rejectReason, createdAtMs: timestamp, result,
      });
      return result;
    });
  };
}

module.exports = {
  publicQuestionDto,
  validatedQuestionPayload,
  questionFingerprint,
  createQuestionCurationHandler,
};
