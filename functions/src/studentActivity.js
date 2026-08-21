"use strict";

const { HttpsError } = require("firebase-functions/v2/https");

// These are intentionally high-value actions, rather than every tap or scroll.
// Keeping the vocabulary small makes the owner feed useful and prevents noisy,
// expensive activity data.
const STUDENT_ACTIVITY = Object.freeze({
  login: "Logged in",
  home_opened: "Opened Home",
  work_opened: "Opened Work",
  fees_opened: "Viewed fees and receipts",
  attendance_opened: "Viewed attendance",
  results_opened: "Viewed results",
  profile_opened: "Opened profile",
  documents_opened: "Opened documents",
  heartbeat: "Active in app",
});

const PRESENCE_WRITE_INTERVAL_MS = 90 * 1000;
const DUPLICATE_EVENT_WINDOW_MS = 15 * 1000;

function activityLabel(type) {
  return STUDENT_ACTIVITY[type] || null;
}

function isActiveStudent(student) {
  return student && student.status === "active" && student.archivedAtMs == null &&
    student.isAppAccessEnabled === true;
}

function assertStudentSession(request, now) {
  const auth = request.auth;
  const token = auth && auth.token;
  if (!auth || !auth.uid || !token || token.student !== true ||
      typeof token.instituteId !== "string" || !token.instituteId ||
      typeof token.studentId !== "string" || !token.studentId ||
      typeof token.studentSessionExpiresAt !== "number" || token.studentSessionExpiresAt <= now) {
    throw new HttpsError("unauthenticated", "Student session is not active.");
  }
  return { firebaseUid: auth.uid, instituteId: token.instituteId, studentId: token.studentId };
}

async function activeBatchIds(db, instituteRef, studentId) {
  const enrollments = await instituteRef.collection("batch_students")
    .where("studentId", "==", studentId).get();
  return enrollments.docs
    .filter((doc) => doc.get("status") === "active" && typeof doc.get("batchId") === "string")
    .map((doc) => doc.get("batchId"))
    .sort();
}

/**
 * Records a verified activity event and refreshes the single student-presence
 * document. This is server-only; no client timestamp or student-supplied ID is
 * trusted here.
 */
async function writeStudentActivity({ db, instituteId, studentId, eventType, now }) {
  const label = activityLabel(eventType);
  if (!label) throw new HttpsError("invalid-argument", "Unsupported student activity.");

  const instituteRef = db.collection("institutes").doc(instituteId);
  const presenceRef = instituteRef.collection("student_activity_presence").doc(studentId);
  const [presenceSnap, batchIds] = await Promise.all([
    presenceRef.get(),
    activeBatchIds(db, instituteRef, studentId),
  ]);
  const presence = presenceSnap.exists ? presenceSnap.data() : {};
  const lastSeenAtMs = Number(presence.lastSeenAtMs || 0);
  const lastEventAtMs = Number(presence.lastActivityAtMs || 0);
  const isHeartbeat = eventType === "heartbeat";
  const duplicateEvent = !isHeartbeat && presence.lastActivityType === eventType &&
    now - lastEventAtMs < DUPLICATE_EVENT_WINDOW_MS;
  const shouldRefreshPresence = !presenceSnap.exists || now - lastSeenAtMs >= PRESENCE_WRITE_INTERVAL_MS ||
    !isHeartbeat;

  if (!shouldRefreshPresence && (isHeartbeat || duplicateEvent)) {
    return { recorded: false, batchIds };
  }

  const batch = db.batch();
  const presenceData = { studentId, batchIds, lastSeenAtMs: now, updatedAtMs: now };
  if (!isHeartbeat) {
    presenceData.lastActivityType = eventType;
    presenceData.lastActivityLabel = label;
    presenceData.lastActivityAtMs = now;
  }
  batch.set(presenceRef, presenceData, { merge: true });

  if (!isHeartbeat && !duplicateEvent) {
    batch.set(instituteRef.collection("student_activity").doc(), {
      studentId,
      batchIds,
      eventType,
      label,
      occurredAtMs: now,
    });
  }
  await batch.commit();
  return { recorded: !isHeartbeat && !duplicateEvent, batchIds };
}

function createStudentActivityHandler({ db, hasActiveSubscription, now = () => Date.now() }) {
  return async (request) => {
    const currentTime = now();
    const identity = assertStudentSession(request, currentTime);
    const eventType = typeof request.data?.eventType === "string"
      ? request.data.eventType.trim() : "";
    if (!activityLabel(eventType) || eventType === "login") {
      throw new HttpsError("invalid-argument", "Unsupported student activity.");
    }

    const instituteRef = db.collection("institutes").doc(identity.instituteId);
    const studentRef = instituteRef.collection("students").doc(identity.studentId);
    const [instituteSnap, studentSnap] = await Promise.all([instituteRef.get(), studentRef.get()]);
    const institute = instituteSnap.exists ? instituteSnap.data() : null;
    const student = studentSnap.exists ? studentSnap.data() : null;
    if (!institute || institute.isActive === false || !hasActiveSubscription(institute) ||
        !isActiveStudent(student) || student.firebaseUid !== identity.firebaseUid) {
      throw new HttpsError("permission-denied", "Student access is not active.");
    }

    return writeStudentActivity({
      db,
      instituteId: identity.instituteId,
      studentId: identity.studentId,
      eventType,
      now: currentTime,
    });
  };
}

function requireInstituteId(data) {
  const instituteId = typeof data?.instituteId === "string" ? data.instituteId.trim() : "";
  if (!instituteId || instituteId.length > 128) {
    throw new HttpsError("invalid-argument", "Invalid instituteId.");
  }
  return instituteId;
}

/** Owner-only feed. Keeping this behind a Callable avoids exposing activity
 * records to a client role while the app is in release testing. */
function createStudentActivityFeedHandler({ db, assertCanRead, now = () => Date.now() }) {
  return async (request) => {
    const instituteId = requireInstituteId(request.data);
    await assertCanRead(request.auth, instituteId);
    const instituteRef = db.collection("institutes").doc(instituteId);
    const [eventsSnap, presenceSnap] = await Promise.all([
      instituteRef.collection("student_activity")
        .orderBy("occurredAtMs", "desc").limit(400).get(),
      instituteRef.collection("student_activity_presence").get(),
    ]);
    return {
      serverTimeMs: now(),
      events: eventsSnap.docs.map((doc) => ({ id: doc.id, ...doc.data() })),
      presence: presenceSnap.docs.map((doc) => ({ id: doc.id, ...doc.data() })),
    };
  };
}

module.exports = {
  STUDENT_ACTIVITY,
  createStudentActivityHandler,
  createStudentActivityFeedHandler,
  writeStudentActivity,
};
