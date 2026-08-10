"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const { hasPermission } = require("./studentAuthCore");
const {
  MONEY_EPSILON,
  feeBusinessKey,
  ledgerStatus,
  paymentReferenceKey,
  receiptNumber,
  requestHash,
  toMoney,
} = require("./financialLedgerCore");

const ALLOWED_ACTIONS = new Set([
  "create_fee",
  "collect_payment",
  "adjust_and_collect",
  "reverse_payment",
  "owner_edit_payment",
  "owner_delete_payment",
]);

const OWNER_ONLY_ACTIONS = new Set([
  "owner_edit_payment",
  "owner_delete_payment",
]);

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

function requiredTimestamp(data, field) {
  const value = data && data[field];
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function money(data, field, options) {
  return validatedMoney(data && data[field], field, options);
}

function validatedMoney(value, field, options) {
  try {
    return toMoney(value, field, options);
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

async function resolveFinanceAuthority(transaction, db, auth, instituteId) {
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

  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const isSuperAdmin = appUser &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(appUser.role) || appUser.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isSuperAdmin) return { instituteRef, canManagePaymentHistory: true };

  if (instituteSnap.get("isActive") === false) {
    throw new HttpsError("failed-precondition", "Institute is inactive.");
  }
  if (auth.uid === instituteId) return { instituteRef, canManagePaymentHistory: true };

  const isManagedOwner = appUser && appUser.instituteId === instituteId &&
    ["InstituteOwner", "owner", "instituteOwner", "institute_owner"].includes(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isManagedOwner) return { instituteRef, canManagePaymentHistory: true };

  const isManagedAdmin = appUser && appUser.instituteId === instituteId &&
    ["InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  if (isManagedAdmin) return { instituteRef, canManagePaymentHistory: false };

  const staff = staffSnap.exists ? staffSnap.data() : null;
  if (isActive(staff) && hasPermission(staff.permissions, "collect_fee")) {
    return { instituteRef, canManagePaymentHistory: false };
  }
  throw new HttpsError("permission-denied", "Financial mutation is not allowed.");
}

async function readEffectivePaid(transaction, instituteRef, feeId) {
  const paymentsQuery = instituteRef.collection("payments").where("feeId", "==", feeId);
  const reversalsQuery = instituteRef.collection("payment_reversals").where("feeId", "==", feeId);
  const [paymentsSnap, reversalsSnap] = await Promise.all([
    transaction.get(paymentsQuery),
    transaction.get(reversalsQuery),
  ]);
  const reversedPaymentIds = new Set(reversalsSnap.docs.map((doc) => doc.get("paymentId")));
  const paidAmount = paymentsSnap.docs
    .filter((doc) => {
      const status = doc.get("status");
      return (!status || status === "completed") && !reversedPaymentIds.has(doc.id);
    })
    .reduce((sum, doc) => sum + Number(doc.get("amount") || 0), 0);
  return Math.round(paidAmount * 100) / 100;
}

async function planReceipt(transaction, instituteRef, operationId, receiptGroupId, actorUid, studentId) {
  const safeGroupId = receiptGroupId || operationId;
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(safeGroupId)) {
    throw new HttpsError("invalid-argument", "Invalid receipt group.");
  }
  const groupRef = instituteRef.collection("ledger_internal").doc(`receipt_group_${safeGroupId}`);
  const groupSnap = await transaction.get(groupRef);
  if (groupSnap.exists) {
    if (groupSnap.get("actorUid") !== actorUid || groupSnap.get("studentId") !== studentId) {
      throw new HttpsError("permission-denied", "Receipt group does not belong to this operation.");
    }
    const existingNumber = groupSnap.get("receiptNumber");
    const createdAtMs = Number(groupSnap.get("createdAtMs"));
    if (!/^REC-[0-9]{10}$/.test(existingNumber) || !Number.isSafeInteger(createdAtMs) ||
      Date.now() - createdAtMs > 10 * 60 * 1000) {
      throw new HttpsError("failed-precondition", "Receipt group is no longer valid.");
    }
    return { number: existingNumber, groupRef, sequenceRef: null, sequence: null };
  }

  const sequenceRef = instituteRef.collection("ledger_internal").doc("receipt_sequence");
  const sequenceSnap = await transaction.get(sequenceRef);
  const lastValue = Number(sequenceSnap.get("lastValue") || 0);
  if (!Number.isSafeInteger(lastValue) || lastValue < 0 || lastValue >= Number.MAX_SAFE_INTEGER) {
    throw new HttpsError("failed-precondition", "Receipt sequence is invalid.");
  }
  let sequence = lastValue + 1;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const candidate = receiptNumber(sequence);
    const collisionQuery = instituteRef.collection("receipts")
      .where("receiptNumber", "==", candidate)
      .limit(1);
    const collisionSnap = await transaction.get(collisionQuery);
    if (collisionSnap.empty) {
      return { number: candidate, groupRef, sequenceRef, sequence };
    }
    sequence += 1;
  }
  throw new HttpsError(
    "failed-precondition",
    "Receipt sequence requires administrative reconciliation.",
  );
}

function applyReceiptPlan(transaction, plan, actorUid, studentId, now) {
  if (!plan.sequenceRef) return;
  transaction.set(plan.sequenceRef, { lastValue: plan.sequence, updatedAtMs: now }, { merge: true });
  transaction.create(plan.groupRef, {
    receiptNumber: plan.number,
    actorUid,
    studentId,
    createdAtMs: now,
  });
}

function paymentAndReceipt({
  instituteId,
  operationId,
  fee,
  amount,
  paymentMethod,
  transactionId,
  receiptPlan,
  paymentDateMs,
  actorUid,
  note,
  receiptText,
  now,
  ledger,
}) {
  const paymentId = compactId("pay", `${instituteId}:${operationId}`);
  const payment = {
    id: paymentId,
    instituteId,
    feeId: fee.id,
    studentId: fee.studentId,
    amount,
    paymentMethod,
    transactionId,
    receiptNumber: receiptPlan.number,
    paymentDateMs,
    collectedByUserId: actorUid,
    status: "completed",
    note,
    createdAtMs: now,
    updatedAtMs: now,
    operationId,
    ledgerVersion: 1,
  };
  const receipt = {
    id: compactId("receipt", paymentId),
    instituteId,
    paymentId,
    feeId: fee.id,
    studentId: fee.studentId,
    receiptNumber: receiptPlan.number,
    receiptDateMs: paymentDateMs,
    totalAmount: fee.totalAmount,
    paidAmount: ledger.paidAmount,
    dueAmount: ledger.dueAmount,
    paymentMethod,
    receiptText: receiptText || `Payment of ${amount.toFixed(2)} received.`,
    createdAtMs: now,
    operationId,
    ledgerVersion: 1,
  };
  return { payment, receipt };
}

function publicResult(
  operationId,
  action,
  feeOrFees,
  payments = [],
  receipts = [],
  reversals = [],
  deletions = {},
) {
  const fees = Array.isArray(feeOrFees) ? feeOrFees : feeOrFees ? [feeOrFees] : [];
  return {
    operationId,
    action,
    fees,
    payments,
    receipts,
    reversals,
    deletedPaymentIds: deletions.deletedPaymentIds || [],
    deletedReceiptIds: deletions.deletedReceiptIds || [],
  };
}

function createFinancialLedgerHandler({ db }) {
  return async (request) => {
    const data = request.data || {};
    const instituteId = requiredString(data, "instituteId");
    const operationId = requiredString(data, "operationId");
    const action = requiredString(data, "action", 40);
    if (!/^[A-Za-z0-9_-]{16,128}$/.test(operationId) || !ALLOWED_ACTIONS.has(action)) {
      throw new HttpsError("invalid-argument", "Invalid financial operation.");
    }
    if (!request.auth || !request.auth.uid) {
      throw new HttpsError("unauthenticated", "Sign in is required.");
    }
    const actorUid = request.auth.uid;
    const now = Date.now();
    const hash = requestHash(data);

    return db.runTransaction(async (transaction) => {
      const authority = await resolveFinanceAuthority(
        transaction,
        db,
        request.auth,
        instituteId,
      );
      const instituteRef = authority.instituteRef;
      if (OWNER_ONLY_ACTIONS.has(action) && !authority.canManagePaymentHistory) {
        throw new HttpsError(
          "permission-denied",
          "Only the institute owner can edit or permanently delete a saved payment.",
        );
      }
      const operationRef = instituteRef.collection("financial_operations").doc(operationId);
      const operationSnap = await transaction.get(operationRef);
      if (operationSnap.exists) {
        if (operationSnap.get("requestHash") !== hash || operationSnap.get("actorUid") !== actorUid) {
          throw new HttpsError("already-exists", "Operation ID was already used for another request.");
        }
        return operationSnap.get("result");
      }

      let result;
      if (action === "create_fee") {
        const studentId = requiredString(data, "studentId");
        const batchId = optionalString(data, "batchId", 128);
        const feePeriod = requiredString(data, "feePeriod", 80);
        const feeType = requiredString(data, "feeType", 40).toLowerCase();
        const dueDateMs = requiredTimestamp(data, "dueDateMs");
        const baseAmount = money(data, "baseAmount");
        const discountAmount = money(data, "discountAmount");
        const lateFeeAmount = money(data, "lateFeeAmount");
        const totalAmount = validatedMoney(
          baseAmount - discountAmount + lateFeeAmount,
          "totalAmount",
        );
        const initialAmount = data.amount == null ? 0 : money(data, "amount");
        if (initialAmount - totalAmount > MONEY_EPSILON) {
          throw new HttpsError("failed-precondition", "Payment exceeds remaining due.");
        }
        const businessKey = feeBusinessKey({ studentId, batchId, feePeriod, feeType });
        const feeId = compactId("fee", `${instituteId}:${businessKey}`);
        const feeRef = instituteRef.collection("fees").doc(feeId);
        const keyRef = instituteRef.collection("ledger_internal").doc(`fee_key_${businessKey}`);
        const studentRef = instituteRef.collection("students").doc(studentId);
        const legacyQuery = instituteRef.collection("fees").where("studentId", "==", studentId);
        const [studentSnap, feeSnap, keySnap, legacySnap] = await Promise.all([
          transaction.get(studentRef),
          transaction.get(feeRef),
          transaction.get(keyRef),
          transaction.get(legacyQuery),
        ]);
        if (!studentSnap.exists) {
          throw new HttpsError("failed-precondition", "Student is not available for fee creation.");
        }
        const studentStatus = studentSnap.get("status");
        if (studentSnap.get("archivedAtMs") != null ||
          ["archived", "inactive", "blocked"].includes(studentStatus)) {
          throw new HttpsError("failed-precondition", "Student is not available for fee creation.");
        }
        const duplicate = legacySnap.docs.some((doc) => doc.get("cancelledAtMs") == null &&
          feeBusinessKey({
            studentId: doc.get("studentId"),
            batchId: doc.get("batchId"),
            feePeriod: doc.get("feePeriod"),
            feeType: doc.get("feeType"),
          }) === businessKey);
        if (feeSnap.exists || keySnap.exists || duplicate) {
          throw new HttpsError("already-exists", "This fee already exists.");
        }

        const paymentMethod = initialAmount > 0
          ? requiredString(data, "paymentMethod", 40).toLowerCase()
          : null;
        const transactionId = initialAmount > 0 ? optionalString(data, "transactionId", 128) : null;
        const referenceKey = initialAmount > 0
          ? paymentReferenceKey(paymentMethod, transactionId)
          : null;
        const referenceRef = referenceKey
          ? instituteRef.collection("ledger_internal").doc(`payment_ref_${referenceKey}`)
          : null;
        const referenceSnap = referenceRef ? await transaction.get(referenceRef) : null;
        if (referenceSnap && referenceSnap.exists) {
          throw new HttpsError("already-exists", "This payment reference was already used.");
        }
        const receiptPlan = initialAmount > 0
          ? await planReceipt(
            transaction,
            instituteRef,
            operationId,
            optionalString(data, "receiptGroupId", 128),
            actorUid,
            studentId,
          )
          : null;
        const initialLedger = ledgerStatus(totalAmount, initialAmount);
        const fee = {
          id: feeId,
          instituteId,
          studentId,
          batchId,
          feePeriod,
          feeType,
          dueDateMs,
          baseAmount,
          discountAmount,
          lateFeeAmount,
          totalAmount,
          ...initialLedger,
          note: optionalString(data, "note"),
          createdAtMs: now,
          updatedAtMs: now,
          cancelledAtMs: null,
          businessKey,
          ledgerVersion: 1,
        };
        let payments = [];
        let receipts = [];
        transaction.create(feeRef, fee);
        transaction.create(keyRef, { feeId, businessKey, createdAtMs: now });
        if (initialAmount > 0) {
          applyReceiptPlan(transaction, receiptPlan, actorUid, studentId, now);
          const records = paymentAndReceipt({
            instituteId,
            operationId,
            fee,
            amount: initialAmount,
            paymentMethod,
            transactionId,
            receiptPlan,
            paymentDateMs: requiredTimestamp(data, "paymentDateMs"),
            actorUid,
            note: optionalString(data, "note"),
            receiptText: optionalString(data, "receiptText", 4000),
            now,
            ledger: initialLedger,
          });
          transaction.create(instituteRef.collection("payments").doc(records.payment.id), records.payment);
          transaction.create(instituteRef.collection("receipts").doc(records.receipt.id), records.receipt);
          if (referenceRef) transaction.create(referenceRef, { paymentId: records.payment.id, createdAtMs: now });
          payments = [records.payment];
          receipts = [records.receipt];
        }
        result = publicResult(operationId, action, fee, payments, receipts);
      } else if (action === "collect_payment" || action === "adjust_and_collect") {
        const feeId = requiredString(data, "feeId");
        const amount = money(data, "amount", { allowZero: false });
        const feeRef = instituteRef.collection("fees").doc(feeId);
        const feeSnap = await transaction.get(feeRef);
        if (!feeSnap.exists || feeSnap.get("cancelledAtMs") != null) {
          throw new HttpsError("failed-precondition", "Fee is unavailable.");
        }
        const currentFee = { id: feeSnap.id, ...feeSnap.data() };
        let totalAmount = Number(currentFee.totalAmount || 0);
        let adjustedFields = {};
        let newBusinessKey = currentFee.businessKey || feeBusinessKey(currentFee);
        let newKeyRef = null;
        let newKeySnap = null;
        if (action === "adjust_and_collect") {
          const newBaseAmount = money(data, "newBaseAmount");
          const discountPercent = money(data, "discountPercent");
          if (discountPercent > 100) {
            throw new HttpsError("invalid-argument", "Discount must be between 0 and 100%.");
          }
          const rawDiscountAmount = Math.round(newBaseAmount * discountPercent) / 100;
          const discountAmount = validatedMoney(rawDiscountAmount, "discountAmount");
          totalAmount = validatedMoney(newBaseAmount - discountAmount, "totalAmount");
          const feePeriod = requiredString(data, "feePeriod", 80);
          newBusinessKey = feeBusinessKey({ ...currentFee, feePeriod });
          if (newBusinessKey !== currentFee.businessKey) {
            newKeyRef = instituteRef.collection("ledger_internal").doc(`fee_key_${newBusinessKey}`);
            newKeySnap = await transaction.get(newKeyRef);
            if (newKeySnap.exists && newKeySnap.get("feeId") !== feeId) {
              throw new HttpsError("already-exists", "Adjusted fee conflicts with an existing fee.");
            }
          }
          adjustedFields = {
            baseAmount: newBaseAmount,
            discountAmount,
            lateFeeAmount: 0,
            totalAmount,
            feePeriod,
            businessKey: newBusinessKey,
          };
        }

        const effectivePaid = await readEffectivePaid(transaction, instituteRef, feeId);
        if (totalAmount + MONEY_EPSILON < effectivePaid) {
          throw new HttpsError("failed-precondition", "Fee total cannot be less than the immutable paid ledger.");
        }
        const currentDue = Math.max(0, totalAmount - effectivePaid);
        if (amount - currentDue > MONEY_EPSILON) {
          throw new HttpsError("failed-precondition", "Payment exceeds remaining due.");
        }
        const paymentMethod = requiredString(data, "paymentMethod", 40).toLowerCase();
        const transactionId = optionalString(data, "transactionId", 128);
        const referenceKey = paymentReferenceKey(paymentMethod, transactionId);
        const referenceRef = referenceKey
          ? instituteRef.collection("ledger_internal").doc(`payment_ref_${referenceKey}`)
          : null;
        const referenceSnap = referenceRef ? await transaction.get(referenceRef) : null;
        if (referenceSnap && referenceSnap.exists) {
          throw new HttpsError("already-exists", "This payment reference was already used.");
        }
        const receiptPlan = await planReceipt(
          transaction,
          instituteRef,
          operationId,
          optionalString(data, "receiptGroupId", 128),
          actorUid,
          currentFee.studentId,
        );
        const ledger = ledgerStatus(totalAmount, effectivePaid + amount);
        const fee = {
          ...currentFee,
          ...adjustedFields,
          ...ledger,
          updatedAtMs: now,
          ledgerVersion: 1,
        };
        const records = paymentAndReceipt({
          instituteId,
          operationId,
          fee,
          amount,
          paymentMethod,
          transactionId,
          receiptPlan,
          paymentDateMs: requiredTimestamp(data, "paymentDateMs"),
          actorUid,
          note: optionalString(data, "note"),
          receiptText: optionalString(data, "receiptText", 4000),
          now,
          ledger,
        });
        applyReceiptPlan(transaction, receiptPlan, actorUid, currentFee.studentId, now);
        transaction.update(feeRef, { ...adjustedFields, ...ledger, updatedAtMs: now, ledgerVersion: 1 });
        transaction.create(instituteRef.collection("payments").doc(records.payment.id), records.payment);
        transaction.create(instituteRef.collection("receipts").doc(records.receipt.id), records.receipt);
        if (referenceRef) transaction.create(referenceRef, { paymentId: records.payment.id, createdAtMs: now });
        if (newKeyRef && !newKeySnap.exists) {
          transaction.create(newKeyRef, { feeId, businessKey: newBusinessKey, createdAtMs: now });
        }
        if (action === "adjust_and_collect") {
          transaction.create(instituteRef.collection("fee_adjustments").doc(compactId("adj", operationId)), {
            instituteId,
            feeId,
            studentId: currentFee.studentId,
            previous: {
              baseAmount: currentFee.baseAmount,
              discountAmount: currentFee.discountAmount,
              lateFeeAmount: currentFee.lateFeeAmount,
              totalAmount: currentFee.totalAmount,
              feePeriod: currentFee.feePeriod,
            },
            next: adjustedFields,
            reason: optionalString(data, "note") || "Adjusted during payment collection",
            adjustedByUserId: actorUid,
            adjustedAtMs: now,
            operationId,
          });
        }
        result = publicResult(operationId, action, fee, [records.payment], [records.receipt]);
      } else if (action === "owner_edit_payment") {
        const paymentId = requiredString(data, "paymentId");
        const amount = money(data, "amount", { allowZero: false });
        const paymentMethod = requiredString(data, "paymentMethod", 40).toLowerCase();
        const paymentDateMs = requiredTimestamp(data, "paymentDateMs");
        const feePeriod = requiredString(data, "feePeriod", 80);
        const reason = requiredString(data, "reason", 500);
        if (reason.length < 3) throw new HttpsError("invalid-argument", "A correction reason is required.");
        const note = optionalString(data, "note");
        const paymentRef = instituteRef.collection("payments").doc(paymentId);
        const paymentSnap = await transaction.get(paymentRef);
        if (!paymentSnap.exists) throw new HttpsError("not-found", "Payment not found.");
        if (paymentSnap.get("status") !== "completed") {
          throw new HttpsError("failed-precondition", "Only an active completed payment can be edited.");
        }
        const payment = { id: paymentSnap.id, ...paymentSnap.data() };
        const sourceFeeRef = instituteRef.collection("fees").doc(payment.feeId);
        const receiptQuery = instituteRef.collection("receipts").where("paymentId", "==", paymentId);
        const [sourceFeeSnap, receiptSnap] = await Promise.all([
          transaction.get(sourceFeeRef),
          transaction.get(receiptQuery),
        ]);
        if (!sourceFeeSnap.exists || sourceFeeSnap.get("cancelledAtMs") != null) {
          throw new HttpsError("failed-precondition", "The original fee is unavailable.");
        }
        if (receiptSnap.size > 1) {
          throw new HttpsError("failed-precondition", "Payment has duplicate receipts and requires reconciliation.");
        }

        const sourceFee = { id: sourceFeeSnap.id, ...sourceFeeSnap.data() };
        const targetBusinessKey = feeBusinessKey({ ...sourceFee, feePeriod });
        const movesToAnotherFee = targetBusinessKey !== (sourceFee.businessKey || feeBusinessKey(sourceFee));
        let targetFeeRef = sourceFeeRef;
        let targetFee = sourceFee;
        let targetKeyRef = null;
        let createTargetFee = false;

        if (movesToAnotherFee) {
          const deterministicTargetId = compactId("fee", `${instituteId}:${targetBusinessKey}`);
          const candidateFeeRef = instituteRef.collection("fees").doc(deterministicTargetId);
          const candidateKeyRef = instituteRef.collection("ledger_internal").doc(`fee_key_${targetBusinessKey}`);
          const studentFeesQuery = instituteRef.collection("fees").where("studentId", "==", payment.studentId);
          const [candidateSnap, keySnap, studentFeesSnap] = await Promise.all([
            transaction.get(candidateFeeRef),
            transaction.get(candidateKeyRef),
            transaction.get(studentFeesQuery),
          ]);
          const legacyTargetSnap = studentFeesSnap.docs.find((doc) =>
            doc.get("cancelledAtMs") == null && feeBusinessKey({
              studentId: doc.get("studentId"),
              batchId: doc.get("batchId"),
              feePeriod: doc.get("feePeriod"),
              feeType: doc.get("feeType"),
            }) === targetBusinessKey,
          );
          if (keySnap.exists && keySnap.get("feeId") !== deterministicTargetId &&
            (!legacyTargetSnap || keySnap.get("feeId") !== legacyTargetSnap.id)) {
            throw new HttpsError("already-exists", "The selected payment month conflicts with another fee.");
          }
          if (candidateSnap.exists && candidateSnap.get("cancelledAtMs") == null) {
            targetFeeRef = candidateFeeRef;
            targetFee = { id: candidateSnap.id, ...candidateSnap.data() };
          } else if (legacyTargetSnap) {
            targetFeeRef = legacyTargetSnap.ref;
            targetFee = { id: legacyTargetSnap.id, ...legacyTargetSnap.data() };
          } else {
            targetFeeRef = candidateFeeRef;
            createTargetFee = true;
            targetFee = {
              ...sourceFee,
              id: deterministicTargetId,
              feePeriod,
              businessKey: targetBusinessKey,
              createdAtMs: now,
              updatedAtMs: now,
              cancelledAtMs: null,
              ledgerVersion: 1,
            };
          }
          targetKeyRef = candidateKeyRef;
        }

        if (targetFee.studentId !== payment.studentId) {
          throw new HttpsError("failed-precondition", "The selected fee belongs to another student.");
        }
        const originalAmount = Number(payment.amount || 0);
        const sourceEffectivePaid = await readEffectivePaid(transaction, instituteRef, sourceFee.id);
        if (sourceEffectivePaid + MONEY_EPSILON < originalAmount) {
          throw new HttpsError("failed-precondition", "Payment is not present in the effective ledger.");
        }
        const sourcePaidAfter = movesToAnotherFee
          ? sourceEffectivePaid - originalAmount
          : sourceEffectivePaid - originalAmount + amount;
        if (sourcePaidAfter < -MONEY_EPSILON || sourcePaidAfter - Number(sourceFee.totalAmount || 0) > MONEY_EPSILON) {
          throw new HttpsError("failed-precondition", "The corrected amount exceeds the original fee balance.");
        }
        const sourceLedger = ledgerStatus(Number(sourceFee.totalAmount || 0), sourcePaidAfter);

        let targetLedger = sourceLedger;
        if (movesToAnotherFee) {
          const targetEffectivePaid = createTargetFee
            ? 0
            : await readEffectivePaid(transaction, instituteRef, targetFee.id);
          if (targetEffectivePaid + amount - Number(targetFee.totalAmount || 0) > MONEY_EPSILON) {
            throw new HttpsError("failed-precondition", "The corrected amount exceeds the selected month balance.");
          }
          targetLedger = ledgerStatus(Number(targetFee.totalAmount || 0), targetEffectivePaid + amount);
        }

        const updatedSourceFee = { ...sourceFee, ...sourceLedger, updatedAtMs: now, ledgerVersion: 1 };
        const updatedTargetFee = movesToAnotherFee
          ? { ...targetFee, ...targetLedger, updatedAtMs: now, ledgerVersion: 1 }
          : updatedSourceFee;
        const updatedPayment = {
          ...payment,
          feeId: updatedTargetFee.id,
          amount,
          paymentMethod,
          paymentDateMs,
          note,
          updatedAtMs: now,
          ledgerVersion: 1,
        };
        const receiptDoc = receiptSnap.docs[0];
        const updatedReceipt = receiptDoc ? {
          id: receiptDoc.id,
          ...receiptDoc.data(),
          feeId: updatedTargetFee.id,
          receiptDateMs: paymentDateMs,
          totalAmount: updatedTargetFee.totalAmount,
          paidAmount: targetLedger.paidAmount,
          dueAmount: targetLedger.dueAmount,
          paymentMethod,
          receiptText: `Payment of ${amount.toFixed(2)} received.`,
          ledgerVersion: 1,
        } : null;

        transaction.update(sourceFeeRef, { ...sourceLedger, updatedAtMs: now, ledgerVersion: 1 });
        if (movesToAnotherFee) {
          if (createTargetFee) transaction.create(targetFeeRef, updatedTargetFee);
          else transaction.update(targetFeeRef, { ...targetLedger, updatedAtMs: now, ledgerVersion: 1 });
          if (targetKeyRef) transaction.set(targetKeyRef, {
            feeId: updatedTargetFee.id,
            businessKey: targetBusinessKey,
            createdAtMs: now,
          }, { merge: true });
        }
        transaction.update(paymentRef, {
          feeId: updatedPayment.feeId,
          amount: updatedPayment.amount,
          paymentMethod: updatedPayment.paymentMethod,
          paymentDateMs: updatedPayment.paymentDateMs,
          note: updatedPayment.note,
          updatedAtMs: now,
          ledgerVersion: 1,
        });
        if (updatedReceipt) {
          transaction.update(receiptDoc.ref, {
            feeId: updatedReceipt.feeId,
            receiptDateMs: updatedReceipt.receiptDateMs,
            totalAmount: updatedReceipt.totalAmount,
            paidAmount: updatedReceipt.paidAmount,
            dueAmount: updatedReceipt.dueAmount,
            paymentMethod: updatedReceipt.paymentMethod,
            receiptText: updatedReceipt.receiptText,
            ledgerVersion: 1,
          });
        }
        transaction.create(instituteRef.collection("payment_corrections").doc(compactId("payment_correction", operationId)), {
          instituteId,
          paymentId,
          studentId: payment.studentId,
          previous: {
            feeId: payment.feeId,
            amount: originalAmount,
            paymentMethod: payment.paymentMethod,
            paymentDateMs: payment.paymentDateMs,
            note: payment.note || null,
          },
          next: {
            feeId: updatedPayment.feeId,
            amount,
            paymentMethod,
            paymentDateMs,
            feePeriod: updatedTargetFee.feePeriod,
            note,
          },
          reason,
          correctedByUserId: actorUid,
          correctedAtMs: now,
          operationId,
          ledgerVersion: 1,
        });
        result = publicResult(
          operationId,
          action,
          movesToAnotherFee ? [updatedSourceFee, updatedTargetFee] : updatedSourceFee,
          [updatedPayment],
          updatedReceipt ? [updatedReceipt] : [],
        );
      } else if (action === "owner_delete_payment") {
        const paymentId = requiredString(data, "paymentId");
        const reason = requiredString(data, "reason", 500);
        if (reason.length < 3) throw new HttpsError("invalid-argument", "A deletion reason is required.");
        const paymentRef = instituteRef.collection("payments").doc(paymentId);
        const paymentSnap = await transaction.get(paymentRef);
        if (!paymentSnap.exists) throw new HttpsError("not-found", "Payment not found.");
        if (paymentSnap.get("status") !== "completed") {
          throw new HttpsError("failed-precondition", "Only an active completed payment can be permanently deleted.");
        }
        const payment = { id: paymentSnap.id, ...paymentSnap.data() };
        const feeRef = instituteRef.collection("fees").doc(payment.feeId);
        const receiptQuery = instituteRef.collection("receipts").where("paymentId", "==", paymentId);
        const referenceKey = paymentReferenceKey(payment.paymentMethod, payment.transactionId);
        const referenceRef = referenceKey
          ? instituteRef.collection("ledger_internal").doc(`payment_ref_${referenceKey}`)
          : null;
        const [feeSnap, receiptsSnap, referenceSnap] = await Promise.all([
          transaction.get(feeRef),
          transaction.get(receiptQuery),
          referenceRef ? transaction.get(referenceRef) : Promise.resolve(null),
        ]);
        if (!feeSnap.exists) throw new HttpsError("failed-precondition", "Fee record not found.");
        const effectivePaid = await readEffectivePaid(transaction, instituteRef, payment.feeId);
        if (effectivePaid + MONEY_EPSILON < Number(payment.amount || 0)) {
          throw new HttpsError("failed-precondition", "Payment is not present in the effective ledger.");
        }
        const fee = { id: feeSnap.id, ...feeSnap.data() };
        const ledger = ledgerStatus(
          Number(fee.totalAmount || 0),
          effectivePaid - Number(payment.amount || 0),
        );
        const updatedFee = { ...fee, ...ledger, updatedAtMs: now, ledgerVersion: 1 };
        transaction.update(feeRef, { ...ledger, updatedAtMs: now, ledgerVersion: 1 });
        receiptsSnap.docs.forEach((receiptDoc) => transaction.delete(receiptDoc.ref));
        transaction.delete(paymentRef);
        if (referenceRef && referenceSnap && referenceSnap.exists && referenceSnap.get("paymentId") === paymentId) {
          transaction.delete(referenceRef);
        }
        transaction.create(instituteRef.collection("payment_deletions").doc(compactId("payment_deletion", operationId)), {
          instituteId,
          paymentId,
          feeId: payment.feeId,
          studentId: payment.studentId,
          amount: Number(payment.amount || 0),
          receiptNumber: payment.receiptNumber,
          reason,
          deletedByUserId: actorUid,
          deletedAtMs: now,
          operationId,
          ledgerVersion: 1,
        });
        result = publicResult(
          operationId,
          action,
          updatedFee,
          [],
          [],
          [],
          {
            deletedPaymentIds: [paymentId],
            deletedReceiptIds: receiptsSnap.docs.map((receiptDoc) => receiptDoc.id),
          },
        );
      } else {
        const paymentId = requiredString(data, "paymentId");
        const reason = requiredString(data, "reason", 500);
        if (reason.length < 3) throw new HttpsError("invalid-argument", "A reversal reason is required.");
        const paymentRef = instituteRef.collection("payments").doc(paymentId);
        const reversalId = compactId("reversal", `${instituteId}:${paymentId}`);
        const reversalRef = instituteRef.collection("payment_reversals").doc(reversalId);
        const [paymentSnap, reversalSnap] = await Promise.all([
          transaction.get(paymentRef),
          transaction.get(reversalRef),
        ]);
        if (!paymentSnap.exists) throw new HttpsError("not-found", "Payment not found.");
        if (reversalSnap.exists || paymentSnap.get("status") === "reversed") {
          throw new HttpsError("already-exists", "Payment is already reversed.");
        }
        const payment = { id: paymentSnap.id, ...paymentSnap.data() };
        const feeRef = instituteRef.collection("fees").doc(payment.feeId);
        const feeSnap = await transaction.get(feeRef);
        if (!feeSnap.exists) throw new HttpsError("failed-precondition", "Fee record not found.");
        const effectivePaid = await readEffectivePaid(transaction, instituteRef, payment.feeId);
        if (effectivePaid + MONEY_EPSILON < Number(payment.amount || 0)) {
          throw new HttpsError("failed-precondition", "Payment is not present in the effective ledger.");
        }
        const fee = { id: feeSnap.id, ...feeSnap.data() };
        const ledger = ledgerStatus(Number(fee.totalAmount || 0), effectivePaid - Number(payment.amount || 0));
        const reversal = {
          id: reversalId,
          instituteId,
          paymentId,
          feeId: payment.feeId,
          studentId: payment.studentId,
          amount: Number(payment.amount),
          receiptNumber: payment.receiptNumber,
          reason,
          reversedByUserId: actorUid,
          reversedAtMs: now,
          operationId,
          ledgerVersion: 1,
        };
        const updatedFee = { ...fee, ...ledger, updatedAtMs: now, ledgerVersion: 1 };
        transaction.create(reversalRef, reversal);
        transaction.update(feeRef, { ...ledger, updatedAtMs: now, ledgerVersion: 1 });
        result = publicResult(
          operationId,
          action,
          updatedFee,
          [{ ...payment, status: "reversed", updatedAtMs: now }],
          [],
          [reversal],
        );
      }

      transaction.create(operationRef, {
        instituteId,
        action,
        actorUid,
        requestHash: hash,
        result,
        createdAtMs: now,
      });
      return result;
    });
  };
}

module.exports = { createFinancialLedgerHandler };
