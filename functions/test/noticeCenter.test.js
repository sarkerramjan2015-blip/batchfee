"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  NOTICE_ACTIONS,
  NOTICE_CATEGORIES,
  RECIPIENT_ROLES,
  SUPPORT_ITEM_STATUSES,
  SUPPORT_ITEM_TYPES,
  TUTORIAL_STATUSES,
  extractYouTubeVideoId,
  hasCredentialMaterial,
  isMissingNoticeIndexError,
  isEligibleForNotice,
  loadNoticeDocs,
  normalizeAudience,
  normalizeSupportItem,
  publicAdminNotice,
  publicNotice,
  publicSupportItem,
} = require("../src/noticeCenter");

function noticeDoc(id, publishedAtMs) {
  return { id, data: () => ({ status: "published", publishedAtMs }) };
}

test("notice centre exposes only explicit categories, recipient roles and actions", () => {
  assert.deepEqual([...NOTICE_CATEGORIES].sort(), ["billing", "feature", "important", "maintenance", "update"]);
  assert.deepEqual([...RECIPIENT_ROLES].sort(), ["owner", "staff"]);
  assert.deepEqual([...SUPPORT_ITEM_TYPES].sort(), ["complaint", "suggestion"]);
  assert.deepEqual([...SUPPORT_ITEM_STATUSES].sort(), ["in_progress", "open", "resolved"]);
  assert.deepEqual([...TUTORIAL_STATUSES].sort(), ["archived", "published"]);
  assert.ok(NOTICE_ACTIONS.has("mark_notice_state"));
  assert.ok(NOTICE_ACTIONS.has("register_notice_push_token"));
  assert.ok(NOTICE_ACTIONS.has("submit_support_item"));
  assert.ok(NOTICE_ACTIONS.has("add_support_item_note"));
  assert.ok(NOTICE_ACTIONS.has("create_tutorial"));
  assert.ok(NOTICE_ACTIONS.has("list_tutorials"));
});

test("tutorials accept only a single canonical YouTube video", () => {
  assert.equal(extractYouTubeVideoId("dQw4w9WgXcQ"), "dQw4w9WgXcQ");
  assert.equal(extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=5"), "dQw4w9WgXcQ");
  assert.equal(extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "dQw4w9WgXcQ");
  assert.equal(extractYouTubeVideoId("https://youtube.com/shorts/puZaesauAK4?si=TlEoaLu0VTN7ZjYd"), "puZaesauAK4");
  assert.throws(() => extractYouTubeVideoId("https://example.com/player"), /YouTube/i);
  assert.throws(() => extractYouTubeVideoId("https://youtube.com/playlist?list=123"), /single YouTube/i);
});

test("notice audience defaults to owners and rejects unsafe audience shapes", () => {
  assert.deepEqual(normalizeAudience({}), { roles: ["owner"], instituteIds: [] });
  assert.deepEqual(
    normalizeAudience({ audience: { roles: ["owner", "staff"], instituteIds: ["inst_a", "inst_b"] } }),
    { roles: ["owner", "staff"], instituteIds: ["inst_a", "inst_b"] },
  );
  assert.throws(
    () => normalizeAudience({ audience: { roles: ["student"] } }),
    /audience role/i,
  );
  assert.throws(
    () => normalizeAudience({ audience: { instituteIds: Array(51).fill("inst") } }),
    /audience institutes/i,
  );
});

test("eligible audience is role, institute and expiry scoped", () => {
  const now = Date.UTC(2026, 8, 10, 10, 0, 0);
  const notice = {
    status: "published",
    expiresAtMs: now + 1_000,
    audience: { roles: ["owner"], instituteIds: ["inst_a"] },
  };
  assert.equal(isEligibleForNotice(notice, { role: "owner", instituteId: "inst_a" }, now), true);
  assert.equal(isEligibleForNotice(notice, { role: "staff", instituteId: "inst_a" }, now), false);
  assert.equal(isEligibleForNotice(notice, { role: "owner", instituteId: "inst_b" }, now), false);
  assert.equal(isEligibleForNotice({ ...notice, expiresAtMs: now }, { role: "owner", instituteId: "inst_a" }, now), false);
  assert.equal(isEligibleForNotice({ ...notice, status: "archived" }, { role: "owner", instituteId: "inst_a" }, now), false);
});

test("public notice and feedback views never leak server-only fields", () => {
  const publicClientNotice = publicNotice("notice_1", {
    title: "Maintenance update", body: "Service work at 10 PM.", category: "maintenance",
    publishedAtMs: 100, updatedAtMs: 200, expiresAtMs: 300,
    audience: { roles: ["owner"], instituteIds: ["inst_a"] }, createdByUid: "root", secret: "never",
  }, true);
  assert.deepEqual(Object.keys(publicClientNotice).sort(), [
    "body", "category", "expiresAtMs", "isRead", "noticeId", "publishedAtMs", "senderName", "title", "updatedAtMs",
  ].sort());
  assert.equal(publicClientNotice.isRead, true);

  const adminNotice = publicAdminNotice("notice_1", {
    ...publicClientNotice, status: "published", audience: { roles: ["owner"], instituteIds: ["inst_a"] },
    createdByName: "Root", internalToken: "never",
  });
  assert.equal(adminNotice.createdByName, "Root");
  assert.equal(Object.hasOwn(adminNotice, "internalToken"), false);

  const item = publicSupportItem("item_1", {
    type: "complaint", title: "Payment trouble", body: "Cannot save payment.", status: "open",
    instituteId: "inst_a", instituteName: "A", createdByName: "Owner", createdAtMs: 1,
    createdByUid: "owner-a", password: "never",
  });
  assert.equal(Object.hasOwn(item, "createdByUid"), false);
  assert.equal(Object.hasOwn(item, "password"), false);
});

test("credential material is rejected from notices and feedback", () => {
  assert.equal(hasCredentialMaterial("Please reset a password"), true);
  assert.equal(hasCredentialMaterial("OTP is 123456"), true);
  assert.equal(hasCredentialMaterial("Need help with fee collection"), false);
});

test("feedback accepts a concise title and rejects incomplete submissions", () => {
  assert.deepEqual(
    normalizeSupportItem({ type: "suggestion", title: " hi ", body: " Useful feedback here. " }),
    { type: "suggestion", title: "hi", body: "Useful feedback here." },
  );
  assert.throws(
    () => normalizeSupportItem({ type: "suggestion", title: "h", body: "Useful feedback here." }),
    /at least 2/i,
  );
  assert.throws(
    () => normalizeSupportItem({ type: "complaint", title: "Bug", body: "too short" }),
    /at least 10/i,
  );
});

test("notice query recognises only missing composite-index failures", () => {
  assert.equal(isMissingNoticeIndexError({ code: 9, message: "The query requires an index." }), true);
  assert.equal(isMissingNoticeIndexError({ code: "failed-precondition", message: "Index is building." }), true);
  assert.equal(isMissingNoticeIndexError({ code: 9, message: "Another precondition failed." }), false);
  assert.equal(isMissingNoticeIndexError({ code: 14, message: "Service unavailable." }), false);
});

test("notice query falls back while the composite index is building and keeps newest first", async () => {
  const older = noticeDoc("older", 10);
  const newer = noticeDoc("newer", 20);
  let fallbackCalls = 0;
  const baseQuery = {
    orderBy() {
      return {
        limit() {
          return {
            get: async () => {
              const error = new Error("The query requires an index that is still building.");
              error.code = 9;
              throw error;
            },
          };
        },
      };
    },
    limit() {
      return {
        get: async () => {
          fallbackCalls += 1;
          return { docs: [older, newer] };
        },
      };
    },
  };
  const db = {
    collection: () => ({ where: () => baseQuery }),
  };

  const docs = await loadNoticeDocs(db, "published");
  assert.equal(fallbackCalls, 1);
  assert.deepEqual(docs.map((doc) => doc.id), ["newer", "older"]);
});

test("notice query does not hide unrelated backend failures", async () => {
  const baseQuery = {
    orderBy() {
      return {
        limit() {
          return { get: async () => { throw Object.assign(new Error("Database unavailable."), { code: 14 }); } };
        },
      };
    },
    limit() {
      throw new Error("Fallback must not run.");
    },
  };
  const db = { collection: () => ({ where: () => baseQuery }) };
  await assert.rejects(() => loadNoticeDocs(db, "published"), /Database unavailable/);
});
