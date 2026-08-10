"use strict";

const { FieldPath, FieldValue } = require("firebase-admin/firestore");

const LEGACY_STUDENT_CREDENTIAL_FIELDS = Object.freeze([
  "studentPasswordHash",
  "appAccessEmail",
  "appAccessPassword",
  "passwordHash",
  "passwordSalt",
  "passwordVerifier",
  "virtualEmail",
  "authEmail",
]);

const LEGACY_STUDENT_EMAIL_SUFFIX = "@s.batchfee.app";
const DEFAULT_PAGE_SIZE = 250;

function hasLegacyStudentCredentialFields(data) {
  return Boolean(data) && LEGACY_STUDENT_CREDENTIAL_FIELDS.some((field) =>
    Object.prototype.hasOwnProperty.call(data, field));
}

function isLegacyStudentEmail(email) {
  return typeof email === "string" &&
    email.trim().toLowerCase().endsWith(LEGACY_STUDENT_EMAIL_SUFFIX);
}

async function scanLegacyAuthUsers(auth, { dryRun }) {
  let pageToken;
  let found = 0;
  let enabledFound = 0;
  let disabled = 0;
  let tokensRevoked = 0;

  do {
    const page = await auth.listUsers(1000, pageToken);
    for (const user of page.users) {
      if (!isLegacyStudentEmail(user.email)) continue;
      found += 1;
      if (!user.disabled) enabledFound += 1;
      if (!dryRun) {
        if (!user.disabled) {
          await auth.updateUser(user.uid, { disabled: true });
          disabled += 1;
        }
        await auth.revokeRefreshTokens(user.uid);
        tokensRevoked += 1;
      }
    }
    pageToken = page.pageToken;
  } while (pageToken);

  return { found, enabledFound, disabled, tokensRevoked };
}

async function scanAndCleanStudentDocuments(db, { dryRun, pageSize }) {
  let query = db.collectionGroup("students")
    .orderBy(FieldPath.documentId())
    .limit(pageSize);
  let scanned = 0;
  let found = 0;
  let cleaned = 0;

  while (true) {
    const snapshot = await query.get();
    if (snapshot.empty) break;

    const batch = dryRun ? null : db.batch();
    let pageCleaned = 0;
    for (const document of snapshot.docs) {
      scanned += 1;
      if (!hasLegacyStudentCredentialFields(document.data())) continue;
      found += 1;
      if (batch) {
        const removals = Object.fromEntries(
          LEGACY_STUDENT_CREDENTIAL_FIELDS.map((field) => [field, FieldValue.delete()]),
        );
        batch.update(document.ref, removals);
        cleaned += 1;
        pageCleaned += 1;
      }
    }
    if (batch && pageCleaned > 0) await batch.commit();

    if (snapshot.size < pageSize) break;
    query = db.collectionGroup("students")
      .orderBy(FieldPath.documentId())
      .startAfter(snapshot.docs[snapshot.docs.length - 1])
      .limit(pageSize);
  }

  return { scanned, found, cleaned };
}

async function scanAndDeleteLegacyMappings(db, { dryRun, pageSize }) {
  let query = db.collection("student_login_mappings")
    .orderBy(FieldPath.documentId())
    .limit(pageSize);
  let found = 0;
  let deleted = 0;

  while (true) {
    const snapshot = await query.get();
    if (snapshot.empty) break;

    const batch = dryRun ? null : db.batch();
    for (const document of snapshot.docs) {
      found += 1;
      if (batch) {
        batch.delete(document.ref);
        deleted += 1;
      }
    }
    if (batch) await batch.commit();

    if (snapshot.size < pageSize) break;
    query = db.collection("student_login_mappings")
      .orderBy(FieldPath.documentId())
      .startAfter(snapshot.docs[snapshot.docs.length - 1])
      .limit(pageSize);
  }

  return { found, deleted };
}

async function cleanupLegacyStudentCredentials({
  db,
  auth,
  dryRun = true,
  disableLegacyAuth = false,
  pageSize = DEFAULT_PAGE_SIZE,
}) {
  if (!db || !auth) throw new TypeError("Firestore and Auth clients are required.");
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 500) {
    throw new RangeError("pageSize must be an integer from 1 to 500.");
  }
  if (!dryRun && !disableLegacyAuth) {
    throw new Error(
      "Apply mode requires legacy virtual-email Auth users to be disabled and revoked.",
    );
  }

  // Revoke legacy virtual-email identities before removing their Firestore
  // metadata. New P0-03 student identities have no email and are unaffected.
  const authUsers = await scanLegacyAuthUsers(auth, { dryRun });
  const studentDocuments = await scanAndCleanStudentDocuments(db, { dryRun, pageSize });
  const legacyMappings = await scanAndDeleteLegacyMappings(db, { dryRun, pageSize });

  return {
    dryRun,
    authUsers,
    studentDocuments,
    legacyMappings,
  };
}

module.exports = {
  LEGACY_STUDENT_CREDENTIAL_FIELDS,
  cleanupLegacyStudentCredentials,
  hasLegacyStudentCredentialFields,
  isLegacyStudentEmail,
};
