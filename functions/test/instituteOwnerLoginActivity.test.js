"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  createInstituteOwnerLoginFeedHandler,
  createInstituteOwnerLoginRecorder,
  dhakaDayStartMs,
} = require("../src/instituteOwnerLoginActivity");

class Snapshot {
  constructor(ref, data) {
    this.ref = ref;
    this.id = ref.path.split("/").at(-1);
    this.exists = data !== undefined;
    this._data = data === undefined ? undefined : structuredClone(data);
  }
  data() { return this._data === undefined ? undefined : structuredClone(this._data); }
}

class Query {
  constructor(db, path, filters = [], ordering = null, maxResults = Infinity) {
    this.db = db; this.path = path; this.filters = filters;
    this.ordering = ordering; this.maxResults = maxResults;
  }
  where(field, operator, value) {
    return new Query(this.db, this.path, [...this.filters, [field, operator, value]], this.ordering, this.maxResults);
  }
  orderBy(field, direction) {
    return new Query(this.db, this.path, this.filters, [field, direction], this.maxResults);
  }
  limit(maxResults) {
    return new Query(this.db, this.path, this.filters, this.ordering, maxResults);
  }
  async get() {
    const depth = this.path.split("/").length + 1;
    let docs = [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => {
        if (operator === ">=") return Number(data[field] || 0) >= value;
        return data[field] === value;
      }));
    if (this.ordering) {
      const [field, direction] = this.ordering;
      docs.sort((a, b) => (Number(a[1][field] || 0) - Number(b[1][field] || 0)) * (direction === "desc" ? -1 : 1));
    }
    const snapshots = docs.slice(0, this.maxResults)
      .map(([path, data]) => new Snapshot(new Document(this.db, path), data));
    return { docs: snapshots, empty: snapshots.length === 0, size: snapshots.length };
  }
}

class Collection extends Query {
  doc(id) { return new Document(this.db, `${this.path}/${id}`); }
}

class Document {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new Collection(this.db, `${this.path}/${name}`); }
  async get() { return new Snapshot(this, this.db.documents.get(this.path)); }
}

class FakeDb {
  constructor(documents = {}) { this.documents = new Map(Object.entries(documents)); }
  collection(name) { return new Collection(this, name); }
  async runTransaction(callback) {
    const writes = [];
    const transaction = {
      get: async (ref) => ref.get(),
      set: (ref, data, options) => writes.push([ref.path, structuredClone(data), options]),
    };
    const result = await callback(transaction);
    writes.forEach(([path, data, options]) => {
      const current = this.documents.get(path) || {};
      this.documents.set(path, options?.merge ? { ...current, ...data } : data);
    });
    return result;
  }
}

test("managed institute owner login is counted once per session", async () => {
  const now = Date.UTC(2026, 7, 22, 6, 0, 0);
  const db = new FakeDb({
    "institutes/institute-a": { ownerUid: "owner-a" },
    "app_users/owner-a": { role: "InstituteOwner", status: "active", instituteId: "institute-a" },
  });
  const handler = createInstituteOwnerLoginRecorder({ db, now: () => now });
  const request = {
    auth: { uid: "owner-a" },
    data: { instituteId: "institute-a", sessionId: "12345678-1234-1234-1234-123456789012", method: "password" },
  };

  assert.equal((await handler(request)).recorded, true);
  assert.equal((await handler(request)).recorded, false);
  assert.equal(db.documents.get("institutes/institute-a/owner_login_summary/current").totalLoginCount, 1);
  assert.equal(db.documents.get("institutes/institute-a/owner_login_activity/12345678-1234-1234-1234-123456789012").method, "password");
});

test("staff or another tenant cannot record an institute owner login", async () => {
  const db = new FakeDb({
    "institutes/institute-a": { ownerUid: "owner-a" },
    "app_users/staff-a": { role: "Staff", status: "active", instituteId: "institute-a" },
  });
  const handler = createInstituteOwnerLoginRecorder({ db });
  await assert.rejects(
    handler({
      auth: { uid: "staff-a" },
      data: { instituteId: "institute-a", sessionId: "12345678-1234-1234-1234-123456789012", method: "password" },
    }),
    (error) => error.code === "permission-denied",
  );
});

test("Super Admin feed returns only the last 30 days and Dhaka today count", async () => {
  const now = Date.UTC(2026, 7, 22, 6, 0, 0);
  const oneHourAgo = now - 60 * 60 * 1000;
  const twoDaysAgo = now - 2 * 24 * 60 * 60 * 1000;
  const db = new FakeDb({
    "institutes/institute-a": { ownerUid: "owner-a" },
    "institutes/institute-a/owner_login_summary/current": { totalLoginCount: 9, lastLoginAtMs: oneHourAgo },
    "institutes/institute-a/owner_login_activity/today": { occurredAtMs: oneHourAgo, actorUid: "owner-a", method: "password" },
    "institutes/institute-a/owner_login_activity/recent": { occurredAtMs: twoDaysAgo, actorUid: "owner-a", method: "biometric" },
    "institutes/institute-a/owner_login_activity/expired": { occurredAtMs: now - 31 * 24 * 60 * 60 * 1000, actorUid: "owner-a", method: "password" },
  });
  const handler = createInstituteOwnerLoginFeedHandler({
    db,
    now: () => now,
    assertPlatformRoot: async (auth) => assert.equal(auth.uid, "root"),
  });

  const result = await handler({ auth: { uid: "root" }, data: { instituteId: "institute-a" } });
  assert.equal(result.totalLoginCount, 9);
  assert.equal(result.last30DaysCount, 2);
  assert.equal(result.todayCount, 1);
  assert.deepEqual(result.events.map((event) => event.id), ["today", "recent"]);
  assert.equal(dhakaDayStartMs(now), Date.UTC(2026, 7, 21, 18, 0, 0));
});
