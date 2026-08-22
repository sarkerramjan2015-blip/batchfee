"use strict";

const { HttpsError } = require("firebase-functions/v2/https");

const LOGIN_ACTIVITY_RETENTION_DAYS = 30;
const LOGIN_ACTIVITY_RETENTION_MS = LOGIN_ACTIVITY_RETENTION_DAYS * 24 * 60 * 60 * 1000;
const OWNER_ROLES = new Set(["InstituteOwner", "owner", "instituteOwner", "institute_owner"]);
const LOGIN_METHODS = new Set(["password", "biometric"]);

function requireBoundedString(data, field, maxLength) {
  const value = typeof data?.[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return value;
}

function requireSessionId(data) {
  const sessionId = requireBoundedString(data, "sessionId", 80);
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(sessionId)) {
    throw new HttpsError("invalid-argument", "Invalid sessionId.");
  }
  return sessionId;
}

function loginMethod(data) {
  const method = typeof data?.method === "string" ? data.method.trim().toLowerCase() : "password";
  if (!LOGIN_METHODS.has(method)) {
    throw new HttpsError("invalid-argument", "Invalid login method.");
  }
  return method;
}

function isActiveManagedOwner(appUser, instituteId) {
  return appUser && appUser.instituteId === instituteId && OWNER_ROLES.has(appUser.role) &&
    (!Object.prototype.hasOwnProperty.call(appUser, "status") || appUser.status === "active");
}

async function assertInstituteOwner(db, auth, instituteId) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const [instituteSnap, appUserSnap] = await Promise.all([
    db.collection("institutes").doc(instituteId).get(),
    db.collection("app_users").doc(auth.uid).get(),
  ]);
  if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");

  const institute = instituteSnap.data() || {};
  const appUser = appUserSnap.exists ? appUserSnap.data() : null;
  const canonicalOwner = auth.uid === instituteId || auth.uid === institute.ownerUid;
  if (!canonicalOwner && !isActiveManagedOwner(appUser, instituteId)) {
    throw new HttpsError("permission-denied", "Only the institute owner login can be recorded.");
  }
  return institute;
}

function dhakaDayStartMs(timestampMs) {
  const offsetMs = 6 * 60 * 60 * 1000;
  const shifted = new Date(timestampMs + offsetMs);
  return Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()) - offsetMs;
}

function createInstituteOwnerLoginRecorder({ db, now = () => Date.now() }) {
  return async (request) => {
    const instituteId = requireBoundedString(request.data, "instituteId", 128);
    const sessionId = requireSessionId(request.data);
    const method = loginMethod(request.data);
    await assertInstituteOwner(db, request.auth, instituteId);

    const occurredAtMs = now();
    const activityRef = db.collection("institutes").doc(instituteId)
      .collection("owner_login_activity").doc(sessionId);
    const summaryRef = db.collection("institutes").doc(instituteId)
      .collection("owner_login_summary").doc("current");

    const recorded = await db.runTransaction(async (transaction) => {
      const [existingEvent, summarySnap] = await Promise.all([
        transaction.get(activityRef),
        transaction.get(summaryRef),
      ]);
      if (existingEvent.exists) return false;

      const summary = summarySnap.exists ? summarySnap.data() : {};
      const totalLoginCount = Math.max(0, Number(summary?.totalLoginCount || 0)) + 1;
      transaction.set(activityRef, {
        actorUid: request.auth.uid,
        occurredAtMs,
        method,
      });
      transaction.set(summaryRef, {
        totalLoginCount,
        lastLoginAtMs: occurredAtMs,
        lastActorUid: request.auth.uid,
        retentionDays: LOGIN_ACTIVITY_RETENTION_DAYS,
        updatedAtMs: occurredAtMs,
      }, { merge: true });
      return true;
    });

    return { recorded, occurredAtMs, retentionDays: LOGIN_ACTIVITY_RETENTION_DAYS };
  };
}

function createInstituteOwnerLoginFeedHandler({ db, assertPlatformRoot, now = () => Date.now() }) {
  return async (request) => {
    await assertPlatformRoot(request.auth);
    const instituteId = requireBoundedString(request.data, "instituteId", 128);
    const instituteRef = db.collection("institutes").doc(instituteId);
    const instituteSnap = await instituteRef.get();
    if (!instituteSnap.exists) throw new HttpsError("not-found", "Institute not found.");

    const currentTime = now();
    const cutoffMs = currentTime - LOGIN_ACTIVITY_RETENTION_MS;
    const [eventsSnap, summarySnap] = await Promise.all([
      instituteRef.collection("owner_login_activity")
        .where("occurredAtMs", ">=", cutoffMs)
        .orderBy("occurredAtMs", "desc")
        .limit(300)
        .get(),
      instituteRef.collection("owner_login_summary").doc("current").get(),
    ]);
    const events = eventsSnap.docs.map((doc) => ({ id: doc.id, ...doc.data() }));
    const todayStartMs = dhakaDayStartMs(currentTime);
    const summary = summarySnap.exists ? summarySnap.data() : {};

    return {
      instituteId,
      retentionDays: LOGIN_ACTIVITY_RETENTION_DAYS,
      totalLoginCount: Math.max(0, Number(summary?.totalLoginCount || 0)),
      last30DaysCount: events.length,
      todayCount: events.filter((event) => Number(event.occurredAtMs || 0) >= todayStartMs).length,
      lastLoginAtMs: Math.max(0, Number(summary?.lastLoginAtMs || events[0]?.occurredAtMs || 0)),
      events,
    };
  };
}

function createInstituteOwnerLoginCleanupHandler({ db, now = () => Date.now() }) {
  return async () => {
    const cutoffMs = now() - LOGIN_ACTIVITY_RETENTION_MS;
    let deleted = 0;
    for (let page = 0; page < 5; page += 1) {
      const expired = await db.collectionGroup("owner_login_activity")
        .where("occurredAtMs", "<", cutoffMs)
        .limit(400)
        .get();
      if (expired.empty) break;
      const batch = db.batch();
      expired.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
      deleted += expired.size;
      if (expired.size < 400) break;
    }
    return { deleted, cutoffMs };
  };
}

module.exports = {
  LOGIN_ACTIVITY_RETENTION_DAYS,
  LOGIN_ACTIVITY_RETENTION_MS,
  assertInstituteOwner,
  createInstituteOwnerLoginCleanupHandler,
  createInstituteOwnerLoginFeedHandler,
  createInstituteOwnerLoginRecorder,
  dhakaDayStartMs,
};
