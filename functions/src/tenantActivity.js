"use strict";

const { randomUUID } = require("node:crypto");

const ACTIVITY_SUMMARY_MAX = 240;
const ACTIVITY_NAME_MAX = 120;

const ACTIVE_OWNER_ROLES = new Set([
  "InstituteOwner", "owner", "instituteOwner", "institute_owner",
]);
const ACTIVE_ADMIN_ROLES = new Set([
  "InstituteAdmin", "admin", "instituteAdmin", "institute_admin",
]);
const ROOT_ROLES = new Set(["SuperAdmin", "superAdmin", "super_admin"]);

/**
 * Tenant business events for the platform activity timeline. These events are
 * written exclusively by trusted callables (Admin SDK) so tenant clients can
 * neither fake nor erase them. `platform_activity_events` is callable-only in
 * Firestore rules; only the Root platform timeline query returns them.
 */
function platformActivityRef(db, instituteId, eventId) {
  return db.collection("institutes").doc(instituteId)
    .collection("platform_activity_events").doc(eventId);
}

function newTenantActivity({
  action,
  actorUid,
  actorRole,
  actorName,
  targetType,
  targetId,
  summary,
  now,
}) {
  return {
    action: typeof action === "string" ? action : "tenant_activity",
    actorUid: typeof actorUid === "string" ? actorUid : "",
    actorRole: typeof actorRole === "string" ? actorRole : "tenant",
    actorName: typeof actorName === "string" ? actorName.slice(0, ACTIVITY_NAME_MAX) : "",
    targetType: typeof targetType === "string" ? targetType : "",
    targetId: typeof targetId === "string" ? targetId : "",
    outcome: "completed",
    summary: typeof summary === "string" ? summary.slice(0, ACTIVITY_SUMMARY_MAX) : "",
    occurredAtMs: Number.isSafeInteger(now) ? now : Date.now(),
  };
}

function transactionTenantActivity(transaction, db, instituteId, values, eventId = randomUUID()) {
  transaction.create(platformActivityRef(db, instituteId, eventId), newTenantActivity(values));
}

function setTenantActivity(db, instituteId, values, eventId = randomUUID()) {
  return platformActivityRef(db, instituteId, eventId).set(newTenantActivity(values));
}

/**
 * Classifies the caller of a tenant callable from already-read snapshots.
 * Best-effort: returns "tenant" when the caller cannot be classified. The
 * staff snapshot may be omitted when the caller is already known to be an
 * owner/root.
 */
function classifyTenantActor(instituteId, actorUid, appUserSnap, staffSnap) {
  const appUser = appUserSnap && appUserSnap.exists ? appUserSnap.data() : null;
  const activeAppUser = appUser &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
  const name = appUser && typeof appUser.name === "string" ? appUser.name : "";
  const role = appUser && typeof appUser.role === "string" ? appUser.role : "";
  if (activeAppUser && (ROOT_ROLES.has(role) || appUser.platformRole === "root")) {
    return { actorRole: "root", actorName: name };
  }
  if (actorUid === instituteId) return { actorRole: "owner", actorName: name };
  if (activeAppUser && appUser.instituteId === instituteId && ACTIVE_OWNER_ROLES.has(role)) {
    return { actorRole: "owner", actorName: name };
  }
  if (activeAppUser && appUser.instituteId === instituteId && ACTIVE_ADMIN_ROLES.has(role)) {
    return { actorRole: "admin", actorName: name };
  }
  if (staffSnap && staffSnap.exists) {
    const staff = staffSnap.data();
    if (staff && staff.archivedAtMs == null) {
      return {
        actorRole: "staff",
        actorName: typeof staff.fullName === "string" ? staff.fullName : name,
      };
    }
  }
  return { actorRole: "tenant", actorName: name };
}

/**
 * Resolves the caller with direct reads (non-transactional handlers).
 */
async function resolveTenantActor(db, authContext, instituteId) {
  const actorUid = authContext && authContext.uid;
  if (!actorUid) return { actorRole: "unknown", actorName: "" };
  const [appUserSnap, staffSnap] = await Promise.all([
    db.collection("app_users").doc(actorUid).get(),
    db.collection("institutes").doc(instituteId).collection("staffs").doc(actorUid).get(),
  ]);
  return classifyTenantActor(instituteId, actorUid, appUserSnap, staffSnap);
}

/**
 * Resolves the caller inside a Firestore transaction (all reads precede the
 * caller's writes) using the transaction reader so the classification is
 * consistent with the business write.
 */
async function resolveTenantActorInTransaction(transaction, db, authContext, instituteId) {
  const actorUid = authContext && authContext.uid;
  if (!actorUid) return { actorRole: "unknown", actorName: "" };
  const [appUserSnap, staffSnap] = await Promise.all([
    transaction.get(db.collection("app_users").doc(actorUid)),
    transaction.get(db.collection("institutes").doc(instituteId).collection("staffs").doc(actorUid)),
  ]);
  return classifyTenantActor(instituteId, actorUid, appUserSnap, staffSnap);
}

function activityActorLabel(actor) {
  const name = actor && typeof actor.actorName === "string" && actor.actorName.trim()
    ? actor.actorName.trim()
    : "";
  return name || (actor && actor.actorRole) || "Tenant";
}

module.exports = {
  activityActorLabel,
  classifyTenantActor,
  newTenantActivity,
  platformActivityRef,
  resolveTenantActor,
  resolveTenantActorInTransaction,
  setTenantActivity,
  transactionTenantActivity,
};
