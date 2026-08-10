"use strict";

const { applicationDefault, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const {
  cleanupLegacyStudentCredentials,
} = require("../src/legacyStudentCredentialCleanup");

function argumentValue(name) {
  const exactIndex = process.argv.indexOf(name);
  if (exactIndex >= 0) return process.argv[exactIndex + 1];
  const prefix = `${name}=`;
  const argument = process.argv.find((value) => value.startsWith(prefix));
  return argument ? argument.slice(prefix.length) : undefined;
}

async function main() {
  const projectId = argumentValue("--project");
  const confirmedProjectId = argumentValue("--confirm-project");
  const apply = process.argv.includes("--apply");
  const disableLegacyAuth = process.argv.includes("--disable-legacy-auth");
  const isEmulator = Boolean(
    process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_AUTH_EMULATOR_HOST,
  );

  if (!projectId) {
    throw new Error("A target project is required: --project <firebase-project-id>.");
  }
  if (apply && confirmedProjectId !== projectId) {
    throw new Error("Apply mode requires --confirm-project to exactly match --project.");
  }
  if (apply && !disableLegacyAuth) {
    throw new Error("Apply mode requires --disable-legacy-auth.");
  }

  initializeApp({
    projectId,
    ...(isEmulator ? {} : { credential: applicationDefault() }),
  });
  const result = await cleanupLegacyStudentCredentials({
    db: getFirestore(),
    auth: getAuth(),
    dryRun: !apply,
    disableLegacyAuth,
  });

  process.stdout.write(`${JSON.stringify({ projectId, ...result }, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.message || error}\n`);
  process.exitCode = 1;
});
