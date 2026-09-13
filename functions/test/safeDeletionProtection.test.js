"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  hasPlatformAdminRole,
  resolveInstituteOwnerUid,
  isManagedInstitutePrincipal,
  assertAuthority,
  isProtectedAuthIdentity,
} = require("../src/safeDeletion");

test("institute cleanup resolves the stored owner UID before using the legacy document ID", () => {
  assert.equal(resolveInstituteOwnerUid("legacy-institute", { ownerUid: "owner-uid" }), "owner-uid");
  assert.equal(resolveInstituteOwnerUid("legacy-institute", {}), "legacy-institute");
});

test("safe deletion recognises active managed owners and admins as institute principals", () => {
  assert.equal(isManagedInstitutePrincipal({
    instituteId: "institute-a", role: "InstituteOwner", status: "active",
  }, "institute-a"), true);
  assert.equal(isManagedInstitutePrincipal({
    instituteId: "institute-a", role: "admin", status: "active",
  }, "institute-a"), true);
  assert.equal(isManagedInstitutePrincipal({
    instituteId: "institute-a", role: "InstituteOwner", status: "archived",
  }, "institute-a"), false);
  assert.equal(isManagedInstitutePrincipal({
    instituteId: "another-institute", role: "InstituteOwner", status: "active",
  }, "institute-a"), false);
});

test("safe deletion accepts the canonical owner UID even when it differs from the institute document ID", () => {
  const institute = {
    ownerUid: "owner-auth-uid",
    subscriptionStatus: "active",
    currentPeriodEndMs: Date.now() + 60_000,
  };
  assert.doesNotThrow(() => assertAuthority({
    auth: { uid: "owner-auth-uid" },
    institute,
    appUser: null,
    staff: null,
    instituteId: "legacy-institute-document-id",
    entityType: "student",
    action: "archive",
  }));
});

test("the caller and every platform root identity are protected from auth cleanup", () => {
  assert.equal(isProtectedAuthIdentity({
    actorUid: "root-admin", authUid: "root-admin", appUser: null,
  }), true);
  assert.equal(isProtectedAuthIdentity({
    actorUid: "root-admin", authUid: "other-root", appUser: { role: "SuperAdmin", status: "archived" },
  }), true);
  assert.equal(isProtectedAuthIdentity({
    actorUid: "root-admin", authUid: "owner-uid", appUser: { role: "InstituteOwner" },
  }), false);
  assert.equal(hasPlatformAdminRole({ platformRole: "root", status: "archived" }), true);
});
