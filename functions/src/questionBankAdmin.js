"use strict";

const { randomUUID } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const { QUESTION_SCHEMA_VERSION } = require("./questionBankFoundation");
const { publicQuestionDto } = require("./questionCuration");
const {
  QUESTION_WALLET_DOCUMENT,
  normalizedWallet,
} = require("./questionBilling");

const SETTINGS_COLLECTION = "platform_question_bank_settings";
const SETTINGS_DOCUMENT = "default";
const MAX_PAGE_SIZE = 50;

const DEFAULT_QUESTION_BANK_SETTINGS = Object.freeze({
  generationEnabled: true,
  contributionEnabled: true,
  actorDailyPreviewLimit: 5,
  instituteDailyPreviewLimit: 25,
  platformDailyPreviewLimit: 100,
  maxQuestionsPerRequest: 30,
});

function validId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,100}$/.test(value);
}

function requiredId(value, label) {
  if (!validId(value)) throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  return value;
}

function numberInRange(value, fallback, min, max, label) {
  if (value == null) return fallback;
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < min || number > max) {
    throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  }
  return number;
}

function booleanValue(value, fallback, label) {
  if (value == null) return fallback;
  if (typeof value !== "boolean") throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  return value;
}

function cleanString(value, maxLength) {
  return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
}

function creditAmount(value) {
  const amount = Number(value);
  if (!Number.isSafeInteger(amount) || amount < 1 || amount > 100_000_000) {
    throw new HttpsError("invalid-argument", "Wallet credit must be between BDT 0.01 and BDT 1,000,000.00.");
  }
  return amount;
}

function settingsDto(data) {
  const source = data && typeof data === "object" ? data : {};
  return {
    generationEnabled: source.generationEnabled !== false,
    contributionEnabled: source.contributionEnabled !== false,
    actorDailyPreviewLimit: numberInRange(source.actorDailyPreviewLimit, 5, 1, 20, "actor preview limit"),
    instituteDailyPreviewLimit: numberInRange(source.instituteDailyPreviewLimit, 25, 1, 500, "institute preview limit"),
    platformDailyPreviewLimit: numberInRange(source.platformDailyPreviewLimit, 100, 1, 10_000, "platform preview limit"),
    maxQuestionsPerRequest: numberInRange(source.maxQuestionsPerRequest, 30, 1, 30, "question request limit"),
  };
}

function requestedSettings(data) {
  const source = data && typeof data === "object" ? data : {};
  return {
    generationEnabled: booleanValue(source.generationEnabled, DEFAULT_QUESTION_BANK_SETTINGS.generationEnabled, "generation setting"),
    contributionEnabled: booleanValue(source.contributionEnabled, DEFAULT_QUESTION_BANK_SETTINGS.contributionEnabled, "contribution setting"),
    actorDailyPreviewLimit: numberInRange(source.actorDailyPreviewLimit, DEFAULT_QUESTION_BANK_SETTINGS.actorDailyPreviewLimit, 1, 20, "actor preview limit"),
    instituteDailyPreviewLimit: numberInRange(source.instituteDailyPreviewLimit, DEFAULT_QUESTION_BANK_SETTINGS.instituteDailyPreviewLimit, 1, 500, "institute preview limit"),
    platformDailyPreviewLimit: numberInRange(source.platformDailyPreviewLimit, DEFAULT_QUESTION_BANK_SETTINGS.platformDailyPreviewLimit, 1, 10_000, "platform preview limit"),
    maxQuestionsPerRequest: numberInRange(source.maxQuestionsPerRequest, DEFAULT_QUESTION_BANK_SETTINGS.maxQuestionsPerRequest, 1, 30, "question request limit"),
  };
}

async function loadQuestionBankSettings(db) {
  const snapshot = await db.collection(SETTINGS_COLLECTION).doc(SETTINGS_DOCUMENT).get();
  return settingsDto(snapshot.exists ? snapshot.data() : null);
}

function adminQuestionDto(id, data) {
  return {
    ...publicQuestionDto(id, data),
    status: typeof data.status === "string" ? data.status : "",
    curatedAtMs: Number.isSafeInteger(data.curatedAtMs) ? data.curatedAtMs : 0,
  };
}

function adminTopupDto(id, data) {
  return {
    requestId: id,
    instituteId: cleanString(data && data.instituteId, 128),
    amountPoisha: Number.isSafeInteger(data && data.amountPoisha) ? data.amountPoisha : 0,
    feePoisha: Number.isSafeInteger(data && data.feePoisha) ? data.feePoisha : 0,
    payablePoisha: Number.isSafeInteger(data && data.payablePoisha) ? data.payablePoisha : 0,
    paymentMethod: cleanString(data && data.paymentMethod, 16) || "bkash",
    senderNumber: cleanString(data && data.senderNumber, 20) || "",
    requestedAtMs: Number.isSafeInteger(data && data.requestedAtMs) ? data.requestedAtMs : 0,
  };
}

function revenueSummaryDto(data) {
  const source = data && typeof data === "object" ? data : {};
  const safe = (value) => Number.isSafeInteger(value) ? value : 0;
  return {
    totalQuestionChargesPoisha: safe(source.totalQuestionChargesPoisha),
    totalTopupFeePoisha: safe(source.totalTopupFeePoisha),
    totalTopupCreditPoisha: safe(source.totalTopupCreditPoisha),
    topupCount: safe(source.topupCount),
    chargeCount: safe(source.chargeCount),
  };
}

/** Root-only control plane. It returns academic content and configuration only. */
function createQuestionBankAdminHandler({ db, authorizeRoot, now = Date.now, randomId = randomUUID }) {
  return async function commitQuestionBankAdminOperation(request) {
    await authorizeRoot(request.auth);
    const uid = request.auth && request.auth.uid;
    const data = request.data || {};
    const action = data.action;
    const settingsRef = db.collection(SETTINGS_COLLECTION).doc(SETTINGS_DOCUMENT);

    if (action === "get_settings") return { settings: await loadQuestionBankSettings(db) };

    if (action === "get_institute_wallet") {
      const instituteId = requiredId(data.instituteId, "institute");
      const instituteRef = db.collection("institutes").doc(instituteId);
      const [instituteSnapshot, walletSnapshot] = await Promise.all([
        instituteRef.get(),
        instituteRef.collection("question_bank_wallet").doc(QUESTION_WALLET_DOCUMENT).get(),
      ]);
      if (!instituteSnapshot.exists) throw new HttpsError("not-found", "Institute was not found.");
      return { instituteId, wallet: normalizedWallet(walletSnapshot.exists ? walletSnapshot.data() : null) };
    }

    if (action === "list_questions") {
      const status = typeof data.status === "string" ? data.status : "curated";
      if (!["curated", "retired"].includes(status)) {
        throw new HttpsError("invalid-argument", "Invalid question bank status.");
      }
      const requested = Number(data.limit);
      const limit = Number.isSafeInteger(requested) ? Math.min(Math.max(requested, 1), MAX_PAGE_SIZE) : 25;
      const snapshot = await db.collection("global_question_bank")
        .where("status", "==", status)
        .limit(limit)
        .get();
      const questions = snapshot.docs
        .map((document) => adminQuestionDto(document.id, document.data()))
        .filter((question) => question.questionText)
        .sort((left, right) => right.curatedAtMs - left.curatedAtMs);
      return { questions, limit };
    }

    if (action === "list_pending_topups") {
      const snapshot = await db.collectionGroup("question_bank_topup_requests")
        .where("kind", "==", "history")
        .where("status", "==", "pending")
        .limit(50)
        .get();
      const requests = snapshot.docs
        .map((document) => adminTopupDto(document.id, document.data()))
        .sort((left, right) => right.requestedAtMs - left.requestedAtMs);
      return { requests };
    }

    if (action === "get_revenue_summary") {
      const snapshot = await db.collection("platform_question_revenue").doc("_summary").get();
      return revenueSummaryDto(snapshot.exists ? snapshot.data() : null);
    }

    if (!["update_settings", "credit_institute_wallet", "retire_question", "restore_question",
      "approve_topup", "reject_topup"].includes(action)) {
      throw new HttpsError("invalid-argument", "Invalid question bank admin action.");
    }
    const operationId = requiredId(data.operationId, "admin operation");
    const operationRef = db.collection("question_bank_admin_operations").doc(operationId);

    if (action === "approve_topup" || action === "reject_topup") {
      const instituteId = requiredId(data.instituteId, "institute");
      const requestId = requiredId(data.requestId, "top-up request");
      const instituteRef = db.collection("institutes").doc(instituteId);
      const requestRef = instituteRef.collection("question_bank_topup_requests").doc(requestId);
      const pendingRef = instituteRef.collection("question_bank_topup_requests").doc("pending");
      const walletRef = instituteRef.collection("question_bank_wallet").doc(QUESTION_WALLET_DOCUMENT);
      const ledgerRef = instituteRef.collection("question_bank_wallet_ledger").doc(`topup_${requestId}`);
      const revenueRef = db.collection("platform_question_revenue").doc(`topup_${requestId}`);
      const revenueSummaryRef = db.collection("platform_question_revenue").doc("_summary");
      return db.runTransaction(async (tx) => {
        const [previous, requestSnap, pendingSnap, walletSnap, revenueSummarySnap] = await Promise.all([
          tx.get(operationRef), tx.get(requestRef), tx.get(pendingRef), tx.get(walletRef),
          tx.get(revenueSummaryRef),
        ]);
        if (previous.exists) {
          const saved = previous.data();
          if (saved.actorUid !== uid || saved.action !== action || saved.instituteId !== instituteId ||
              saved.requestId !== requestId) {
            throw new HttpsError("already-exists", "Operation ID belongs to another top-up decision.");
          }
          return saved.result;
        }
        if (!requestSnap.exists || requestSnap.get("status") !== "pending" ||
            requestSnap.get("kind") !== "history") {
          throw new HttpsError("failed-precondition", "Top-up request is no longer pending.");
        }
        const amountPoisha = Number(requestSnap.get("amountPoisha"));
        const feePoisha = Number(requestSnap.get("feePoisha")) || 0;
        if (!Number.isSafeInteger(amountPoisha) || amountPoisha <= 0) {
          throw new HttpsError("failed-precondition", "Top-up request has an invalid amount.");
        }
        const wallet = normalizedWallet(walletSnap.exists ? walletSnap.data() : null);
        const timestamp = now();
        const status = action === "approve_topup" ? "approved" : "rejected";
        let balancePoisha = wallet.balancePoisha;
        if (action === "approve_topup") {
          balancePoisha = wallet.balancePoisha + amountPoisha;
          if (!Number.isSafeInteger(balancePoisha)) {
            throw new HttpsError("out-of-range", "Question wallet balance is too large.");
          }
          const nextWallet = {
            balancePoisha,
            totalCreditedPoisha: wallet.totalCreditedPoisha + amountPoisha,
            totalDebitedPoisha: wallet.totalDebitedPoisha,
            updatedAtMs: timestamp,
          };
          if (walletSnap.exists) tx.update(walletRef, nextWallet);
          else tx.create(walletRef, nextWallet);
          tx.create(ledgerRef, {
            schemaVersion: QUESTION_SCHEMA_VERSION,
            type: "credit",
            amountPoisha,
            feePoisha,
            balanceAfterPoisha: balancePoisha,
            instituteId,
            reason: "Question wallet top-up (owner payment approved)",
            recordedBy: uid,
            requestId,
            operationId,
            createdAtMs: timestamp,
          });
          const summary = revenueSummaryDto(revenueSummarySnap.exists ? revenueSummarySnap.data() : null);
          tx.create(revenueRef, {
            schemaVersion: QUESTION_SCHEMA_VERSION,
            kind: "topup_fee",
            amountPoisha,
            feePoisha,
            instituteId,
            requestId,
            recordedBy: uid,
            createdAtMs: timestamp,
          });
          const nextSummary = {
            schemaVersion: QUESTION_SCHEMA_VERSION,
            totalQuestionChargesPoisha: summary.totalQuestionChargesPoisha,
            totalTopupFeePoisha: summary.totalTopupFeePoisha + feePoisha,
            totalTopupCreditPoisha: summary.totalTopupCreditPoisha + amountPoisha,
            topupCount: summary.topupCount + 1,
            chargeCount: summary.chargeCount,
            updatedAtMs: timestamp,
          };
          if (revenueSummarySnap.exists) tx.update(revenueSummaryRef, nextSummary);
          else tx.create(revenueSummaryRef, nextSummary);
        }
        const decision = action === "approve_topup" ?
          { approvedBy: uid, approvedAtMs: timestamp } :
          { rejectedBy: uid, rejectedAtMs: timestamp };
        tx.update(requestRef, { status, updatedAtMs: timestamp, ...decision });
        if (pendingSnap.exists && pendingSnap.get("operationId") === requestId) {
          tx.update(pendingRef, { status, updatedAtMs: timestamp, ...decision });
        }
        const result = {
          action,
          instituteId,
          requestId,
          amountPoisha,
          status,
          balancePoisha,
          updatedAtMs: timestamp,
        };
        tx.create(operationRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          operationId,
          actorUid: uid,
          action,
          instituteId,
          requestId,
          amountPoisha,
          auditToken: randomId(),
          createdAtMs: timestamp,
          result,
        });
        return result;
      });
    }

    if (action === "credit_institute_wallet") {
      const instituteId = requiredId(data.instituteId, "institute");
      const amountPoisha = creditAmount(data.amountPoisha);
      const reason = cleanString(data.reason, 240) || "Question wallet top-up";
      const instituteRef = db.collection("institutes").doc(instituteId);
      const walletRef = instituteRef.collection("question_bank_wallet").doc(QUESTION_WALLET_DOCUMENT);
      const ledgerRef = instituteRef.collection("question_bank_wallet_ledger").doc(`credit_${operationId}`);
      return db.runTransaction(async (tx) => {
        const [previous, instituteSnapshot, walletSnapshot] = await Promise.all([
          tx.get(operationRef), tx.get(instituteRef), tx.get(walletRef),
        ]);
        if (previous.exists) {
          const saved = previous.data();
          if (saved.actorUid !== uid || saved.action !== action || saved.instituteId !== instituteId ||
              saved.amountPoisha !== amountPoisha) {
            throw new HttpsError("already-exists", "Operation ID belongs to another wallet credit.");
          }
          return saved.result;
        }
        if (!instituteSnapshot.exists) throw new HttpsError("not-found", "Institute was not found.");
        const wallet = normalizedWallet(walletSnapshot.exists ? walletSnapshot.data() : null);
        const timestamp = now();
        const balancePoisha = wallet.balancePoisha + amountPoisha;
        if (!Number.isSafeInteger(balancePoisha)) {
          throw new HttpsError("out-of-range", "Question wallet balance is too large.");
        }
        const nextWallet = {
          balancePoisha,
          totalCreditedPoisha: wallet.totalCreditedPoisha + amountPoisha,
          totalDebitedPoisha: wallet.totalDebitedPoisha,
          updatedAtMs: timestamp,
        };
        if (walletSnapshot.exists) tx.update(walletRef, nextWallet);
        else tx.create(walletRef, nextWallet);
        tx.create(ledgerRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          type: "credit",
          amountPoisha,
          balanceAfterPoisha: balancePoisha,
          instituteId,
          reason,
          recordedBy: uid,
          operationId,
          createdAtMs: timestamp,
        });
        const result = { action, instituteId, amountPoisha, balancePoisha, updatedAtMs: timestamp };
        tx.create(operationRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          operationId,
          actorUid: uid,
          action,
          instituteId,
          amountPoisha,
          reason,
          auditToken: randomId(),
          createdAtMs: timestamp,
          result,
        });
        return result;
      });
    }

    if (action === "update_settings") {
      const settings = requestedSettings(data.settings);
      return db.runTransaction(async (tx) => {
        const previous = await tx.get(operationRef);
        if (previous.exists) {
          const saved = previous.data();
          if (saved.actorUid !== uid || saved.action !== action) {
            throw new HttpsError("already-exists", "Operation ID belongs to another request.");
          }
          return saved.result;
        }
        const timestamp = now();
        const result = { action, settings, updatedAtMs: timestamp };
        tx.set(settingsRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          ...settings,
          updatedAtMs: timestamp,
          updatedBy: uid,
        });
        tx.create(operationRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          operationId,
          actorUid: uid,
          action,
          auditToken: randomId(),
          createdAtMs: timestamp,
          result,
        });
        return result;
      });
    }

    const questionId = requiredId(data.questionId, "question");
    const questionRef = db.collection("global_question_bank").doc(questionId);
    return db.runTransaction(async (tx) => {
      const [previous, questionSnap] = await Promise.all([tx.get(operationRef), tx.get(questionRef)]);
      if (previous.exists) {
        const saved = previous.data();
        if (saved.actorUid !== uid || saved.action !== action || saved.questionId !== questionId) {
          throw new HttpsError("already-exists", "Operation ID belongs to another request.");
        }
        return saved.result;
      }
      if (!questionSnap.exists) throw new HttpsError("not-found", "Question was not found.");
      const question = questionSnap.data();
      const desiredStatus = action === "retire_question" ? "retired" : "curated";
      if (question.status !== (action === "retire_question" ? "curated" : "retired")) {
        throw new HttpsError("failed-precondition", "Question is not in a state that can be changed.");
      }
      const fingerprint = typeof question.questionFingerprint === "string" ? question.questionFingerprint : "";
      const duplicateRef = fingerprint ? db.collection("global_question_dedup").doc(fingerprint) : null;
      const duplicate = duplicateRef ? await tx.get(duplicateRef) : null;
      if (action === "restore_question" && duplicate && duplicate.exists &&
          duplicate.get("globalQuestionId") !== questionId) {
        throw new HttpsError("already-exists", "An identical active academic question already exists.");
      }
      const timestamp = now();
      const result = { action, questionId, status: desiredStatus, updatedAtMs: timestamp };
      tx.update(questionRef, {
        status: desiredStatus,
        lifecycleUpdatedAtMs: timestamp,
        lifecycleUpdatedBy: uid,
        ...(action === "retire_question" ? { retiredAtMs: timestamp } : { restoredAtMs: timestamp }),
      });
      if (duplicateRef && action === "retire_question" && duplicate && duplicate.exists &&
          duplicate.get("globalQuestionId") === questionId) {
        tx.delete(duplicateRef);
      }
      if (duplicateRef && action === "restore_question" && (!duplicate || !duplicate.exists)) {
        tx.create(duplicateRef, {
          schemaVersion: QUESTION_SCHEMA_VERSION,
          globalQuestionId: questionId,
          publishedAtMs: question.publishedAtMs || timestamp,
          restoredAtMs: timestamp,
        });
      }
      tx.create(operationRef, {
        schemaVersion: QUESTION_SCHEMA_VERSION,
        operationId,
        actorUid: uid,
        action,
        questionId,
        auditToken: randomId(),
        createdAtMs: timestamp,
        result,
      });
      return result;
    });
  };
}

module.exports = {
  DEFAULT_QUESTION_BANK_SETTINGS,
  SETTINGS_COLLECTION,
  SETTINGS_DOCUMENT,
  settingsDto,
  requestedSettings,
  loadQuestionBankSettings,
  adminTopupDto,
  revenueSummaryDto,
  createQuestionBankAdminHandler,
};
