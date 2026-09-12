"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  ACTIONS,
  NON_ROOT_PLATFORM_ROLES,
  PERMISSIONS,
  PLATFORM_MEMBER_STATUSES,
  PLATFORM_ROLES,
  assertCanAssignInstituteOwner,
  assertCanAssignPlatformRole,
  CLIENT_NOTE_STATUSES,
  isManageablePlatformMember,
  matchesDirectoryFilters,
  matchesStudentSupportSearch,
  normalizeClientNoteRequest,
  normalizeDirectoryRequest,
  normalizeStudentSupportRequest,
  normalizeTimelineRequest,
  platformRoleFor,
  publicClientNote,
  publicDirectoryInstitute,
  publicPlatformActivity,
  publicPlatformMember,
  studentState,
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
  assert.ok(PERMISSIONS.root.has("list_platform_admins"));
  assert.ok(PERMISSIONS.root.has("update_platform_admin"));
  assert.ok(!PERMISSIONS.billing.has("update_platform_admin"));
  assert.ok(PERMISSIONS.root.has("query_institute_directory"));
  assert.ok(PERMISSIONS.support.has("query_institute_directory"));
  assert.ok(PERMISSIONS.operations.has("query_institute_directory"));
  assert.ok(!PERMISSIONS.billing.has("query_institute_directory"));
  assert.ok(PERMISSIONS.root.has("query_institute_timeline"));
  assert.ok(PERMISSIONS.root.has("get_student_support_details"));
  assert.ok(PERMISSIONS.support.has("query_student_support"));
  assert.ok(PERMISSIONS.support.has("create_client_note"));
  assert.ok(!PERMISSIONS.support.has("query_institute_timeline"));
  assert.ok(!PERMISSIONS.support.has("get_student_support_details"));
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

test("only non-root platform members can appear in the Team Members result", () => {
  assert.deepEqual([...NON_ROOT_PLATFORM_ROLES].sort(), ["billing", "operations", "read_only", "support"]);
  assert.deepEqual([...PLATFORM_MEMBER_STATUSES].sort(), ["active", "suspended"]);
  assert.equal(isManageablePlatformMember({ role: "PlatformAdmin", platformRole: "support" }), true);
  assert.equal(isManageablePlatformMember({ role: "PlatformAdmin", platformRole: "root" }), false);
  assert.equal(isManageablePlatformMember({ role: "InstituteOwner", platformRole: "support" }), false);

  assert.deepEqual(
    publicPlatformMember("team_support_1", {
      name: "Support Agent",
      email: "support@example.com",
      platformRole: "support",
      status: "suspended",
      createdAtMs: 100,
      updatedAtMs: 200,
      passwordHash: "must-not-leak",
      recoveryLink: "must-not-leak",
      instituteId: "must-not-leak",
    }),
    {
      userId: "team_support_1",
      name: "Support Agent",
      email: "support@example.com",
      platformRole: "support",
      status: "suspended",
      createdAtMs: 100,
      updatedAtMs: 200,
    },
  );
});

test("directory query normalizes filters and returns only safe institute card fields", () => {
  const now = Date.UTC(2026, 8, 9, 12, 0, 0);
  const request = normalizeDirectoryRequest({
    query: "  GAIBANDHA  ",
    pageSize: 50,
    filters: {
      status: "active",
      planId: "plan_growth",
      renewalWindow: "30days",
      activityWindow: "7days",
      minStudentCount: 10,
      maxStudentCount: 100,
    },
  });
  assert.equal(request.filters.query, "gaibandha");
  assert.equal(request.filters.status, "active");
  assert.equal(request.filters.minStudentCount, 10);
  assert.equal(request.cursor, null);

  const row = publicDirectoryInstitute("institute_1", {
    instituteName: "Gaibandha Accounting",
    ownerName: "Mohammad Hossain",
    email: "owner@example.test",
    phone: "+8801712345678",
    instituteCode: "GAI-01",
    currentPlanId: "plan_growth",
    subscriptionStatus: "active",
    currentPeriodEndMs: now + 14 * 24 * 60 * 60 * 1_000,
    createdAt: now - 10_000,
    lastActiveAt: now - 2 * 24 * 60 * 60 * 1_000,
    studentCount: 58,
    staffCount: 5,
    batchCount: 4,
    securityPin: "must-not-leak",
    address: "must-not-leak",
  }, now);
  assert.deepEqual(Object.keys(row).sort(), [
    "batchCount", "createdAtMs", "currentPeriodEndMs", "instituteCode", "instituteId",
    "instituteName", "lastActiveAtMs", "ownerEmail", "ownerName", "phone", "staffCount",
    "studentCount", "subscriptionStatus", "currentPlanId",
  ].sort());
  assert.equal(matchesDirectoryFilters(row, request.filters, now), true);
  assert.equal(matchesDirectoryFilters(row, { ...request.filters, query: "01712345678" }, now), true);
  assert.equal(matchesDirectoryFilters(row, { ...request.filters, minStudentCount: 59 }, now), false);
  assert.equal(matchesDirectoryFilters(row, { ...request.filters, activityWindow: "today" }, now), false);
});

test("directory rejects invalid page/filter combinations and cursor reuse", () => {
  assert.throws(
    () => normalizeDirectoryRequest({ pageSize: 40, filters: {} }),
    /page size/i,
  );
  assert.throws(
    () => normalizeDirectoryRequest({ pageSize: 50, filters: { minStudentCount: 20, maxStudentCount: 10 } }),
    /minimum student count/i,
  );
  assert.throws(
    () => normalizeDirectoryRequest({ pageSize: 50, cursor: "not-a-valid-cursor", filters: {} }),
    /cursor/i,
  );
});

test("student support searches only safe active records and validates cursor scope", () => {
  assert.equal(matchesStudentSupportSearch({
    studentCode: "STD-1025", studentCodeNormalized: "std-1025", fullName: "Rafi Islam", phone: "+8801712345678",
  }, "student-1", "std-1025"), true);
  assert.equal(matchesStudentSupportSearch({
    studentCode: "STD-1025", fullName: "Rafi Islam", phone: "+8801712345678",
  }, "student-1", "01712345678"), true);
  assert.equal(matchesStudentSupportSearch({ fullName: "Rafi Islam" }, "student-1", "unknown"), false);
  assert.equal(studentState({ status: "active" }), "active");
  assert.equal(studentState({ status: "closed" }), "inactive");
  assert.equal(studentState({ archivedAtMs: 1 }), "archived");
  assert.throws(
    () => normalizeStudentSupportRequest({ query: "ab" }),
    /at least 3/i,
  );
  assert.throws(
    () => normalizeStudentSupportRequest({ query: "student", cursor: "invalid" }),
    /cursor/i,
  );
});

test("client notes and activity rows are allowlisted and reject credential material", () => {
  assert.deepEqual([...CLIENT_NOTE_STATUSES].sort(), ["follow_up", "open", "resolved"]);
  const note = normalizeClientNoteRequest({
    instituteId: "inst_a", title: "Login follow-up", body: "Owner asked us to check staff access.", status: "follow_up", followUpAtMs: 123,
  });
  assert.equal(note.status, "follow_up");
  assert.throws(
    () => normalizeClientNoteRequest({ instituteId: "inst_a", body: "Password is 123456." }),
    /passwords|OTP|PINs|tokens/i,
  );
  assert.throws(
    () => normalizeTimelineRequest({ instituteId: "inst_a", pageSize: 40 }),
    /page size/i,
  );
  assert.deepEqual(
    publicClientNote("note_1", {
      title: "Follow up", body: "Called owner", status: "open", followUpAtMs: 100, createdAtMs: 50,
      createdByName: "Support", createdByRole: "support", password: "must-not-leak",
    }),
    {
      noteId: "note_1", title: "Follow up", body: "Called owner", status: "open", followUpAtMs: 100,
      createdAtMs: 50, createdByName: "Support", createdByRole: "support",
    },
  );
  assert.deepEqual(
    publicPlatformActivity("event_1", {
      action: "student_support_details_viewed", actorRole: "root", targetType: "student", targetId: "student_a",
      outcome: "completed", summary: "Student support details viewed", occurredAtMs: 999, supportReason: "must-not-leak",
    }),
    {
      eventId: "event_1", action: "student_support_details_viewed", actorRole: "root", targetType: "student",
      targetId: "student_a", outcome: "completed", summary: "Student support details viewed", occurredAtMs: 999,
    },
  );
});
