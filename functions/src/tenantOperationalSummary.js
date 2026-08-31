"use strict";

const {
  isStaleSummaryEvent,
  shouldRefreshOperationalSummary,
  summaryPayload,
} = require("./tenantOperationalSummaryCore");

function countFromAggregate(snapshot) {
  return snapshot && typeof snapshot.data === "function" ? snapshot.data().count : 0;
}

function createTenantOperationalSummaryHandler({ db, now = () => Date.now() }) {
  if (!db) throw new TypeError("db is required");

  return async (event) => {
    const before = event?.data?.before;
    const after = event?.data?.after;
    if (!shouldRefreshOperationalSummary(before, after)) {
      return { reconciled: false, reason: "active_count_unchanged" };
    }

    const instituteId = event?.params?.instituteId;
    if (typeof instituteId !== "string" || !instituteId) {
      throw new TypeError("instituteId is required");
    }

    const instituteRef = db.collection("institutes").doc(instituteId);
    const summaryRef = instituteRef.collection("dashboard_summary").doc("current");
    const [students, batches, staffs] = await Promise.all([
      instituteRef.collection("students").where("status", "==", "active").count().get(),
      instituteRef.collection("batches").where("status", "==", "active").count().get(),
      instituteRef.collection("staffs").where("status", "==", "active").count().get(),
    ]);
    const payload = summaryPayload({
      studentCount: countFromAggregate(students),
      batchCount: countFromAggregate(batches),
      staffCount: countFromAggregate(staffs),
      event,
      updatedAtMs: now(),
    });

    return db.runTransaction(async (transaction) => {
      const [instituteSnap, summarySnap] = await Promise.all([
        transaction.get(instituteRef),
        transaction.get(summaryRef),
      ]);
      if (!instituteSnap.exists) {
        return { reconciled: false, reason: "institute_missing" };
      }
      const currentSummary = summarySnap.exists ? summarySnap.data() : null;
      // CloudEvents may execute out of order. An older aggregate snapshot must
      // never overwrite the result produced for a newer committed write.
      if (isStaleSummaryEvent(currentSummary, event)) {
        return { reconciled: false, reason: "stale_event" };
      }

      transaction.set(summaryRef, payload, { merge: true });
      transaction.update(instituteRef, {
        studentCount: payload.studentCount,
        batchCount: payload.batchCount,
        staffCount: payload.staffCount,
        countSummaryUpdatedAtMs: payload.updatedAtMs,
      });
      return { reconciled: true, ...payload };
    });
  };
}

module.exports = { createTenantOperationalSummaryHandler };
