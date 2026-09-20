"use strict";

// Attendance rows are written directly by staff devices, not through a
// callable. This trigger maintains a lightweight "last attendance activity"
// timestamp on the institute doc so the platform churn engine can read one
// field instead of scanning attendance rows per institute.
//
// The write is monotonic (a stale/out-of-order event can never move the
// timestamp backwards) and happens after the row is committed, so attendance
// saving itself is never blocked by this signal.

function createAttendanceSignalHandler({ db, now = () => Date.now() }) {
  if (!db) throw new TypeError("db is required");

  return async (event) => {
    const after = event && event.data && event.data.after;
    if (!after || !after.exists) return { updated: false, reason: "deleted_row" };
    const instituteId = event.params && event.params.instituteId;
    if (typeof instituteId !== "string" || !instituteId) {
      throw new TypeError("instituteId is required");
    }
    const data = after.data() || {};
    const candidate = Math.max(
      Number(data.attendanceDateMs) || 0,
      Number(data.createdAtMs) || 0,
      Number(data.updatedAtMs) || 0,
      0,
    );
    if (candidate <= 0) return { updated: false, reason: "no_timestamp" };

    const instituteRef = db.collection("institutes").doc(instituteId);
    return db.runTransaction(async (transaction) => {
      const instituteSnap = await transaction.get(instituteRef);
      if (!instituteSnap.exists) return { updated: false, reason: "institute_missing" };
      const existing = Number(instituteSnap.get("lastAttendanceAtMs")) || 0;
      if (candidate <= existing) return { updated: false, reason: "stale_event" };
      transaction.update(instituteRef, { lastAttendanceAtMs: candidate });
      return { updated: true, lastAttendanceAtMs: candidate };
    });
  };
}

module.exports = { createAttendanceSignalHandler };
