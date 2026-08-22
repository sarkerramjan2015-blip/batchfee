"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createPermanentInstitutePurgeHandler } = require("../src/permanentInstitutePurge");

class Snapshot {
  constructor(ref, data) {
    this.ref = ref;
    this.id = ref.path.split("/").at(-1);
    this.exists = data !== undefined;
    this._data = data === undefined ? undefined : structuredClone(data);
  }
  data() { return this._data === undefined ? undefined : structuredClone(this._data); }
  get(field) { return this._data && this._data[field]; }
}

class Query {
  constructor(db, path, filters = [], maxResults = Infinity) {
    this.db = db; this.path = path; this.filters = filters; this.maxResults = maxResults;
  }
  where(field, operator, value) {
    return new Query(this.db, this.path, [...this.filters, [field, operator, value]], this.maxResults);
  }
  limit(maxResults) { return new Query(this.db, this.path, this.filters, maxResults); }
  async get() {
    const depth = this.path.split("/").length + 1;
    const docs = [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value))
      .slice(0, this.maxResults)
      .map(([path, data]) => new Snapshot(new Document(this.db, path), data));
    return { docs, empty: docs.length === 0 };
  }
}

class Collection extends Query {
  doc(id) { return new Document(this.db, `${this.path}/${id}`); }
}

class Document {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new Collection(this.db, `${this.path}/${name}`); }
  async get() { return new Snapshot(this, this.db.documents.get(this.path)); }
  async delete() { this.db.documents.delete(this.path); }
}

class FakeDb {
  constructor(documents) { this.documents = new Map(Object.entries(documents)); }
  collection(name) { return new Collection(this, name); }
  batch() {
    const deleted = [];
    return {
      delete: (ref) => deleted.push(ref.path),
      commit: async () => deleted.forEach((path) => this.documents.delete(path)),
    };
  }
  async recursiveDelete(ref) {
    for (const path of [...this.documents.keys()]) {
      if (path === ref.path || path.startsWith(`${ref.path}/`)) this.documents.delete(path);
    }
  }
}

function archivedInstitute() {
  return {
    instituteName: "Archived Institute",
    ownerUid: "owner-a",
    deletionState: "retained",
    archivedAtMs: 10,
    subscriptionStatus: "deletion_pending",
  };
}

function testDependencies(db) {
  const deletedUsers = [];
  const deletedPrefixes = [];
  return {
    deletedUsers,
    deletedPrefixes,
    handler: createPermanentInstitutePurgeHandler({
      db,
      adminAuth: { deleteUser: async (uid) => deletedUsers.push(uid) },
      bucket: { deleteFiles: async ({ prefix }) => deletedPrefixes.push(prefix) },
    }),
  };
}

test("Super Admin permanently purges an archived institute and every linked cloud record", async () => {
  const db = new FakeDb({
    "app_users/root-admin": { role: "SuperAdmin", status: "active" },
    "app_users/owner-a": { role: "InstituteOwner", status: "archived", instituteId: "institute-a" },
    "app_users/staff-a": { role: "Staff", status: "archived", instituteId: "institute-a" },
    "institutes/institute-a": archivedInstitute(),
    "institutes/institute-a/students/student-a": { firebaseUid: "student-auth-a" },
    "institutes/institute-a/staffs/staff-a": { firebaseUid: "staff-auth-a" },
    "institutes/institute-a/fees/fee-a": { studentId: "student-a" },
    "institutes/institute-b": { instituteName: "Keep Me" },
    "student_auth_accounts/student-auth-a": { instituteId: "institute-a" },
    "student_auth_logins/login-a": { instituteId: "institute-a", studentId: "student-a" },
    "student_auth_attempts/login-a": { failedAttempts: 2 },
    "student_login_mappings/10001": { instituteId: "institute-a" },
    "subscriptionRequests/request-a": { instituteId: "institute-a" },
    "public_registration_profiles/profile-a": { instituteId: "institute-a" },
    "public_registration_dedup/dedup-a": { instituteId: "institute-a" },
    "platform_audit/audit-a": { instituteId: "institute-a" },
    "registrations/institute-a/pending/request-a": { instituteId: "institute-a" },
  });
  const { handler, deletedUsers, deletedPrefixes } = testDependencies(db);

  const result = await handler({ auth: { uid: "root-admin" }, data: { instituteId: "institute-a" } });

  assert.deepEqual(result, { instituteId: "institute-a", permanentlyDeleted: true, alreadyDeleted: false });
  assert.equal([...db.documents.keys()].some((path) => path.includes("institute-a")), false);
  assert.equal(db.documents.has("student_auth_attempts/login-a"), false);
  assert.equal(db.documents.has("student_login_mappings/10001"), false);
  assert.equal(db.documents.has("app_users/root-admin"), true);
  assert.equal(db.documents.has("institutes/institute-b"), true);
  assert.deepEqual(new Set(deletedUsers), new Set(["owner-a", "staff-a", "staff-auth-a", "student-auth-a", "institute-a"]));
  assert.equal(deletedPrefixes.length, 3);
});

test("tenant users cannot permanently purge an institute", async () => {
  const db = new FakeDb({
    "app_users/owner-a": { role: "InstituteOwner", status: "archived", instituteId: "institute-a" },
    "institutes/institute-a": archivedInstitute(),
  });
  const { handler } = testDependencies(db);

  await assert.rejects(
    handler({ auth: { uid: "owner-a" }, data: { instituteId: "institute-a" } }),
    (error) => error.code === "permission-denied",
  );
  assert.equal(db.documents.has("institutes/institute-a"), true);
});

test("even a Super Admin cannot purge an active institute", async () => {
  const db = new FakeDb({
    "app_users/root-admin": { role: "SuperAdmin", status: "active" },
    "institutes/institute-a": { instituteName: "Active Institute", subscriptionStatus: "active", isActive: true },
  });
  const { handler } = testDependencies(db);

  await assert.rejects(
    handler({ auth: { uid: "root-admin" }, data: { instituteId: "institute-a" } }),
    (error) => error.code === "failed-precondition",
  );
  assert.equal(db.documents.has("institutes/institute-a"), true);
});
