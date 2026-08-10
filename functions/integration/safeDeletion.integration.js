"use strict";

const assert = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { createSafeDeletionHandler } = require("../src/safeDeletion");

const projectId = process.env.GCLOUD_PROJECT || "demo-batchfee-deletion";
if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run this test through the Firestore emulator.");
}

initializeApp({ projectId });
const db = getFirestore();
const instituteId = "deletion-owner";
const adminUid = "deletion-superadmin";
const studentId = "deletion-student";
const batchId = "deletion-batch";
const studentUid = "deletion-student-auth";
const retainedMediaAssetId = "a".repeat(32);
const retainedMediaReference = `batchfee-media://v1/${instituteId}/${retainedMediaAssetId}`;

class FakeAdminAuth {
  constructor() {
    this.users = new Map();
    this.failNextUpdate = false;
  }

  add(uid, customClaims = {}) {
    this.users.set(uid, { uid, disabled: false, customClaims });
  }

  async getUser(uid) {
    const user = this.users.get(uid);
    if (!user) {
      const error = new Error("User not found");
      error.code = "auth/user-not-found";
      throw error;
    }
    return user;
  }

  async updateUser(uid, patch) {
    if (this.failNextUpdate) {
      this.failNextUpdate = false;
      throw new Error("simulated Auth outage");
    }
    const user = await this.getUser(uid);
    Object.assign(user, patch);
    return user;
  }

  async revokeRefreshTokens(uid) {
    await this.getUser(uid);
  }
}

const adminAuth = new FakeAdminAuth();
const commit = createSafeDeletionHandler({ db, adminAuth });

function request(actorUid, operationId, entityType, entityId, action, reason) {
  return {
    auth: { uid: actorUid, token: {} },
    data: { operationId, instituteId, entityType, entityId, action, reason },
  };
}

async function expectCode(expectedCode, work) {
  let failure;
  try {
    await work();
  } catch (error) {
    failure = error;
  }
  assert.equal(failure && failure.code, expectedCode, failure && failure.stack);
}

async function main() {
  const instituteRef = db.collection("institutes").doc(instituteId);
  await instituteRef.set({
    instituteName: "Deletion Integration",
    isActive: true,
    status: "active",
    subscriptionStatus: "active",
    profilePhotoUri: "https://media.example/institute.jpg",
  });
  await db.collection("app_users").doc(adminUid).set({ role: "SuperAdmin", status: "active" });
  await db.collection("app_users").doc(instituteId).set({
    role: "InstituteAdmin",
    instituteId,
    status: "active",
  });
  await instituteRef.collection("students").doc(studentId).set({
    instituteId,
    studentCode: "ST-1",
    fullName: "Retained Student",
    status: "active",
    archivedAtMs: null,
    isAppAccessEnabled: true,
    firebaseUid: studentUid,
    photoUri: retainedMediaReference,
  });
  await instituteRef.collection("media_assets").doc(retainedMediaAssetId).set({
    instituteId,
    assetId: retainedMediaAssetId,
    reference: retainedMediaReference,
    deliveryType: "authenticated",
    purpose: "student_photo",
    status: "active",
    cleanupState: "retained",
  });
  await instituteRef.collection("batches").doc(batchId).set({
    instituteId,
    name: "Retained Batch",
    status: "active",
    archivedAtMs: null,
  });
  await instituteRef.collection("fees").doc("fee-retained").set({
    instituteId,
    studentId,
    batchId,
    totalAmount: 1000,
  });
  await instituteRef.collection("payments").doc("payment-retained").set({
    instituteId,
    studentId,
    feeId: "fee-retained",
    amount: 500,
  });
  await instituteRef.collection("receipts").doc("receipt-retained").set({
    instituteId,
    studentId,
    feeId: "fee-retained",
    paymentId: "payment-retained",
  });
  await instituteRef.collection("attendance").doc("attendance-retained").set({
    instituteId,
    studentId,
    batchId,
  });
  await db.collection("student_auth_accounts").doc(studentUid).set({
    instituteId,
    studentId,
    loginKey: "retained-login-key",
    status: "active",
  });
  await db.collection("student_auth_logins").doc("retained-login-key").set({
    instituteId,
    studentId,
    firebaseUid: studentUid,
    enabled: true,
  });
  adminAuth.add(studentUid, { studentManaged: true, instituteId, studentId });
  adminAuth.add(instituteId);

  const archiveStudentRequest = request(
    instituteId,
    "operation-student-archive-0001",
    "student",
    studentId,
    "archive",
    "Student left the institute",
  );
  const archivedStudent = await commit(archiveStudentRequest);
  assert.equal(archivedStudent.status, "archived");
  assert.equal(archivedStudent.hardDeleteAllowed, false);
  assert.equal(archivedStudent.mediaCleanupState, "retained");
  assert.equal(archivedStudent.authCleanupState, "complete");
  assert.equal(adminAuth.users.get(studentUid).disabled, true);
  assert.equal((await instituteRef.collection("students").doc(studentId).get()).get("status"), "archived");
  assert.equal((await instituteRef.collection("media_assets").doc(retainedMediaAssetId).get()).get("status"), "retained");
  assert.equal((await db.collection("student_auth_logins").doc("retained-login-key").get()).get("enabled"), false);
  for (const [collectionName, documentId] of [
    ["fees", "fee-retained"],
    ["payments", "payment-retained"],
    ["receipts", "receipt-retained"],
    ["attendance", "attendance-retained"],
  ]) {
    assert.equal((await instituteRef.collection(collectionName).doc(documentId).get()).exists, true);
  }
  assert.deepEqual(await commit(archiveStudentRequest), archivedStudent);
  assert.equal((await instituteRef.collection("deletion_audit").get()).size, 1);

  const restoreStudent = await commit(request(
    instituteId,
    "operation-student-restore-0001",
    "student",
    studentId,
    "restore",
    "Student returned to the institute",
  ));
  assert.equal(restoreStudent.status, "active");
  assert.equal(restoreStudent.isAppAccessEnabled, true);
  assert.equal(adminAuth.users.get(studentUid).disabled, false);
  assert.equal((await instituteRef.collection("students").doc(studentId).get()).get("archivedAtMs"), null);
  assert.equal((await instituteRef.collection("media_assets").doc(retainedMediaAssetId).get()).get("status"), "active");

  await instituteRef.collection("students").doc("student-auth-outage").set({
    instituteId,
    studentCode: "ST-2",
    fullName: "Auth Outage Student",
    status: "active",
    archivedAtMs: null,
    isAppAccessEnabled: true,
    firebaseUid: "student-auth-outage-uid",
  });
  await db.collection("student_auth_accounts").doc("student-auth-outage-uid").set({
    instituteId,
    studentId: "student-auth-outage",
    loginKey: "outage-login-key",
  });
  await db.collection("student_auth_logins").doc("outage-login-key").set({
    instituteId,
    studentId: "student-auth-outage",
    firebaseUid: "student-auth-outage-uid",
    enabled: true,
  });
  adminAuth.add("student-auth-outage-uid", {
    studentManaged: true,
    instituteId,
    studentId: "student-auth-outage",
  });
  const outageRequest = request(
    instituteId,
    "operation-student-outage-0001",
    "student",
    "student-auth-outage",
    "archive",
    "Archive during simulated Auth outage",
  );
  adminAuth.failNextUpdate = true;
  await expectCode("unavailable", () => commit(outageRequest));
  assert.equal((await instituteRef.collection("students").doc("student-auth-outage").get()).get("status"), "archived");
  assert.equal((await instituteRef.collection("deletion_operations")
    .doc("operation-student-outage-0001").get()).get("authCleanupState"), "pending");
  const reconciled = await commit(outageRequest);
  assert.equal(reconciled.authCleanupState, "complete");
  assert.equal(adminAuth.users.get("student-auth-outage-uid").disabled, true);

  await instituteRef.collection("students").doc("student-stale-cleanup").set({
    instituteId,
    studentCode: "ST-3",
    fullName: "Stale Cleanup Student",
    status: "active",
    archivedAtMs: null,
    isAppAccessEnabled: true,
    firebaseUid: "student-stale-cleanup-uid",
  });
  await db.collection("student_auth_accounts").doc("student-stale-cleanup-uid").set({
    instituteId,
    studentId: "student-stale-cleanup",
    loginKey: "stale-cleanup-login-key",
  });
  await db.collection("student_auth_logins").doc("stale-cleanup-login-key").set({
    instituteId,
    studentId: "student-stale-cleanup",
    firebaseUid: "student-stale-cleanup-uid",
    enabled: true,
  });
  adminAuth.add("student-stale-cleanup-uid", {
    studentManaged: true,
    instituteId,
    studentId: "student-stale-cleanup",
  });
  const staleArchiveRequest = request(
    instituteId,
    "operation-stale-archive-0001",
    "student",
    "student-stale-cleanup",
    "archive",
    "Archive before immediate recovery",
  );
  adminAuth.failNextUpdate = true;
  await expectCode("unavailable", () => commit(staleArchiveRequest));
  await commit(request(
    instituteId,
    "operation-stale-restore-0001",
    "student",
    "student-stale-cleanup",
    "restore",
    "Recover before old cleanup retry",
  ));
  const superseded = await commit(staleArchiveRequest);
  assert.equal(superseded.authCleanupState, "superseded");
  assert.equal(adminAuth.users.get("student-stale-cleanup-uid").disabled, false);
  assert.equal((await instituteRef.collection("students")
    .doc("student-stale-cleanup").get()).get("status"), "active");

  const archivedBatch = await commit(request(
    instituteId,
    "operation-batch-archive-0001",
    "batch",
    batchId,
    "archive",
    "Batch completed its lifecycle",
  ));
  assert.equal(archivedBatch.status, "archived");
  assert.equal((await instituteRef.collection("batches").doc(batchId).get()).exists, true);
  assert.equal((await instituteRef.collection("fees").doc("fee-retained").get()).exists, true);

  await expectCode("permission-denied", () => commit(request(
    "another-owner",
    "operation-cross-tenant-0001",
    "student",
    studentId,
    "archive",
    "Cross tenant attempt",
  )));
  await expectCode("invalid-argument", () => commit(request(
    instituteId,
    "operation-purge-denied-0001",
    "batch",
    batchId,
    "purge",
    "Attempt an unsupported purge",
  )));

  const archivedInstitute = await commit(request(
    adminUid,
    "operation-institute-archive-0001",
    "institute",
    instituteId,
    "archive",
    "Institute requested controlled archival",
  ));
  assert.equal(archivedInstitute.subscriptionStatus, "deletion_pending");
  assert.equal((await instituteRef.get()).exists, true);
  assert.equal((await instituteRef.get()).get("isActive"), false);
  assert.equal((await db.collection("app_users").doc(instituteId).get()).get("status"), "archived");
  assert.equal((await instituteRef.collection("payments").doc("payment-retained").get()).exists, true);
  assert.equal(adminAuth.users.get(instituteId).disabled, true);

  const restoredInstitute = await commit(request(
    adminUid,
    "operation-institute-restore-0001",
    "institute",
    instituteId,
    "restore",
    "SuperAdmin approved institute recovery",
  ));
  assert.equal(restoredInstitute.subscriptionStatus, "active");
  assert.equal((await instituteRef.get()).get("isActive"), true);
  assert.equal((await instituteRef.collection("fees").doc("fee-retained").get()).exists, true);
  assert.equal(adminAuth.users.get(instituteId).disabled, false);

  process.stdout.write("safe deletion emulator integration: PASS\n");
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
