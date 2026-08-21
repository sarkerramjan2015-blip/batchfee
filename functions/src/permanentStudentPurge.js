"use strict";

const { HttpsError } = require("firebase-functions/v2/https");
const { parseMediaReference } = require("./mediaSecurityCore");
const { hasCurrentSubscription } = require("./subscriptionPolicy");
const { studentLoginDocumentId } = require("./studentAuthCore");

const STUDENT_COLLECTIONS = [
  "batch_students", "attendance", "fees", "payments", "receipts", "results",
  "absent_messages", "homework_submissions", "assignment_submissions",
  "student_activity",
];

function requireString(data, key, max = 256) {
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

/**
 * Irreversible server-side student data purge. Only an archived student can be purged;
 * the caller must be the institute owner or a Super Admin.
 */
function createPermanentStudentPurgeHandler({ db, adminAuth, bucket }) {
  return async (request) => {
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const instituteId = requireString(request.data, "instituteId", 128);
    const studentId = requireString(request.data, "studentId", 128);
    const instituteRef = db.collection("institutes").doc(instituteId);
    const studentRef = instituteRef.collection("students").doc(studentId);
    const [instituteSnap, studentSnap, actorSnap] = await Promise.all([
      instituteRef.get(), studentRef.get(), db.collection("app_users").doc(request.auth.uid).get(),
    ]);
    if (!instituteSnap.exists || !studentSnap.exists) throw new HttpsError("not-found", "Archived student was not found.");
    const institute = instituteSnap.data();
    const actor = actorSnap.exists ? actorSnap.data() : null;
    const superAdmin = isSuperAdmin(actor);
    const managedOwner = isManagedInstituteOwner(actor, instituteId);
    const owner = request.auth.uid === instituteId || request.auth.uid === institute.ownerUid;
    if (!owner && !managedOwner && !superAdmin) {
      throw new HttpsError("permission-denied", "Only the institute owner or Super Admin can permanently delete a student.");
    }
    if (!hasCurrentSubscription(institute)) {
      throw new HttpsError("failed-precondition", "Subscription has expired. Renew the plan to continue.");
    }
    const student = studentSnap.data();
    // `archivedAtMs` is the canonical archive marker. Older app versions did
    // not consistently update the legacy `status` field, so requiring both
    // leaves legitimately archived records impossible to remove.
    if (student.archivedAtMs == null) {
      throw new HttpsError("failed-precondition", "Archive the student before permanently deleting their data.");
    }
    // Remove every application record keyed by this student before deleting the profile.
    await Promise.all(STUDENT_COLLECTIONS.map((collection) =>
      deleteQuery(db, instituteRef.collection(collection).where("studentId", "==", studentId))
    ));
    await instituteRef.collection("student_activity_presence").doc(studentId).delete().catch(() => {});

    const media = parseMediaReference(student.photoUri);
    if (media && media.instituteId === instituteId) {
      const assetRef = instituteRef.collection("media_assets").doc(media.assetId);
      const assetSnap = await assetRef.get();
      if (assetSnap.exists && assetSnap.get("storageBucket") === bucket.name &&
          typeof assetSnap.get("storageObjectPath") === "string") {
        const asset = assetSnap.data();
        await bucket.file(asset.storageObjectPath).delete({ ignoreNotFound: true });
      }
      await assetRef.delete().catch(() => {});
    }
    const authUid = typeof student.firebaseUid === "string" ? student.firebaseUid : null;
    if (authUid) {
      await db.collection("student_auth_accounts").doc(authUid).delete().catch(() => {});
      await adminAuth.deleteUser(authUid).catch((error) => {
        if (error?.code !== "auth/user-not-found") throw error;
      });
    }
    const logins = await db.collection("student_auth_logins").where("instituteId", "==", instituteId)
      .where("studentId", "==", studentId).get();
    const loginKeys = new Set(logins.docs.map((doc) => doc.id));
    if (typeof student.studentCode === "string" && student.studentCode.trim()) {
      loginKeys.add(studentLoginDocumentId(student.studentCode));
      await db.collection("student_login_mappings").doc(student.studentCode).delete().catch(() => {});
    }
    await Promise.all([
      ...logins.docs.map((doc) => doc.ref.delete()),
      ...[...loginKeys].map((loginKey) => db.collection("student_auth_attempts").doc(loginKey).delete()),
    ]);
    await studentRef.delete();
    await removeDeletionMetadata(db, instituteRef, "student", studentId);
    return { studentId, permanentlyDeleted: true };
  };
}

module.exports = { createPermanentStudentPurgeHandler };
