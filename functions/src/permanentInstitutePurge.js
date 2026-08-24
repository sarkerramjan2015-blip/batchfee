"use strict";

const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");

function requiredString(data, key, max = 128) {
  const value = typeof data?.[key] === "string" ? data[key].trim() : "";
  if (!value || value.length > max || value.includes("/")) {
    throw new HttpsError("invalid-argument", `Invalid ${key}.`);
  }
  return value;
}

function isSuperAdmin(user) {
  return user &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(user.role) || user.platformRole === "root") &&
    (!Object.prototype.hasOwnProperty.call(user, "status") || user.status === "active");
}

function hasPlatformAdminRole(user) {
  return user &&
    (["SuperAdmin", "superAdmin", "super_admin"].includes(user.role) || user.platformRole === "root");
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

async function deleteDocumentTree(db, ref) {
  if (typeof db.recursiveDelete === "function") {
    await db.recursiveDelete(ref);
  } else {
    await ref.delete();
  }
}

async function deleteAuthUser(adminAuth, uid) {
  if (!uid) return;
  await adminAuth.deleteUser(uid).catch((error) => {
    if (error?.code !== "auth/user-not-found") throw error;
  });
}

/**
 * Irreversibly removes an archived institute and its tenant-owned cloud data.
 * This endpoint is intentionally Super Admin-only and can never target an active institute.
 */
function createPermanentInstitutePurgeHandler({ db, adminAuth, bucket }) {
  return async (request) => {
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
    const instituteId = requiredString(request.data, "instituteId");
    const actorRef = db.collection("app_users").doc(request.auth.uid);
    const instituteRef = db.collection("institutes").doc(instituteId);
    const [actorSnap, instituteSnap] = await Promise.all([actorRef.get(), instituteRef.get()]);
    const actor = actorSnap.exists ? actorSnap.data() : null;
    if (!isSuperAdmin(actor)) {
      throw new HttpsError("permission-denied", "Only an active Super Admin can permanently delete an institute.");
    }
    // A retry after a completed purge is safe and gives the client an idempotent result.
    if (!instituteSnap.exists) return { instituteId, permanentlyDeleted: true, alreadyDeleted: true };

    const institute = instituteSnap.data();
    if (institute.deletionState !== "retained" || institute.archivedAtMs == null ||
        institute.subscriptionStatus !== "deletion_pending") {
      throw new HttpsError("failed-precondition", "Archive the institute before permanently deleting its data.");
    }

    // Resolve every Firebase identity before deleting Firestore documents.
    const [students, staffs, managedUsers, authAccounts, authLogins] = await Promise.all([
      instituteRef.collection("students").get(),
      instituteRef.collection("staffs").get(),
      db.collection("app_users").where("instituteId", "==", instituteId).get(),
      db.collection("student_auth_accounts").where("instituteId", "==", instituteId).get(),
      db.collection("student_auth_logins").where("instituteId", "==", instituteId).get(),
    ]);
    const candidateAuthUids = new Set([
      institute.ownerUid,
      instituteId,
      ...managedUsers.docs.map((doc) => doc.id),
      ...authAccounts.docs.map((doc) => doc.id),
      ...students.docs.map((doc) => doc.get("firebaseUid")),
      ...staffs.docs.map((doc) => doc.get("firebaseUid") || doc.id),
    ].filter((value) => typeof value === "string" && value));
    const candidateAppUsers = await Promise.all(
      [...candidateAuthUids].map((uid) => db.collection("app_users").doc(uid).get()),
    );
    const protectedAuthUids = new Set([request.auth.uid]);
    candidateAppUsers.forEach((snapshot) => {
      if (snapshot.exists && hasPlatformAdminRole(snapshot.data())) protectedAuthUids.add(snapshot.id);
    });
    const authUids = new Set([...candidateAuthUids].filter((uid) => !protectedAuthUids.has(uid)));
    const loginKeys = new Set(authLogins.docs.map((doc) => doc.id));

    // Remove all tenant media, including temporary public-registration photos.
    const tenantKey = createHash("sha256").update(instituteId).digest("hex").slice(0, 20);
    for (const prefix of [
      `batchfee-media/v1/private/${tenantKey}/`,
      `batchfee-media/v1/public/${tenantKey}/`,
      `batchfee-registration/v1/${tenantKey}/`,
    ]) {
      await bucket.deleteFiles({ prefix, force: true });
    }

    for (const uid of authUids) await deleteAuthUser(adminAuth, uid);

    // Remove global indexes and histories which live outside the institute tree.
    await Promise.all([
      ...[...loginKeys].map((key) => db.collection("student_auth_attempts").doc(key).delete().catch(() => {})),
      deleteQuery(db, db.collection("student_auth_accounts").where("instituteId", "==", instituteId)),
      deleteQuery(db, db.collection("student_auth_logins").where("instituteId", "==", instituteId)),
      deleteQuery(db, db.collection("student_login_mappings").where("instituteId", "==", instituteId)),
      deleteQuery(db, db.collection("subscriptionRequests").where("instituteId", "==", instituteId)),
      deleteQuery(db, db.collection("public_registration_profiles").where("instituteId", "==", instituteId)),
      deleteQuery(db, db.collection("public_registration_dedup").where("instituteId", "==", instituteId)),
      deleteQuery(db, db.collection("platform_audit").where("instituteId", "==", instituteId)),
      Promise.all(managedUsers.docs
        .filter((doc) => !protectedAuthUids.has(doc.id))
        .map((doc) => doc.ref.delete())),
      deleteQuery(db, db.collection("Users").where("instituteId", "==", instituteId)),
      deleteDocumentTree(db, db.collection("registrations").doc(instituteId)),
    ]);
    await Promise.all([
      protectedAuthUids.has(institute.ownerUid || instituteId) ? Promise.resolve() :
        db.collection("app_users").doc(institute.ownerUid || instituteId).delete().catch(() => {}),
      db.collection("institutes_trash").doc(instituteId).delete().catch(() => {}),
      db.collection("app_users_trash").doc(instituteId).delete().catch(() => {}),
    ]);

    // Delete the canonical institute tree last so an interrupted operation can be retried.
    await deleteDocumentTree(db, instituteRef);
    return { instituteId, permanentlyDeleted: true, alreadyDeleted: false };
  };
}

module.exports = { createPermanentInstitutePurgeHandler, hasPlatformAdminRole };
