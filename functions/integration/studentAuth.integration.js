"use strict";

const assert = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const {
  cleanupLegacyStudentCredentials,
} = require("../src/legacyStudentCredentialCleanup");

const projectId = process.env.GCLOUD_PROJECT || "demo-batchfee-functions";
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST;
const functionsHost = process.env.FUNCTIONS_EMULATOR_HOST || "127.0.0.1:5001";
if (!authHost || !process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("Run this test through Firebase Auth, Firestore, and Functions emulators.");
}

initializeApp({ projectId });
const auth = getAuth();
const db = getFirestore();

async function signInCustomToken(customToken) {
  const response = await fetch(
    `http://${authHost}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: customToken, returnSecureToken: true }),
    },
  );
  const data = await response.json();
  assert.equal(response.ok, true, JSON.stringify(data));
  return data;
}

async function refreshIdToken(refreshToken) {
  const response = await fetch(
    `http://${authHost}/securetoken.googleapis.com/v1/token?key=fake-api-key`,
    {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refreshToken }),
    },
  );
  const data = await response.json();
  assert.equal(response.ok, true, JSON.stringify(data));
  return data.id_token;
}

async function callFunction(name, data, idToken) {
  const response = await fetch(
    `http://${functionsHost}/${projectId}/asia-south1/${name}`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(idToken ? { authorization: `Bearer ${idToken}` } : {}),
      },
      body: JSON.stringify({ data }),
    },
  );
  const body = await response.json();
  return { ok: response.ok, status: response.status, body };
}

function decodeClaims(idToken) {
  return JSON.parse(Buffer.from(idToken.split(".")[1], "base64url").toString("utf8"));
}

async function main() {
  const ownerUid = "integration-owner";
  const studentId = "integration-student-doc";
  await auth.createUser({ uid: ownerUid });
  const ownerIdToken = (await signInCustomToken(await auth.createCustomToken(ownerUid))).idToken;
  await db.collection("institutes").doc(ownerUid).set({
    instituteName: "Integration Institute",
    instituteCode: "INT-01",
    isActive: true,
  });
  await db.collection("institutes").doc(ownerUid).collection("students").doc(studentId).set({
    instituteId: ownerUid,
    studentCode: "STU-01",
    fullName: "Integration Student",
    status: "active",
    archivedAtMs: null,
  });

  const legacyUid = "legacy-integration-student";
  await auth.createUser({ uid: legacyUid, email: "legacy-integration@s.batchfee.app" });
  await db.collection("institutes").doc(ownerUid).collection("students")
    .doc("legacy-credential-student").set({
      instituteId: ownerUid,
      studentCode: "LEGACY-01",
      fullName: "Legacy Integration Student",
      studentPasswordHash: "legacy-hash",
      appAccessEmail: "legacy-integration@s.batchfee.app",
    });
  await db.collection("student_login_mappings").doc("LEGACY-01").set({
    instituteId: ownerUid,
    studentDocId: "legacy-credential-student",
  });

  const provision = await callFunction("provisionStudentAccount", {
    instituteId: ownerUid,
    studentId,
    password: "secure-123",
  }, ownerIdToken);
  assert.equal(provision.ok, true, JSON.stringify(provision.body));
  const provisionedUid = provision.body.result.firebaseUid;
  assert.match(provisionedUid, /^student_[a-f0-9]{32}$/);

  const cloudStudent = await db.collection("institutes").doc(ownerUid)
    .collection("students").doc(studentId).get();
  assert.equal(cloudStudent.get("firebaseUid"), provisionedUid);
  assert.equal(cloudStudent.get("isAppAccessEnabled"), true);
  assert.equal(cloudStudent.get("studentPasswordHash"), undefined);
  assert.equal(cloudStudent.get("appAccessEmail"), undefined);
  const linkedStatus = await callFunction("getStudentAccountStatus", {
    instituteId: ownerUid,
    studentId,
  }, ownerIdToken);
  assert.equal(linkedStatus.ok, true, JSON.stringify(linkedStatus.body));
  assert.equal(linkedStatus.body.result.securelyLinked, true);

  const login = await callFunction("loginStudent", {
    studentCode: "stu-01",
    password: "secure-123",
  });
  assert.equal(login.ok, true, JSON.stringify(login.body));
  const studentSignIn = await signInCustomToken(login.body.result.customToken);
  const claims = decodeClaims(studentSignIn.idToken);
  assert.equal(claims.user_id, provisionedUid);
  assert.equal(claims.student, true);
  assert.equal(claims.instituteId, ownerUid);
  assert.equal(claims.studentId, studentId);
  assert.ok(claims.studentSessionExpiresAt > Date.now());
  const refreshedClaims = decodeClaims(await refreshIdToken(studentSignIn.refreshToken));
  assert.equal(refreshedClaims.student, true);
  assert.equal(refreshedClaims.studentId, studentId);
  assert.equal(refreshedClaims.studentSessionExpiresAt, claims.studentSessionExpiresAt);

  const wrongPassword = await callFunction("loginStudent", {
    studentCode: "STU-01",
    password: "wrong-123",
  });
  assert.equal(wrongPassword.ok, false);
  assert.equal(wrongPassword.body.error.status, "UNAUTHENTICATED");

  const disable = await callFunction("disableStudentAccount", {
    instituteId: ownerUid,
    studentId,
  }, ownerIdToken);
  assert.equal(disable.ok, true, JSON.stringify(disable.body));
  assert.equal((await auth.getUser(provisionedUid)).disabled, true);
  assert.equal((await cloudStudent.ref.get()).get("isAppAccessEnabled"), false);
  const disabledStatus = await callFunction("getStudentAccountStatus", {
    instituteId: ownerUid,
    studentId,
  }, ownerIdToken);
  assert.equal(disabledStatus.body.result.securelyLinked, false);

  const disabledLogin = await callFunction("loginStudent", {
    studentCode: "STU-01",
    password: "secure-123",
  });
  assert.equal(disabledLogin.ok, false);
  assert.equal(disabledLogin.body.error.status, "UNAUTHENTICATED");

  const cleanupDryRun = await cleanupLegacyStudentCredentials({ db, auth });
  assert.equal(cleanupDryRun.dryRun, true);
  assert.equal(cleanupDryRun.studentDocuments.found, 1);
  assert.equal(cleanupDryRun.legacyMappings.found, 1);
  assert.equal(cleanupDryRun.authUsers.enabledFound, 1);
  assert.equal((await auth.getUser(legacyUid)).disabled, false);
  assert.equal((await db.collection("institutes").doc(ownerUid).collection("students")
    .doc("legacy-credential-student").get()).get("studentPasswordHash"), "legacy-hash");
  assert.equal((await db.collection("student_login_mappings").doc("LEGACY-01").get()).exists, true);

  const cleanupApply = await cleanupLegacyStudentCredentials({
    db,
    auth,
    dryRun: false,
    disableLegacyAuth: true,
  });
  assert.equal(cleanupApply.studentDocuments.cleaned, 1);
  assert.equal(cleanupApply.legacyMappings.deleted, 1);
  assert.equal(cleanupApply.authUsers.disabled, 1);
  assert.equal(cleanupApply.authUsers.tokensRevoked, 1);
  assert.equal((await auth.getUser(legacyUid)).disabled, true);
  const cleanedLegacyStudent = await db.collection("institutes").doc(ownerUid)
    .collection("students").doc("legacy-credential-student").get();
  assert.equal(cleanedLegacyStudent.get("studentPasswordHash"), undefined);
  assert.equal(cleanedLegacyStudent.get("appAccessEmail"), undefined);
  assert.equal((await db.collection("student_login_mappings").doc("LEGACY-01").get()).exists, false);
  const cleanupVerification = await cleanupLegacyStudentCredentials({ db, auth });
  assert.equal(cleanupVerification.studentDocuments.found, 0);
  assert.equal(cleanupVerification.legacyMappings.found, 0);
  assert.equal(cleanupVerification.authUsers.enabledFound, 0);

  process.stdout.write("student auth emulator integration: PASS\n");
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
