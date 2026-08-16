"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const { hasPermission } = require("./studentAuthCore");
const { hasCurrentSubscription } = require("./subscriptionPolicy");
const { feeBusinessKey, ledgerStatus, requestHash, toMoney } = require("./financialLedgerCore");

// A Firestore transaction can write at most 500 documents. Keeping the limit
// below that protects the exam, its operation receipt, and every generated fee.
const MAX_BILLED_STUDENTS = 450;

function requiredString(data, field, maxLength = 128) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function optionalString(data, field, maxLength = 1000) {
  if (!data || data[field] == null || data[field] === "") return null;
  if (typeof data[field] !== "string" || data[field].trim().length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return data[field].trim();
}

function timestamp(data, field) {
  const value = data && data[field];
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function money(data, field, allowZero = true) {
  try {
    return toMoney(data && data[field], field, { allowZero });
  } catch (error) {
    throw new HttpsError("invalid-argument", error.message);
  }
}

function compactId(prefix, source) {
  return `${prefix}_${createHash("sha256").update(source).digest("hex").slice(0, 40)}`;
}

function isActive(data) {
  return data && data.status === "active" && data.archivedAtMs == null;
}

async function resolveExamFeeAuthority(transaction, db, auth, instituteId) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");

  const instituteRef = db.collection("institutes").doc(instituteId);
  const appUserRef = db.collection("app_users").doc(auth.uid);
  const staffRef = instituteRef.collection("staffs").doc(auth.uid);
  const [instituteSnap, appUserSnap, staffSnap] = await Promise.all([
    transaction.get(instituteRef),
    transaction.get(appUserRef),
    transaction.get(staffRef),
  ]);
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");
  const institute = instituteSnap.data();
  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const isSuperAdmin = appUser &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(appUser.role) || appUser.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isSuperAdmin) return instituteRef;

  if (!hasCurrentSubscription(institute)) {
    throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
  }
  if (institute.isActive === false) throw new HttpsError("failed-precondition", "Institute is inactive.");
  if (auth.uid === instituteId) return instituteRef;

  const isManagedOwnerOrAdmin = appUser && appUser.instituteId === instituteId &&
    ["InstituteOwner", "owner", "instituteOwner", "institute_owner", "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isManagedOwnerOrAdmin) return instituteRef;

  const staff = staffSnap.exists ? staffSnap.data() : null;
  if (isActive(staff) && hasPermission(staff.permissions, "manage_exams") &&
      hasPermission(staff.permissions, "collect_fee")) {
    return instituteRef;
  }
  throw new HttpsError("permission-denied", "Exam fee creation is not allowed.");
}

function parseRequest(data) {
  const operationId = requiredString(data, "operationId", 128);
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(operationId)) {
    throw new HttpsError("invalid-argument", "Invalid operation ID.");
  }
  const examId = requiredString(data, "examId", 128);
  const instituteId = requiredString(data, "instituteId", 128);
  const batchId = requiredString(data, "batchId", 128);
  const examName = requiredString(data, "examName", 120);
  const subject = optionalString(data, "subject", 120);
  const teacherName = optionalString(data, "teacherName", 120);
  const note = optionalString(data, "note", 1000);
  const totalMarks = money(data, "totalMarks", false);
  const passingMarks = money(data, "passingMarks");
  const examFeeAmount = money(data, "examFeeAmount", false);
  if (passingMarks > totalMarks) {
    throw new HttpsError("invalid-argument", "Passing marks cannot exceed total marks.");
  }
  return {
    operationId, examId, instituteId, batchId, examName, subject, teacherName, note,
    totalMarks, passingMarks, examFeeAmount, examDateMs: timestamp(data, "examDateMs"),
  };
}

function publicExam(exam) {
  return { ...exam };
}

function publicFee(fee) {
  return { ...fee };
}

function createExamFeeBillingHandler({ db }) {
  return async (request) => {
    const input = parseRequest(request.data);
    const now = Date.now();
    const fingerprint = requestHash(input);

    return db.runTransaction(async (transaction) => {
      const instituteRef = await resolveExamFeeAuthority(transaction, db, request.auth, input.instituteId);
      const examRef = instituteRef.collection("exams").doc(input.examId);
      const batchRef = instituteRef.collection("batches").doc(input.batchId);
      const operationRef = instituteRef.collection("exam_fee_operations").doc(input.operationId);
      const enrollmentQuery = instituteRef.collection("batch_students")
        .where("batchId", "==", input.batchId)
        .where("status", "==", "active");
      const [operationSnap, existingExamSnap, batchSnap, enrollmentSnap] = await Promise.all([
        transaction.get(operationRef),
        transaction.get(examRef),
        transaction.get(batchRef),
        transaction.get(enrollmentQuery),
      ]);

      if (operationSnap.exists) {
        if (operationSnap.get("actorUid") !== request.auth.uid || operationSnap.get("requestHash") !== fingerprint) {
          throw new HttpsError("permission-denied", "This exam fee request does not belong to this session.");
        }
        return operationSnap.get("result");
      }
      if (existingExamSnap.exists) throw new HttpsError("already-exists", "This exam already exists.");
      if (!isActive(batchSnap.exists ? batchSnap.data() : null)) {
        throw new HttpsError("failed-precondition", "The selected batch is not active.");
      }

      const enrolledIds = [...new Set(enrollmentSnap.docs
        .map((doc) => doc.get("studentId"))
        .filter((studentId) => typeof studentId === "string" && studentId.trim()))];
      if (enrolledIds.length > MAX_BILLED_STUDENTS) {
        throw new HttpsError(
          "resource-exhausted",
          `This batch has more than ${MAX_BILLED_STUDENTS} students. Please contact support to safely create the exam fee.`,
        );
      }
      const studentRefs = enrolledIds.map((studentId) => instituteRef.collection("students").doc(studentId));
      const studentSnaps = await Promise.all(studentRefs.map((studentRef) => transaction.get(studentRef)));
      const activeStudentIds = studentSnaps
        .filter((studentSnap) => isActive(studentSnap.exists ? studentSnap.data() : null))
        .map((studentSnap) => studentSnap.id);

      const exam = {
        id: input.examId,
        instituteId: input.instituteId,
        batchId: input.batchId,
        examName: input.examName,
        subject: input.subject,
        examDateMs: input.examDateMs,
        totalMarks: input.totalMarks,
        passingMarks: input.passingMarks,
        examFeeAmount: input.examFeeAmount,
        teacherName: input.teacherName,
        note: input.note,
        status: "scheduled",
        createdAtMs: now,
        updatedAtMs: now,
        archivedAtMs: null,
      };
      const fees = activeStudentIds.map((studentId) => {
        const businessKey = feeBusinessKey({
          studentId,
          batchId: input.batchId,
          feePeriod: input.examName,
          feeType: "exam_fee",
          sourceId: input.examId,
        });
        const amounts = ledgerStatus(input.examFeeAmount, 0);
        return {
          id: compactId("fee", `${input.instituteId}:${businessKey}`),
          instituteId: input.instituteId,
          studentId,
          batchId: input.batchId,
          feePeriod: input.examName,
          feeType: "exam_fee",
          sourceId: input.examId,
          dueDateMs: input.examDateMs,
          baseAmount: input.examFeeAmount,
          discountAmount: 0,
          lateFeeAmount: 0,
          totalAmount: input.examFeeAmount,
          paidAmount: amounts.paidAmount,
          dueAmount: amounts.dueAmount,
          status: amounts.status,
          note: `Exam fee · ${input.examName}`,
          createdAtMs: now,
          updatedAtMs: now,
          cancelledAtMs: null,
          businessKey,
          ledgerVersion: 1,
        };
      });
      const result = {
        operationId: input.operationId,
        exam: publicExam(exam),
        fees: fees.map(publicFee),
        billedStudentCount: fees.length,
      };
      transaction.create(examRef, exam);
      fees.forEach((fee) => transaction.create(instituteRef.collection("fees").doc(fee.id), fee));
      transaction.create(operationRef, {
        actorUid: request.auth.uid,
        requestHash: fingerprint,
        status: "completed",
        result,
        createdAtMs: now,
        completedAtMs: now,
      });
      return result;
    });
  };
}

module.exports = { createExamFeeBillingHandler };
