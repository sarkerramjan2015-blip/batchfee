"use strict";

const { HttpsError } = require("firebase-functions/v2/https");
const { parseMediaReference } = require("./mediaSecurityCore");
const { hasCurrentSubscription } = require("./subscriptionPolicy");

function requiredString(data, key, max = 256) {
  const value = typeof data?.[key] === "string" ? data[key].trim() : "";
  if (!value || value.length > max) throw new HttpsError("invalid-argument", `Invalid ${key}.`);
  return value;
}

function isSuperAdmin(user) {
  return user &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(user.role) || user.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(user, "status") || user.status === "active");
}

function isManagedInstituteOwner(user, instituteId) {
  return user && user.instituteId === instituteId &&
    ["InstituteOwner", "owner", "instituteOwner", "institute_owner"].includes(user.role) &&
    (!Object.prototype.hasOwnProperty.call(user, "status") || user.status === "active");
}

async function deleteQuery(db, query) {
  while (true) {
    const snapshot = await query.limit(400).get();
    if (snapshot.empty) return;
    const batch = db.batch();
    snapshot.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
}

async function idsFor(instituteRef, collection, field, value) {
  const snapshot = await instituteRef.collection(collection).where(field, "==", value).get();
  return snapshot.docs.map((doc) => doc.id);
}

async function deleteForValues(db, instituteRef, collection, field, values) {
  // Firestore has a limit on `in` filters. Deleting one key at a time is safe for
  // any archive size and makes sure every dependent record is removed.
  for (const value of values) {
    await deleteQuery(db, instituteRef.collection(collection).where(field, "==", value));
  }
}

async function removeDeletionMetadata(db, instituteRef, entityType, entityId) {
  await instituteRef.collection("deletion_states").doc(`${entityType}_${entityId}`).delete().catch(() => {});
  for (const collection of ["deletion_operations", "deletion_audit"]) {
    const snapshot = await instituteRef.collection(collection).where("entityId", "==", entityId).get();
    const matches = snapshot.docs.filter((doc) => doc.get("entityType") === entityType);
    if (matches.length === 0) continue;
    const batch = db.batch();
    matches.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
}

async function removeManagedMedia({ instituteRef, instituteId, reference, bucket }) {
  const media = parseMediaReference(reference);
  if (!media || media.instituteId !== instituteId) return;
  const assetRef = instituteRef.collection("media_assets").doc(media.assetId);
  const assetSnap = await assetRef.get();
  if (assetSnap.exists && assetSnap.get("storageBucket") === bucket.name &&
      typeof assetSnap.get("storageObjectPath") === "string") {
    await bucket.file(assetSnap.get("storageObjectPath")).delete({ ignoreNotFound: true });
  }
  await assetRef.delete().catch(() => {});
}

async function assertPermanentPurgeAuthority({ request, db, instituteId, collection, entityId, label }) {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const instituteRef = db.collection("institutes").doc(instituteId);
  const targetRef = instituteRef.collection(collection).doc(entityId);
  const [instituteSnap, targetSnap, actorSnap] = await Promise.all([
    instituteRef.get(), targetRef.get(), db.collection("app_users").doc(request.auth.uid).get(),
  ]);
  if (!instituteSnap.exists || !targetSnap.exists) {
    throw new HttpsError("not-found", `Archived ${label.toLowerCase()} was not found.`);
  }
  const institute = instituteSnap.data();
  const actor = actorSnap.exists ? actorSnap.data() : null;
  const superAdmin = isSuperAdmin(actor);
  const managedOwner = isManagedInstituteOwner(actor, instituteId);
  const owner = request.auth.uid === instituteId || request.auth.uid === institute.ownerUid;
  if (!owner && !managedOwner && !superAdmin) {
    throw new HttpsError("permission-denied", `Only the institute owner or Super Admin can permanently delete a ${label.toLowerCase()}.`);
  }
  if (!hasCurrentSubscription(institute)) {
    throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
  }
  const target = targetSnap.data();
  if (target.archivedAtMs == null || target.status !== "archived") {
    throw new HttpsError("failed-precondition", `Archive the ${label.toLowerCase()} before permanently deleting its data.`);
  }
  return { instituteRef, targetRef, target };
}

/** Permanently removes an archived batch and every record that belongs to it. */
function createPermanentBatchPurgeHandler({ db, bucket }) {
  return async (request) => {
    const instituteId = requiredString(request.data, "instituteId", 128);
    const batchId = requiredString(request.data, "batchId", 128);
    const { instituteRef, targetRef } = await assertPermanentPurgeAuthority({
      request, db, instituteId, collection: "batches", entityId: batchId, label: "Batch",
    });

    // Read child ids before deleting their parent documents, then clear every
    // dependent record. Student profiles remain intact; only this batch's data goes.
    const [feeIds, homeworkIds, assignmentIds] = await Promise.all([
      idsFor(instituteRef, "fees", "batchId", batchId),
      idsFor(instituteRef, "homework", "batchId", batchId),
      idsFor(instituteRef, "assignments", "batchId", batchId),
    ]);
    await Promise.all([
      deleteForValues(db, instituteRef, "payments", "feeId", feeIds),
      deleteForValues(db, instituteRef, "receipts", "feeId", feeIds),
      deleteForValues(db, instituteRef, "payment_reversals", "feeId", feeIds),
      deleteForValues(db, instituteRef, "homework_submissions", "homeworkId", homeworkIds),
      deleteForValues(db, instituteRef, "assignment_submissions", "assignmentId", assignmentIds),
    ]);
    await Promise.all([
      "batch_students", "attendance", "fees", "results", "absent_messages",
      "works", "homework", "assignments", "exams",
    ].map((collection) => deleteQuery(db, instituteRef.collection(collection).where("batchId", "==", batchId))));
    await targetRef.delete();
    await removeDeletionMetadata(db, instituteRef, "batch", batchId);
    return { batchId, permanentlyDeleted: true };
  };
}

/** Permanently removes an archived staff account, its history, private photo and Firebase login. */
function createPermanentStaffPurgeHandler({ db, adminAuth, bucket }) {
  return async (request) => {
    const instituteId = requiredString(request.data, "instituteId", 128);
    const staffId = requiredString(request.data, "staffId", 128);
    const { instituteRef, targetRef, target } = await assertPermanentPurgeAuthority({
      request, db, instituteId, collection: "staffs", entityId: staffId, label: "Staff",
    });

    await Promise.all([
      deleteQuery(db, instituteRef.collection("staff_attendance").where("staffId", "==", staffId)),
      deleteQuery(db, instituteRef.collection("salaries").where("staffId", "==", staffId)),
      deleteQuery(db, instituteRef.collection("audit_logs").where("userId", "==", staffId)),
    ]);
    await removeManagedMedia({ instituteRef, instituteId, reference: target.photoUri, bucket });

    // Staff-ID sign-in uses server-owned global mappings. Remove those before
    // the profile so a permanently deleted staff member can neither sign in
    // nor leave an occupied Staff ID behind.
    const accountRef = db.collection("staff_auth_accounts").doc(staffId);
    const [accountSnap, loginSnaps, appUserSnap] = await Promise.all([
      accountRef.get(),
      db.collection("staff_auth_logins")
        .where("instituteId", "==", instituteId)
        .where("staffId", "==", staffId)
        .get(),
      db.collection("app_users").doc(staffId).get(),
    ]);
    const loginKeys = new Set(loginSnaps.docs.map((doc) => doc.id));
    if (accountSnap.exists && accountSnap.get("instituteId") === instituteId &&
        accountSnap.get("staffId") === staffId && typeof accountSnap.get("loginKey") === "string") {
      loginKeys.add(accountSnap.get("loginKey"));
    }
    await Promise.all([
      ...loginSnaps.docs.map((doc) => doc.ref.delete()),
      ...[...loginKeys].map((key) => db.collection("staff_auth_attempts").doc(key).delete()),
      accountRef.delete().catch(() => {}),
    ]);
    await targetRef.delete();
    const linkedStaffIdentity = appUserSnap.exists &&
      appUserSnap.get("instituteId") === instituteId && appUserSnap.get("role") === "Staff";
    if (linkedStaffIdentity) {
      await db.collection("app_users").doc(staffId).delete().catch(() => {});
      await adminAuth.deleteUser(staffId).catch((error) => {
        if (error?.code !== "auth/user-not-found") throw error;
      });
    }
    await removeDeletionMetadata(db, instituteRef, "staff", staffId);
    return { staffId, permanentlyDeleted: true };
  };
}

module.exports = { createPermanentBatchPurgeHandler, createPermanentStaffPurgeHandler };
