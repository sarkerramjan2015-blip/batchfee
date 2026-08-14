"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createPermanentStudentPurgeHandler } = require("../src/permanentStudentPurge");

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
    this.db = db;
    this.path = path;
    this.filters = filters;
    this.maxResults = maxResults;
  }

  where(field, operator, value) {
    return new Query(this.db, this.path, [...this.filters, [field, operator, value]], this.maxResults);
  }

  limit(maxResults) { return new Query(this.db, this.path, this.filters, maxResults); }

  async get() {
    const pathDepth = this.path.split("/").length + 1;
    const docs = [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === pathDepth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) =>
        operator === "==" && data[field] === value,
      ))
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
}

function liveInstitute(now) {
  return {
    currentPlanId: "plan_free_trial",
    subscriptionStatus: "trial",
    currentPeriodEndMs: now + 60_000,
    isActive: true,
  };
}

function archivedStudent() {
  return {
    fullName: "Archived Student",
    studentCode: "STU-100",
    status: "archived",
    archivedAtMs: 1,
    firebaseUid: "student-auth-uid",
  };
}

function request() {
  return {
    auth: { uid: "institute-a" },
    data: {
      instituteId: "institute-a",
      studentId: "student-a",
      confirmationName: "Archived Student",
    },
  };
}

test("permanent purge is denied after subscription expiry", async () => {
  const db = new FakeDb({
    "institutes/institute-a": { ...liveInstitute(Date.now()), currentPeriodEndMs: Date.now() - 1 },
    "institutes/institute-a/students/student-a": archivedStudent(),
  });
  const handler = createPermanentStudentPurgeHandler({
    db,
    adminAuth: { deleteUser: async () => {} },
    bucket: { name: "bucket", file: () => ({ delete: async () => {} }) },
  });

  await assert.rejects(handler(request()), (error) => error.code === "failed-precondition");
  assert.equal(db.documents.has("institutes/institute-a/students/student-a"), true);
});

test("permanent purge clears student authentication artifacts", async () => {
  const now = Date.now();
  const db = new FakeDb({
    "institutes/institute-a": liveInstitute(now),
    "institutes/institute-a/students/student-a": archivedStudent(),
    "student_auth_accounts/student-auth-uid": { instituteId: "institute-a", studentId: "student-a" },
    "student_auth_logins/login-key": {
      instituteId: "institute-a", studentId: "student-a", firebaseUid: "student-auth-uid",
    },
    "student_auth_attempts/login-key": { failedAttempts: 2 },
    "student_login_mappings/STU-100": { firebaseUid: "student-auth-uid" },
  });
  const deletedAuthUsers = [];
  const handler = createPermanentStudentPurgeHandler({
    db,
    adminAuth: { deleteUser: async (uid) => deletedAuthUsers.push(uid) },
    bucket: { name: "bucket", file: () => ({ delete: async () => {} }) },
  });

  const result = await handler(request());
  assert.deepEqual(result, { studentId: "student-a", permanentlyDeleted: true });
  assert.equal(db.documents.has("institutes/institute-a/students/student-a"), false);
  assert.equal(db.documents.has("student_auth_accounts/student-auth-uid"), false);
  assert.equal(db.documents.has("student_auth_logins/login-key"), false);
  assert.equal(db.documents.has("student_auth_attempts/login-key"), false);
  assert.equal(db.documents.has("student_login_mappings/STU-100"), false);
  assert.deepEqual(deletedAuthUsers, ["student-auth-uid"]);
});
