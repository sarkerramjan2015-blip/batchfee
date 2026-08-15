"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  ACTIONS,
  PERMISSIONS,
  PLATFORM_ROLES,
  assertCanAssignInstituteOwner,
  assertCanAssignPlatformRole,
  platformRoleFor,
} = require("../src/platformAdmin");

test("platform roles are explicit and legacy SuperAdmin remains root-compatible", () => {
  assert.deepEqual([...PLATFORM_ROLES].sort(), ["billing", "operations", "read_only", "root", "support"]);
  assert.equal(platformRoleFor({ role: "SuperAdmin", status: "active" }), "root");
  assert.equal(platformRoleFor({ role: "PlatformAdmin", platformRole: "billing", status: "active" }), "billing");
  assert.equal(platformRoleFor({ role: "PlatformAdmin", platformRole: "root", status: "suspended" }), null);
});

test("least-privilege permissions keep account authority and owner recovery separate", () => {
  assert.ok(ACTIONS.has("create_institute"));
  assert.ok(PERMISSIONS.root.has("manage_platform_admin"));
  assert.ok(PERMISSIONS.operations.has("create_institute"));
  assert.ok(!PERMISSIONS.operations.has("manage_platform_admin"));
  assert.ok(PERMISSIONS.support.has("send_owner_recovery"));
  assert.ok(!PERMISSIONS.support.has("transfer_owner"));
  assert.ok(!PERMISSIONS.read_only.has("create_institute"));
});

test("platform and institute identities cannot overwrite one another", () => {
  assert.throws(
    () => assertCanAssignInstituteOwner({ role: "SuperAdmin", status: "active" }, "inst_a"),
    /platform account cannot be assigned/i,
  );
  assert.throws(
    () => assertCanAssignInstituteOwner({ role: "InstituteOwner", instituteId: "inst_a" }, "inst_b"),
    /belongs to another institute/i,
  );
  assert.throws(
    () => assertCanAssignPlatformRole({ role: "InstituteOwner", instituteId: "inst_a" }),
    /institute account cannot receive/i,
  );
  assert.throws(
    () => assertCanAssignPlatformRole({ role: "SuperAdmin", status: "active" }),
    /Root access cannot be replaced/i,
  );
  assert.doesNotThrow(() => assertCanAssignInstituteOwner({ role: "InstituteAdmin", instituteId: "inst_a" }, "inst_a"));
  assert.doesNotThrow(() => assertCanAssignPlatformRole({ role: "PlatformAdmin", platformRole: "billing" }));
});
