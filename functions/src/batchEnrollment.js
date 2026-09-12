"use strict";

const { createHash, randomUUID } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const { feeBusinessKey, ledgerStatus, toMoney } = require("./financialLedgerCore");
const { trustedCreationHash } = require("./trustedCreationCore");
const { hasCurrentSubscription } = require("./subscriptionPolicy");
const {
  activityActorLabel,
  resolveTenantActorInTransaction,
  transactionTenantActivity,
} = require("./tenantActivity");

function id(data, key) {
  const value = data?.[key];
  if (typeof value !== "string" || !value.trim() || value.length > 128 || value.includes("/")) {
    throw new HttpsError("invalid-argument", `Invalid ${key}.`);
  }
  return value.trim();
}
const active = (data) => data && data.archivedAtMs == null &&
  String(data.status || "active").toLowerCase() === "active";

function bangladeshDateOrdinal(timestampMs) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Dhaka",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(timestampMs));
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  const ordinal = Number(`${value.year}${value.month}${value.day}`);
  return Number.isSafeInteger(ordinal) ? ordinal : null;
}

/** One transaction owns the membership and its fixed course charge. No payment is collected here. */
function createBatchEnrollmentHandler({ db, authorize, proratedMonthlyTerms }) {
  return async (request) => {
    const data = request.data;
    const instituteId = id(data, "instituteId");
    const studentId = id(data, "studentId");
    const batchId = id(data, "batchId");
    const enrollmentId = id(data, "enrollmentId");
    const operationId = id(data, "operationId");
    if (!Number.isSafeInteger(data.enrollmentStartMs) || data.enrollmentStartMs <= 0 ||
        data.enrollmentStartMs > Date.now() + 300000 || typeof data.admissionDateLinked !== "boolean") {
      throw new HttpsError("invalid-argument", "Confirm a valid assignment date that is not in the future.");
    }
    await authorize(request.auth, instituteId, "manage_batch");
    const instituteRef = db.collection("institutes").doc(instituteId);
    const studentRef = instituteRef.collection("students").doc(studentId);
    const batchRef = instituteRef.collection("batches").doc(batchId);
    const enrollmentRef = instituteRef.collection("batch_students").doc(enrollmentId);
    const operationRef = instituteRef.collection("enrollment_operations").doc(operationId);
    const query = instituteRef.collection("batch_students").where("studentId", "==", studentId);
    const hash = trustedCreationHash({ instituteId, studentId, batchId, enrollmentId,
      date: data.enrollmentStartMs, linked: data.admissionDateLinked });
    async function currentResult(tx, result) {
      if (!result?.enrollment?.id) throw new HttpsError("failed-precondition", "Assignment needs reconciliation.");
      const current = await tx.get(instituteRef.collection("batch_students").doc(result.enrollment.id));
      if (!current.exists || current.get("status") !== "active") {
        throw new HttpsError("failed-precondition", "This assignment was removed. Refresh before assigning again.");
      }
      let fee = null;
      if (result.fee?.id) {
        const currentFee = await tx.get(instituteRef.collection("fees").doc(result.fee.id));
        if (!currentFee.exists || currentFee.get("cancelledAtMs") != null) {
          throw new HttpsError("failed-precondition", "Course charge changed. Review its history before retrying.");
        }
        fee = { ...currentFee.data(), id: currentFee.id };
      }
      return { enrollment: { ...current.data(), id: current.id }, fee };
    }

    return db.runTransaction(async (tx) => {
      const [institute, student, batch, enrollment, operation, history] = await Promise.all([
        tx.get(instituteRef), tx.get(studentRef), tx.get(batchRef), tx.get(enrollmentRef),
        tx.get(operationRef), tx.get(query),
      ]);
      if (!institute.exists || !hasCurrentSubscription(institute.data()) || institute.get("isActive") === false) {
        throw new HttpsError("failed-precondition", "Institute access is not active.");
      }
      if (!student.exists || !active(student.data()) || !batch.exists || !active(batch.data())) {
        throw new HttpsError("failed-precondition", "Student or batch is no longer active.");
      }
      const admissionDateMs = Number(student.get("admissionDateMs"));
      const assignmentDate = bangladeshDateOrdinal(data.enrollmentStartMs);
      const admissionDate = Number.isFinite(admissionDateMs) && admissionDateMs > 0
        ? bangladeshDateOrdinal(admissionDateMs)
        : null;
      if (assignmentDate == null || (admissionDate != null && assignmentDate < admissionDate)) {
        throw new HttpsError(
          "failed-precondition",
          "Assign date cannot be before the student's admission date.",
        );
      }
      if (operation.exists) {
        if (operation.get("actorUid") !== request.auth.uid || operation.get("requestHash") !== hash) {
          throw new HttpsError("already-exists", "Operation belongs to another assignment.");
        }
        return currentResult(tx, operation.get("result"));
      }
      const existing = history.docs.filter((doc) => doc.get("batchId") === batchId && doc.get("status") === "active");
      if (existing.length > 0) {
        // Reconcile a lost response, even after process restart. Never rewrite
        // another actor's assignment or treat a different date as successful.
        if (existing.length === 1 && existing[0].get("createdByUid") === request.auth.uid &&
            existing[0].get("joinedAtMs") === data.enrollmentStartMs && existing[0].get("operationId")) {
          const previous = await tx.get(instituteRef.collection("enrollment_operations").doc(existing[0].get("operationId")));
          if (previous.exists) return currentResult(tx, previous.get("result"));
        }
        throw new HttpsError("already-exists", "Student is already assigned to this batch. Refresh the list.");
      }
      if (enrollment.exists) throw new HttpsError("already-exists", "Assignment ID already exists.");
      if (data.admissionDateLinked && (history.docs.length > 0 || data.enrollmentStartMs !== student.get("admissionDateMs"))) {
        throw new HttpsError("failed-precondition", "Assignment history changed. Refresh and confirm its own assignment date.");
      }
      const mode = String(batch.get("billingMode") || "monthly").toLowerCase();
      if (!["monthly", "course"].includes(mode)) throw new HttpsError("failed-precondition", "Unknown batch billing type.");
      let terms = { firstMonthFeePeriod: null, firstMonthFeeAmount: null };
      let fee = null;
      let feeRef = null;
      let feeKeyRef = null;
      const now = Date.now();
      const actor = await resolveTenantActorInTransaction(tx, db, request.auth, instituteId);
      if (mode === "monthly") {
        terms = proratedMonthlyTerms(toMoney(Number(batch.get("monthlyFeeAmount")), "monthly fee", { allowZero: false }), data.enrollmentStartMs);
      } else {
        const amount = toMoney(Number(batch.get("courseFeeAmount")), "course fee", { allowZero: false });
        const sourceId = `course:${batchId}`;
        const businessKey = feeBusinessKey({ studentId, batchId, feePeriod: "Course", feeType: "course_fee", sourceId });
        const feeId = `fee_${createHash("sha256").update(`${instituteId}:${businessKey}`).digest("hex").slice(0, 40)}`;
        feeRef = instituteRef.collection("fees").doc(feeId);
        feeKeyRef = instituteRef.collection("ledger_internal").doc(`fee_key_${businessKey}`);
        const [savedFee, key, allFees] = await Promise.all([
          tx.get(feeRef), tx.get(feeKeyRef), tx.get(instituteRef.collection("fees").where("studentId", "==", studentId)),
        ]);
        const equivalent = allFees.docs.find((doc) => feeBusinessKey(doc.data()) === businessKey);
        const prior = savedFee.exists ? savedFee : equivalent;
        if (prior) {
          if (prior.get("cancelledAtMs") != null || prior.get("status") === "cancelled") {
            throw new HttpsError("failed-precondition", "A previous course charge was cancelled. Review its history before reassigning.");
          }
          fee = { ...prior.data(), id: prior.id };
          feeRef = null;
        } else {
          if (key.exists) throw new HttpsError("failed-precondition", "Course ledger needs reconciliation. Contact support.");
          fee = { id: feeId, instituteId, studentId, batchId, feePeriod: "Course", feeType: "course_fee", sourceId,
            dueDateMs: Number(batch.get("startDateMs")) || data.enrollmentStartMs,
            baseAmount: amount, discountAmount: 0, lateFeeAmount: 0, totalAmount: amount,
            ...ledgerStatus(amount, 0), note: "Course enrollment fee", createdAtMs: now, updatedAtMs: now,
            cancelledAtMs: null, businessKey, ledgerVersion: 1 };
        }
      }
      const savedEnrollment = { id: enrollmentId, instituteId, studentId, batchId,
        createdByUid: request.auth.uid, operationId,
        joinedAtMs: data.enrollmentStartMs, status: "active", leftAtMs: null,
        ...terms, admissionDateLinked: data.admissionDateLinked, createdAtMs: now, updatedAtMs: now };
      // A shared student document serializes concurrent new-client assignments.
      tx.update(studentRef, { enrollmentRevisionAtMs: now });
      tx.create(enrollmentRef, savedEnrollment);
      if (feeRef) {
        tx.create(feeRef, fee);
        tx.create(feeKeyRef, { feeId: fee.id, businessKey: fee.businessKey, createdAtMs: now });
      }
      const result = { enrollment: savedEnrollment, fee };
      transactionTenantActivity(tx, db, instituteId, {
        action: "student_assigned_to_batch",
        actorUid: request.auth.uid,
        actorRole: actor.actorRole,
        actorName: actor.actorName,
        targetType: "student",
        targetId: studentId,
        summary: `${activityActorLabel(actor)} assigned ${String(student.data().fullName || studentId)} to batch ${String(batch.data().name || batchId)}`,
        now,
      }, randomUUID());
      tx.create(operationRef, { actorUid: request.auth.uid, requestHash: hash, status: "completed", result, createdAtMs: now });
      return result;
    });
  };
}
module.exports = { createBatchEnrollmentHandler };
