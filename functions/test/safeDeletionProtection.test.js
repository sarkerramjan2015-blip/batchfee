"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  hasPlatformAdminRole,
  resolveInstituteOwnerUid,
  isProtectedAuthIdentity,
} = require("../src/safeDeletion");

test("institute cleanup resolves the stored owner UID before using the legacy document ID", () => {
  assert.equal(resolveInstituteOwnerUid("legacy-institute", { ownerUid: "owner-uid" }), "owner-uid");
  assert.equal(resolveInstituteOwnerUid("legacy-institute", {}), "legacy-institute");
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
