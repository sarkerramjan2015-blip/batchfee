"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const {
  DAY_MS,
  addCalendarMonths,
  maskedTransactionReference,
  quoteForPlan,
  subscriptionStartMs,
  subscriptionStatusFor,
  transactionReferenceHash,
} = require("./subscriptionBillingCore");
const { planFromSnapshot } = require("./defaultSubscriptionPlans");
const { FREE_TRIAL_STUDENT_LIMIT } = require("./subscriptionPolicy");

const ALLOWED_ACTIONS = new Set([
  "submit_request",
  "approve_request",
  "reject_request",
  "extend_subscription",
  "set_institute_blocked",
  "manage_institute_subscription",
]);
const SUPER_ADMIN_ROLES = new Set(["SuperAdmin", "superAdmin", "super_admin"]);
const PAYMENT_METHODS = new Set(["bkash", "nagad"]);

function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue);
  if (value && typeof value === "object") {
    return Object.keys(value).sort().reduce((result, key) => {
      result[key] = stableValue(value[key]);
      return result;
    }, {});
  }
  return value;
}

function requestHash(data) {
  return createHash("sha256").update(JSON.stringify(stableValue(data))).digest("hex");
}

function requiredString(data, field, maxLength = 128) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function optionalString(data, field, maxLength = 500) {
  if (!data || data[field] == null || data[field] === "") return null;
  if (typeof data[field] !== "string" || data[field].trim().length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return data[field].trim();
}

function requiredSafeInteger(data, field, min, max) {
  const value = data && data[field];
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function requiredBoolean(data, field) {
  if (!data || typeof data[field] !== "boolean") {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return data[field];
}

function activeAppUser(data) {
  return data && (!Object.prototype.hasOwnProperty.call(data, "status") || data.status === "active");
}

function isSuperAdmin(data) {
  return activeAppUser(data) && (SUPER_ADMIN_ROLES.has(data.role) || data.platformRole === "root");
}

function isBillingAdmin(data) {
  return activeAppUser(data) && (isSuperAdmin(data) || data.platformRole === "billing");
}

function isManagedInstituteOwner(data, instituteId) {
  return activeAppUser(data) && data.instituteId === instituteId &&
    ["InstituteOwner", "owner"].includes(data.role);
}

function asNumber(value, fallback = 0) {
  return typeof value === "number" && Number.isSafeInteger(value) ? value : fallback;
}

function plainInstitute(institute) {
  return {
    currentPlanId: institute.currentPlanId || "plan_free_trial",
    subscriptionStatus: institute.subscriptionStatus || "active",
    isActive: institute.isActive !== false,
    trialEndDate: asNumber(institute.trialEndDate),
    currentPeriodEndMs: asNumber(institute.currentPeriodEndMs),
    studentLimit: asNumber(institute.studentLimit),
    staffLimit: asNumber(institute.staffLimit),
  };
}

function paidPeriodEnd(institute) {
  const currentPeriodEnd = asNumber(institute.currentPeriodEndMs);
  return currentPeriodEnd > 0 ? currentPeriodEnd : asNumber(institute.trialEndDate);
}

function canonicalSubscriptionStatus(planId, periodEndMs, now) {
  return subscriptionStatusFor(planId, periodEndMs, now);
}

function isFreeTrialPlan(planId) {
  return planId === "plan_free_trial";
}

function activeStudentCountQuery(instituteRef) {
  // `status` is present on both legacy and current active student documents.
  // Querying `archivedAtMs == null` would exclude legacy records that predate
  // that optional field and could allow an ineligible paid plan.
  return instituteRef.collection("students").where("status", "==", "active").count();
}

function assertPlanSupportsStudentCount(plan, activeStudentCount) {
  const limit = Number(plan?.maxStudents);
  if (!Number.isSafeInteger(limit) || limit < 1) {
    throw new HttpsError("failed-precondition", "Selected subscription plan has no valid student limit.");
  }
  if (activeStudentCount > limit) {
    throw new HttpsError(
      "failed-precondition",
      `This plan supports up to ${limit} students, but your institute has ${activeStudentCount} active students. Choose an eligible plan.`,
    );
  }
}

function requirePendingRequest(requestSnap) {
  if (!requestSnap.exists) throw new HttpsError("not-found", "Subscription request not found.");
  const request = requestSnap.data();
  if (request.status !== "pending") {
    throw new HttpsError("failed-precondition", "This subscription request was already reviewed.");
  }
  if (!request.quote || typeof request.quote !== "object" ||
      !Number.isFinite(request.quote.monthlyPriceBdt) ||
      !Number.isFinite(request.quote.amountBdt)) {
    throw new HttpsError(
      "failed-precondition",
      "This legacy request has no secure server quote. Ask the owner to submit it again.",
    );
  }
  const expected = quoteForPlan(request.quote.monthlyPriceBdt, request.durationMonths);
  if (expected !== request.quote.amountBdt || expected !== request.amountPaid) {
    throw new HttpsError("failed-precondition", "Subscription request price verification failed.");
  }
  return request;
}

async function loadAuthority(transaction, db, auth, instituteId, requiredRole) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const appUserRef = db.collection("app_users").doc(auth.uid);
  const [instituteSnap, appUserSnap] = await Promise.all([
    transaction.get(instituteRef),
    transaction.get(appUserRef),
  ]);
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");
  const institute = instituteSnap.data();
  if (institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "Institute is archived.");
  }
  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const superAdmin = isSuperAdmin(appUser);
  const billingAdmin = isBillingAdmin(appUser);
  if (requiredRole === "super_admin" && !superAdmin) {
    throw new HttpsError("permission-denied", "Only a Super Admin can perform this action.");
  }
  if (requiredRole === "billing_admin" && !billingAdmin) {
    throw new HttpsError("permission-denied", "Only a Billing or Root administrator can perform this action.");
  }
  const ownerUid = typeof institute.ownerUid === "string" ? institute.ownerUid : instituteId;
  if (requiredRole === "owner" && !superAdmin && auth.uid !== ownerUid &&
      !isManagedInstituteOwner(appUser, instituteId)) {
    throw new HttpsError("permission-denied", "Only the institute owner can submit a subscription request.");
  }
  if (requiredRole === "owner" && institute.isActive === false) {
    throw new HttpsError("failed-precondition", "This institute is blocked. Contact support to restore access.");
  }
  return { instituteRef, institute, appUser, isSuperAdmin: superAdmin, isBillingAdmin: billingAdmin };
}

function applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now, before, after, details = {}) {
  transaction.create(instituteRef.collection("subscription_audit").doc(operationId), {
    operationId,
    action,
    actorUid,
    createdAtMs: now,
    before,
    after,
    ...details,
  });
}

function completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now) {
  transaction.create(instituteRef.collection("subscription_operations").doc(operationId), {
    actorUid,
    requestHash: hash,
    action,
    result,
    createdAtMs: now,
  });
}

function requestResult(requestId, request) {
  return { request: { requestId, ...request } };
}

function receiptResult(receiptId, receipt) {
  return { receipt: { receiptId, ...receipt } };
}

function createSubscriptionBillingHandler({ db, FieldValue }) {
  return async (request) => {
    const data = request.data || {};
    const instituteId = requiredString(data, "instituteId");
    const operationId = requiredString(data, "operationId");
    const action = requiredString(data, "action", 64);
    if (!/^[A-Za-z0-9_-]{16,128}$/.test(operationId) || !ALLOWED_ACTIONS.has(action)) {
      throw new HttpsError("invalid-argument", "Invalid subscription operation.");
    }
    const actorUid = request.auth && request.auth.uid;
    if (!actorUid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const hash = requestHash(data);
    const now = Date.now();

    return db.runTransaction(async (transaction) => {
      const requiredRole = action === "submit_request"
        ? "owner"
        : (["approve_request", "reject_request", "extend_subscription"].includes(action)
          ? "billing_admin" : "super_admin");
      const authority = await loadAuthority(transaction, db, request.auth, instituteId, requiredRole);
      const instituteRef = authority.instituteRef;
      const operationRef = instituteRef.collection("subscription_operations").doc(operationId);
      const operationSnap = await transaction.get(operationRef);
      if (operationSnap.exists) {
        if (operationSnap.get("actorUid") !== actorUid || operationSnap.get("requestHash") !== hash) {
          throw new HttpsError("already-exists", "Operation ID was already used for another request.");
        }
        return operationSnap.get("result");
      }

      if (action === "submit_request") {
        const requestedPlanId = requiredString(data, "requestedPlanId");
        const durationMonths = requiredSafeInteger(data, "durationMonths", 1, 12);
        const normalizedPaymentMethod = requiredString(data, "paymentMethod", 24).toLowerCase();
        if (!PAYMENT_METHODS.has(normalizedPaymentMethod) || ![1, 6, 12].includes(durationMonths)) {
          throw new HttpsError("invalid-argument", "Unsupported payment method or subscription duration.");
        }
        const transactionReference = requiredString(data, "transactionReference", 80);
        let paymentReferenceHash;
        let transactionLast4;
        try {
          paymentReferenceHash = transactionReferenceHash(transactionReference);
          transactionLast4 = maskedTransactionReference(transactionReference);
        } catch (error) {
          throw new HttpsError("invalid-argument", error.message);
        }
        const planRef = db.collection("subscription_plans").doc(requestedPlanId);
        const activeStudentCount = activeStudentCountQuery(instituteRef);
        const duplicateQuery = db.collection("subscriptionRequests")
          .where("paymentReferenceHash", "==", paymentReferenceHash)
          .limit(1);
        const [planSnap, duplicateSnap, studentCountSnap] = await Promise.all([
          transaction.get(planRef),
          transaction.get(duplicateQuery),
          transaction.get(activeStudentCount),
        ]);
        const plan = planFromSnapshot(planSnap, requestedPlanId);
        if (!plan) throw new HttpsError("not-found", "Selected subscription plan is no longer available.");
        if (isFreeTrialPlan(requestedPlanId) || Number(plan.priceBdt) <= 0) {
          throw new HttpsError("failed-precondition", "Free Trial cannot be purchased. Choose a paid plan.");
        }
        const currentStudentCount = studentCountSnap.data().count;
        assertPlanSupportsStudentCount(plan, currentStudentCount);
        if (!duplicateSnap.empty) {
          throw new HttpsError("already-exists", "This payment transaction was already submitted.");
        }
        let amountPaid;
        try {
          amountPaid = quoteForPlan(plan.priceBdt, durationMonths);
        } catch (error) {
          throw new HttpsError("failed-precondition", error.message);
        }
        const requestId = `SUBREQ_${operationId}`;
        const requestRef = db.collection("subscriptionRequests").doc(requestId);
        const institute = authority.institute;
        const requestData = {
          instituteId,
          instituteName: typeof institute.instituteName === "string" ? institute.instituteName : "Institute",
          ownerName: typeof institute.ownerName === "string" ? institute.ownerName : "",
          institutePhone: typeof institute.phone === "string"
            ? institute.phone : (typeof institute.whatsappNumber === "string" ? institute.whatsappNumber : ""),
          requestedPlanId,
          planName: typeof plan.name === "string" ? plan.name : requestedPlanId,
          activeStudentCountAtRequest: currentStudentCount,
          durationMonths,
          amountPaid,
          paymentMethod: normalizedPaymentMethod,
          transactionLast4,
          paymentReferenceHash,
          quote: {
            monthlyPriceBdt: Number(plan.priceBdt),
            amountBdt: amountPaid,
            durationMonths,
            quotedAtMs: now,
          },
          status: "pending",
          requestSentAt: now,
          submittedBy: actorUid,
          reviewedBy: "",
          reviewedAt: 0,
          reviewerNote: "",
        };
        transaction.create(requestRef, requestData);
        applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now, {}, {
          requestId,
          requestedPlanId,
          amountPaid,
        }, { paymentMethod: normalizedPaymentMethod, transactionLast4 });
        const result = requestResult(requestId, requestData);
        completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now);
        return result;
      }

      if (action === "approve_request") {
        const requestId = requiredString(data, "requestId");
        const requestRef = db.collection("subscriptionRequests").doc(requestId);
        const [requestSnap, studentCountSnap] = await Promise.all([
          transaction.get(requestRef),
          transaction.get(activeStudentCountQuery(instituteRef)),
        ]);
        const subscriptionRequest = requirePendingRequest(requestSnap);
        if (subscriptionRequest.instituteId !== instituteId) {
          throw new HttpsError("permission-denied", "Subscription request belongs to another institute.");
        }
        const requestedPlanRef = db.collection("subscription_plans").doc(subscriptionRequest.requestedPlanId);
        const requestedPlanSnap = await transaction.get(requestedPlanRef);
        const requestedPlan = planFromSnapshot(requestedPlanSnap, subscriptionRequest.requestedPlanId);
        if (!requestedPlan) throw new HttpsError("not-found", "Selected subscription plan is no longer available.");
        if (isFreeTrialPlan(subscriptionRequest.requestedPlanId) || Number(requestedPlan.priceBdt) <= 0) {
          throw new HttpsError("failed-precondition", "Free Trial requests cannot be approved as paid subscriptions.");
        }
        const currentStudentCount = studentCountSnap.data().count;
        assertPlanSupportsStudentCount(requestedPlan, currentStudentCount);
        const startDateMs = subscriptionStartMs(paidPeriodEnd(authority.institute), now);
        const endDateMs = addCalendarMonths(startDateMs, subscriptionRequest.durationMonths);
        const receiptId = `SUBREC_${operationId}`;
        const receiptRef = instituteRef.collection("subscription_receipts").doc(receiptId);
        const receipt = {
          receiptId,
          receiptType: "subscription",
          receiptNumber: `SUB-${now}-${operationId.slice(-6).toUpperCase()}`,
          requestId,
          instituteId,
          instituteName: subscriptionRequest.instituteName,
          ownerName: subscriptionRequest.ownerName,
          ownerPhone: subscriptionRequest.institutePhone || "",
          ownerEmail: typeof authority.institute.email === "string" ? authority.institute.email : "",
          instituteCode: typeof authority.institute.instituteCode === "string" ? authority.institute.instituteCode : "",
          instituteAddress: typeof authority.institute.address === "string" ? authority.institute.address : "",
          planId: subscriptionRequest.requestedPlanId,
          planName: subscriptionRequest.planName || subscriptionRequest.requestedPlanId,
          durationMonths: subscriptionRequest.durationMonths,
          amountPaid: subscriptionRequest.amountPaid,
          paymentMethod: subscriptionRequest.paymentMethod,
          transactionLast4: subscriptionRequest.transactionLast4,
          startDateMs,
          endDateMs,
          approvedAt: now,
          approvedBy: actorUid,
        };
        const afterInstitute = {
          currentPlanId: subscriptionRequest.requestedPlanId,
          currentPeriodEndMs: endDateMs,
          studentLimit: requestedPlan.maxStudents,
          staffLimit: requestedPlan.maxUsers,
          subscriptionStatus: "active",
          isActive: true,
        };
        transaction.update(requestRef, {
          status: "approved",
          reviewedBy: actorUid,
          reviewedAt: now,
          receiptId,
          receiptNumber: receipt.receiptNumber,
          startDateMs,
          endDateMs,
          ownerEmail: receipt.ownerEmail,
          instituteCode: receipt.instituteCode,
          instituteAddress: receipt.instituteAddress,
        });
        transaction.update(instituteRef, afterInstitute);
        transaction.create(receiptRef, receipt);
        applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now,
          plainInstitute(authority.institute), afterInstitute, {
            requestId,
            receiptId,
            durationMonths: subscriptionRequest.durationMonths,
          });
        const result = {
          ...receiptResult(receiptId, receipt),
          institute: afterInstitute,
        };
        completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now);
        return result;
      }

      if (action === "reject_request") {
        const requestId = requiredString(data, "requestId");
        const note = optionalString(data, "note", 500) || "Rejected by Super Admin.";
        const requestRef = db.collection("subscriptionRequests").doc(requestId);
        const requestSnap = await transaction.get(requestRef);
        const subscriptionRequest = requirePendingRequest(requestSnap);
        if (subscriptionRequest.instituteId !== instituteId) {
          throw new HttpsError("permission-denied", "Subscription request belongs to another institute.");
        }
        const after = { status: "rejected", reviewedBy: actorUid, reviewedAt: now, reviewerNote: note };
        transaction.update(requestRef, after);
        applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now,
          { requestId, status: "pending" }, { requestId, ...after });
        const result = { request: { requestId, ...subscriptionRequest, ...after } };
        completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now);
        return result;
      }

      if (action === "extend_subscription") {
        const daysToAdd = requiredSafeInteger(data, "daysToAdd", 1, 3650);
        const reason = optionalString(data, "reason", 500) || "Manual platform access extension";
        const startDateMs = subscriptionStartMs(paidPeriodEnd(authority.institute), now);
        const endDateMs = startDateMs + daysToAdd * DAY_MS;
        const planId = authority.institute.currentPlanId || "plan_free_trial";
        const after = {
          currentPeriodEndMs: endDateMs,
          subscriptionStatus: canonicalSubscriptionStatus(planId, endDateMs, now),
          isActive: true,
          preBlockSubscriptionStatus: FieldValue.delete(),
        };
        transaction.update(instituteRef, after);
        applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now,
          plainInstitute(authority.institute), { ...after, preBlockSubscriptionStatus: null },
          { daysToAdd, startDateMs, reason });
        const result = { institute: { ...after, preBlockSubscriptionStatus: null } };
        completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now);
        return result;
      }

      if (action === "set_institute_blocked") {
        const blocked = requiredBoolean(data, "blocked");
        const before = plainInstitute(authority.institute);
        let after;
        if (blocked) {
          const previousStatus = canonicalSubscriptionStatus(
            authority.institute.currentPlanId || "plan_free_trial",
            paidPeriodEnd(authority.institute),
            now,
          );
          after = {
            isActive: false,
            subscriptionStatus: "blocked",
            preBlockSubscriptionStatus: previousStatus,
          };
        } else {
          const planId = authority.institute.currentPlanId || "plan_free_trial";
          after = {
            isActive: true,
            subscriptionStatus: canonicalSubscriptionStatus(planId, paidPeriodEnd(authority.institute), now),
            preBlockSubscriptionStatus: FieldValue.delete(),
          };
        }
        transaction.update(instituteRef, after);
        applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now,
          before, { ...after, preBlockSubscriptionStatus: blocked ? after.preBlockSubscriptionStatus : null }, { blocked });
        const result = { institute: { ...after, preBlockSubscriptionStatus: blocked ? after.preBlockSubscriptionStatus : null } };
        completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now);
        return result;
      }

      const newExpiryMs = requiredSafeInteger(data, "newExpiryMs", 1, 4_102_444_800_000);
      const requestedStudentLimit = requiredSafeInteger(data, "studentLimit", 0, 1_000_000);
      const staffLimit = requiredSafeInteger(data, "staffLimit", 1, 100_000);
      const planId = requiredString(data, "planId");
      const isActive = requiredBoolean(data, "isActive");
      const planRef = db.collection("subscription_plans").doc(planId);
      const [planSnap, studentCountSnap] = await Promise.all([
        transaction.get(planRef),
        transaction.get(activeStudentCountQuery(instituteRef)),
      ]);
      const selectedPlan = planFromSnapshot(planSnap, planId);
      if (!selectedPlan) {
        throw new HttpsError("not-found", "Selected subscription plan is no longer available.");
      }
      const currentStudentCount = studentCountSnap.data().count;
      const freeTrial = isFreeTrialPlan(planId);
      const studentLimit = freeTrial ? FREE_TRIAL_STUDENT_LIMIT : requestedStudentLimit;
      if (!freeTrial && studentLimit < 1) {
        throw new HttpsError("invalid-argument", "Paid plans require a positive student limit.");
      }
      if (isActive && !freeTrial) {
        assertPlanSupportsStudentCount(selectedPlan, currentStudentCount);
        if (studentLimit < currentStudentCount) {
          throw new HttpsError(
            "failed-precondition",
            `Student limit cannot be below the institute's ${currentStudentCount} active students.`,
          );
        }
      }
      const effectiveStatus = canonicalSubscriptionStatus(planId, newExpiryMs, now);
      const after = isActive ? {
        currentPlanId: planId,
        currentPeriodEndMs: newExpiryMs,
        studentLimit,
        staffLimit,
        subscriptionStatus: effectiveStatus,
        isActive: true,
        preBlockSubscriptionStatus: FieldValue.delete(),
      } : {
        currentPlanId: planId,
        currentPeriodEndMs: newExpiryMs,
        studentLimit,
        staffLimit,
        subscriptionStatus: "blocked",
        isActive: false,
        preBlockSubscriptionStatus: effectiveStatus,
      };
      transaction.update(instituteRef, after);
      applyOperationAudit(transaction, instituteRef, operationId, action, actorUid, now,
        plainInstitute(authority.institute), {
          ...after,
          preBlockSubscriptionStatus: isActive ? null : effectiveStatus,
        });
      const result = {
        institute: {
          ...after,
          preBlockSubscriptionStatus: isActive ? null : effectiveStatus,
        },
      };
      completedOperation(transaction, instituteRef, operationId, actorUid, hash, action, result, now);
      return result;
    });
  };
}

module.exports = { createSubscriptionBillingHandler };
