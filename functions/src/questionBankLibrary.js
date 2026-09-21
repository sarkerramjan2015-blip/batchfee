"use strict";

const { randomUUID } = require("node:crypto");
const { FieldPath } = require("firebase-admin/firestore");
const { HttpsError } = require("firebase-functions/v2/https");
const { QUESTION_SCHEMA_VERSION } = require("./questionBankFoundation");
const { publicQuestionDto, validatedQuestionPayload } = require("./questionCuration");

const MAX_LIST_SIZE = 50;
const MAX_PAPER_QUESTIONS = 30;

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

function normalizeFilters(raw) {
  const source = raw && typeof raw === "object" ? raw : {};
  const type = cleanString(source.type, 30).toLowerCase();
  const difficulty = cleanString(source.difficulty, 30).toLowerCase();
  if (type && !["mcq", "short", "creative"].includes(type)) {
    throw new HttpsError("invalid-argument", "Invalid question type filter.");
  }
  if (difficulty && !["easy", "medium", "hard"].includes(difficulty)) {
    throw new HttpsError("invalid-argument", "Invalid difficulty filter.");
  }
  return {
    curriculum: cleanString(source.curriculum, 120).toLocaleLowerCase(),
    syllabusYear: cleanString(source.syllabusYear, 20).toLocaleLowerCase(),
    className: cleanString(source.className, 120).toLocaleLowerCase(),
    subject: cleanString(source.subject, 160).toLocaleLowerCase(),
    chapter: cleanString(source.chapter, 200).toLocaleLowerCase(),
    type,
    difficulty,
    search: cleanString(source.search, 120).toLocaleLowerCase(),
  };
}

function decodePageToken(value) {
  if (!value) return null;
  if (typeof value !== "string" || value.length > 300) {
    throw new HttpsError("invalid-argument", "Invalid question bank page token.");
  }
  try {
    const decoded = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (!Number.isSafeInteger(decoded.curatedAtMs) || !validId(decoded.id)) throw new Error("bad cursor");
    return decoded;
  } catch {
    throw new HttpsError("invalid-argument", "Invalid question bank page token.");
  }
}

function encodePageToken(question) {
  return Buffer.from(JSON.stringify({ curatedAtMs: question.curatedAtMs, id: question.id })).toString("base64url");
}

function matchesFilters(question, filters) {
  const equal = (value, filter) => !filter || cleanString(value, 250).toLocaleLowerCase() === filter;
  if (!equal(question.curriculum, filters.curriculum) || !equal(question.syllabusYear, filters.syllabusYear) ||
      !equal(question.className, filters.className) || !equal(question.subject, filters.subject) ||
      !equal(question.chapter, filters.chapter) || !equal(question.type, filters.type) ||
      !equal(question.difficulty, filters.difficulty)) return false;
  if (!filters.search) return true;
  const haystack = [question.questionText, question.topic, question.chapterName, question.chapter, question.subject]
    .map((value) => cleanString(value, 8000).toLocaleLowerCase()).join(" ");
  return haystack.includes(filters.search);
}

/**
 * Gives active institute exam managers a read-only, allow-listed view of
 * approved global questions. All source/contributor data remains unavailable.
 */
function createQuestionBankLibraryHandler({ db, authorize, now = Date.now, randomId = randomUUID }) {
  return async function commitQuestionBankLibraryOperation(request) {
    const uid = request.auth && request.auth.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const data = request.data || {};
    const instituteId = requiredId(data.instituteId, "institute");
    await authorize(request.auth, instituteId, "manage_exams", true);
    const action = data.action;
    if (action === "list") {
      const requested = Number(data.limit);
      const limit = Number.isSafeInteger(requested) ? Math.min(Math.max(requested, 1), MAX_LIST_SIZE) : 25;
      const filters = normalizeFilters(data.filters);
      const cursor = decodePageToken(data.pageToken);
      // Cursor pagination keeps every request bounded. Academic metadata is
      // filtered server-side, so client devices never receive the raw global
      // collection or need Firestore indexes for every filter combination.
      let query = db.collection("global_question_bank")
        .where("status", "==", "curated")
        .orderBy("curatedAtMs", "desc")
        .orderBy(FieldPath.documentId())
        .limit(limit + 1);
      if (cursor) query = query.startAfter(cursor.curatedAtMs, cursor.id);
      const snapshot = await query.get();
      const pageDocs = snapshot.docs.slice(0, limit);
      const questions = pageDocs
        .map((document) => publicQuestionDto(document.id, document.data()))
        .filter((question) => question.questionText && matchesFilters(question, filters));
      const nextPageToken = snapshot.docs.length > limit && pageDocs.length
        ? encodePageToken(publicQuestionDto(pageDocs.at(-1).id, pageDocs.at(-1).data()))
        : null;
      return { questions, limit, nextPageToken };
    }
    if (action !== "prepare_paper") {
      throw new HttpsError("invalid-argument", "Invalid global question bank action.");
    }
    const operationId = requiredId(data.operationId, "paper operation");
    if (!Array.isArray(data.questionIds) || data.questionIds.length < 1 || data.questionIds.length > MAX_PAPER_QUESTIONS) {
      throw new HttpsError("invalid-argument", `Choose between one and ${MAX_PAPER_QUESTIONS} questions.`);
    }
    const questionIds = data.questionIds.map((id) => requiredId(id, "question"));
    if (new Set(questionIds).size !== questionIds.length) {
      throw new HttpsError("invalid-argument", "A question can only be selected once.");
    }
    const usageRef = db.collection("institutes").doc(instituteId)
      .collection("question_bank_usage").doc(operationId);
    return db.runTransaction(async (tx) => {
      const previous = await tx.get(usageRef);
      if (previous.exists) {
        const saved = previous.data();
        if (saved.actorUid !== uid || !Array.isArray(saved.questionIds) ||
            saved.questionIds.join("|") !== questionIds.join("|")) {
          throw new HttpsError("already-exists", "Paper operation belongs to another request.");
        }
        return saved.result;
      }
      const refs = questionIds.map((id) => db.collection("global_question_bank").doc(id));
      const snapshots = await Promise.all(refs.map((reference) => tx.get(reference)));
      if (snapshots.some((snapshot) => !snapshot.exists || snapshot.get("status") !== "curated")) {
        throw new HttpsError("failed-precondition", "One or more selected questions are no longer available.");
      }
      const questions = snapshots.map((snapshot) => validatedQuestionPayload(snapshot.id, snapshot.data()));
      const result = {
        operationId,
        questions,
        preparedAtMs: now(),
      };
      tx.create(usageRef, {
        schemaVersion: QUESTION_SCHEMA_VERSION,
        operationId,
        actorUid: uid,
        questionIds,
        source: "global_curated_question_bank",
        preparedAtMs: result.preparedAtMs,
        auditToken: randomId(),
        result,
      });
      return result;
    });
  };
}

module.exports = {
  normalizeFilters,
  matchesFilters,
  decodePageToken,
  encodePageToken,
  createQuestionBankLibraryHandler,
};
