"use strict";
// Run only under: firebase emulators:exec --project demo-batchfee-rules --only firestore
const test = require("node:test");
const assert = require("node:assert/strict");
if (!process.env.FIRESTORE_EMULATOR_HOST || !["127.0.0.1", "localhost", "[::1]"].some(
  host => process.env.FIRESTORE_EMULATOR_HOST.startsWith(`${host}:`))) {
  throw new Error("Local Firestore emulator is required. Production access is forbidden.");
}
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { createBatchEnrollmentHandler } = require("../src/batchEnrollment");
const app = initializeApp({ projectId: "demo-batchfee-rules" }, "enrollment-integration");
const db = getFirestore(app);
const date = Date.UTC(2026, 7, 1);
let serial = 0;
async function setup() {
  const instituteId = `enrollment-test-${Date.now()}-${++serial}`;
  const ref = db.collection("institutes").doc(instituteId);
  await Promise.all([
    ref.set({ isActive: true, subscriptionStatus: "active", currentPeriodEndMs: Date.now() + 86400000 }),
    ref.collection("students").doc("s").set({ status: "active", admissionDateMs: date }),
    ref.collection("batches").doc("b").set({ status: "active", billingMode: "course", monthlyFeeAmount: 0, courseFeeAmount: 5000 }),
  ]);
  const handler = createBatchEnrollmentHandler({ db,
    authorize: async (auth, tenant, permission) => {
      assert.equal(auth.uid, "test-owner"); assert.equal(tenant, instituteId); assert.equal(permission, "manage_batch");
    },
    proratedMonthlyTerms: () => ({ firstMonthFeePeriod: "Aug 2026", firstMonthFeeAmount: 1000 }),
  });
  const request = n => ({ auth: { uid: "test-owner" }, data: {
    instituteId, studentId: "s", batchId: "b", enrollmentId: `e${n}`, operationId: `op${n}`,
    enrollmentStartMs: date, admissionDateLinked: true,
  } });
  return { ref, handler, request };
}
test.after(async () => { await db.terminate(); await deleteApp(app); });
test("real Firestore transactions serialize simultaneous course assignments", async () => {
  const { ref, handler, request } = await setup();
  const [a, b] = await Promise.all([handler(request(1)), handler(request(2))]);
  assert.equal(a.enrollment.id, b.enrollment.id);
  assert.equal((await ref.collection("batch_students").get()).size, 1);
  assert.equal((await ref.collection("fees").get()).size, 1);
  assert.equal((await ref.collection("fees").get()).docs[0].get("totalAmount"), 5000);
});
test("invalid course leaves no enrollment or fee in real Firestore", async () => {
  const { ref, handler, request } = await setup();
  await ref.collection("batches").doc("b").update({ courseFeeAmount: 0 });
  await assert.rejects(handler(request(1)));
  assert.equal((await ref.collection("batch_students").get()).size, 0);
  assert.equal((await ref.collection("fees").get()).size, 0);
});
test("real transaction replay returns current payment balance", async () => {
  const { ref, handler, request } = await setup();
  const result = await handler(request(1));
  await ref.collection("fees").doc(result.fee.id).update({ paidAmount: 3000, dueAmount: 2000, status: "partially_paid" });
  const replay = await handler(request(1));
  assert.equal(replay.fee.paidAmount, 3000);
  assert.equal(replay.fee.dueAmount, 2000);
});
