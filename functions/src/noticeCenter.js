"use strict";

// Notice delivery and product feedback live behind a small callable boundary.
// Neither notices nor support conversations are safe to expose as broadly
// readable Firestore collections: recipient filtering, read markers and
// internal follow-up notes must always be server-authoritative.
const { createHash, randomUUID } = require("node:crypto");
const { FieldPath } = require("firebase-admin/firestore");
const { HttpsError } = require("firebase-functions/v2/https");

const NOTICE_ACTIONS = new Set([
  "list_my_notices",
  "mark_notice_state",
  "register_notice_push_token",
  "submit_support_item",
  "publish_notice",
  "update_notice",
  "archive_notice",
  "restore_notice",
  "list_platform_notices",
  "list_support_items",
  "get_support_item_details",
  "add_support_item_note",
  "update_support_item_status",
  "list_tutorials",
  "create_tutorial",
  "update_tutorial",
  "archive_tutorial",
  "restore_tutorial",
  "list_platform_tutorials",
]);
const NOTICE_CATEGORIES = new Set(["update", "maintenance", "billing", "feature", "important"]);
const NOTICE_STATUSES = new Set(["published", "archived"]);
const RECIPIENT_ROLES = new Set(["owner", "staff"]);
const SUPPORT_ITEM_TYPES = new Set(["suggestion", "complaint"]);
const SUPPORT_ITEM_STATUSES = new Set(["open", "in_progress", "resolved"]);
const TUTORIAL_STATUSES = new Set(["published", "archived"]);
const PAGE_SIZES = new Set([25, 50, 100]);
const MAX_NOTICE_LIST_WINDOW = 100;
const MAX_NOTICE_PUSH_TOKENS = 10_000;
const MAX_PUSH_AGE_MS = 90 * 86_400_000;

function requiredString(data, field, maxLength = 160) {
  const value = data && typeof data[field] === "string" ? data[field].trim() : "";
  if (!value || value.length > maxLength) throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  return value;
}

function optionalString(data, field, maxLength = 160) {
  if (data == null || data[field] == null || data[field] === "") return "";
  if (typeof data[field] !== "string" || data[field].trim().length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  return data[field].trim();
}

function safeMillis(value, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? Math.floor(numeric) : fallback;
}

function hashRequest(data) {
  return createHash("sha256").update(JSON.stringify(data || {})).digest("hex");
}

function validOperationId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{16,128}$/.test(value);
}

function noticePushTokenId(token) {
  // Tokens are credentials for a device endpoint. They are never used as a
  // document ID or exposed to any client response.
  return createHash("sha256").update(token).digest("hex");
}

function normalisedIdentifier(value, maxLength = 160) {
  return typeof value === "string" && value.trim().length > 0 && value.trim().length <= maxLength
    ? value.trim() : "";
}

function platformRole(user) {
  if (!user || user.status === "suspended") return "";
  if (["SuperAdmin", "superAdmin", "super_admin"].includes(user.role) && !user.platformRole) return "root";
  return typeof user.platformRole === "string" ? user.platformRole : "";
}

function isRoot(user) {
  return platformRole(user) === "root";
}

function hasCredentialMaterial(value) {
  // This is intentionally a conservative guardrail, not a credential scanner.
  // It prevents ordinary support staff from accidentally saving account secrets
  // in product-feedback or internal note history.
  return /\b(password|passcode|one[ -]?time[ -]?code|otp|access[ -]?token|auth[ -]?token|security[ -]?pin)\b/i.test(value);
}

function assertNoCredentialMaterial(value) {
  if (hasCredentialMaterial(value)) {
    throw new HttpsError(
      "invalid-argument",
      "Do not include passwords, PINs, OTPs, or tokens. Use the approved recovery flow instead."
    );
  }
}

function normalizeSupportItem(data) {
  const type = requiredString(data, "type", 24).toLowerCase();
  if (!SUPPORT_ITEM_TYPES.has(type)) throw new HttpsError("invalid-argument", "Invalid feedback type.");
  const title = requiredString(data, "title", 120);
  const body = requiredString(data, "body", 2_000);
  if (title.length < 2) throw new HttpsError("invalid-argument", "Write a short title (at least 2 characters).");
  if (body.length < 10) throw new HttpsError("invalid-argument", "Please add a little more detail (at least 10 characters).");
  assertNoCredentialMaterial(`${title}\n${body}`);
  return { type, title, body };
}

function parseList(value, field, maxItems, maxLength = 128) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > maxItems) {
    throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  }
  const unique = [...new Set(value.map((item) => normalisedIdentifier(item, maxLength)).filter(Boolean))];
  if (unique.length !== value.length) throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  return unique;
}

async function authenticatedUser(db, auth) {
  if (!auth || !auth.uid) throw new HttpsError("unauthenticated", "Sign in is required.");
  const snap = await db.collection("app_users").doc(auth.uid).get();
  const user = snap.exists ? snap.data() || {} : {};
  if (user.status === "suspended") throw new HttpsError("permission-denied", "This account is inactive.");
  return { uid: auth.uid, user };
}

async function assertRoot(db, auth) {
  const actor = await authenticatedUser(db, auth);
  if (!isRoot(actor.user)) throw new HttpsError("permission-denied", "Root platform access is required.");
  return actor;
}

function normalizedTenantRole(user) {
  const role = typeof user.role === "string" ? user.role : "";
  if (["InstituteOwner", "owner", "InstituteAdmin", "admin", "instituteAdmin", "institute_admin"].includes(role)) return "owner";
  if (role === "Staff") return "staff";
  return "";
}

async function resolveTenantRecipient(db, auth) {
  const actor = await authenticatedUser(db, auth);
  if (isRoot(actor.user)) throw new HttpsError("permission-denied", "Platform accounts do not use the institute notice center.");
  const role = normalizedTenantRole(actor.user);
  if (!role) throw new HttpsError("permission-denied", "This account cannot use the institute notice center.");

  let instituteId = normalisedIdentifier(actor.user.instituteId, 128);
  let instituteSnap = instituteId ? await db.collection("institutes").doc(instituteId).get() : null;
  if (role === "owner" && (!instituteSnap || !instituteSnap.exists)) {
    // Older owner accounts may predate app_users.instituteId. First retain the
    // inexpensive legacy document-id convention, then look up ownerUid.
    const directOwner = await db.collection("institutes").doc(actor.uid).get();
    if (directOwner.exists) {
      instituteSnap = directOwner;
      instituteId = directOwner.id;
    } else {
      const byOwner = await db.collection("institutes").where("ownerUid", "==", actor.uid).limit(2).get();
      if (byOwner.size === 1) {
        instituteSnap = byOwner.docs[0];
        instituteId = instituteSnap.id;
      }
    }
  }
  if (!instituteSnap || !instituteSnap.exists || !instituteId) {
    throw new HttpsError("failed-precondition", "The institute account is not ready yet.");
  }
  const institute = instituteSnap.data() || {};
  if (institute.isActive === false || institute.deletionState === "retained") {
    throw new HttpsError("failed-precondition", "The institute is inactive.");
  }
  if (role === "owner" && institute.ownerUid && institute.ownerUid !== actor.uid && instituteId !== actor.uid) {
    throw new HttpsError("permission-denied", "This account is not the institute owner.");
  }
  if (role === "staff") {
    const staffSnap = await db.collection("institutes").doc(instituteId).collection("staffs").doc(actor.uid).get();
    if (!staffSnap.exists || staffSnap.get("status") !== "active" || staffSnap.get("archivedAtMs") != null) {
      throw new HttpsError("permission-denied", "Active staff access is required.");
    }
  }
  return {
    uid: actor.uid,
    user: actor.user,
    role,
    instituteId,
    instituteName: typeof institute.instituteName === "string"
      ? institute.instituteName : (typeof institute.name === "string" ? institute.name : ""),
    displayName: typeof actor.user.name === "string" ? actor.user.name : "",
  };
}

function normalizeAudience(data) {
  const supplied = data && typeof data.audience === "object" && data.audience !== null ? data.audience : {};
  const roles = parseList(supplied.roles, "audience roles", 2, 24).map((role) => role.toLowerCase());
  const safeRoles = roles.length ? roles : ["owner"];
  if (safeRoles.some((role) => !RECIPIENT_ROLES.has(role))) {
    throw new HttpsError("invalid-argument", "Invalid notice audience role.");
  }
  const instituteIds = parseList(supplied.instituteIds, "audience institutes", 50, 128);
  return { roles: [...new Set(safeRoles)], instituteIds };
}

function normalizeNotice(data, now, existing = null) {
  const title = requiredString(data, "title", 120);
  const body = requiredString(data, "body", 3_000);
  const category = requiredString(data, "category", 24).toLowerCase();
  if (!NOTICE_CATEGORIES.has(category)) throw new HttpsError("invalid-argument", "Invalid notice category.");
  assertNoCredentialMaterial(`${title}\n${body}`);
  const expiryDaysRaw = data && data.expiryDays;
  const expiryDays = expiryDaysRaw == null || expiryDaysRaw === "" ? null : Number(expiryDaysRaw);
  if (expiryDays != null && (!Number.isInteger(expiryDays) || expiryDays < 0 || expiryDays > 365)) {
    throw new HttpsError("invalid-argument", "Expiry must be between 0 and 365 days.");
  }
  return {
    title,
    body,
    category,
    audience: normalizeAudience(data),
    expiresAtMs: expiryDays === 0 ? 0 : now + ((expiryDays == null ? 30 : expiryDays) * 86_400_000),
    ...(existing ? {} : { status: "published", publishedAtMs: now }),
  };
}

function isEligibleForNotice(notice, recipient, now) {
  if (!notice || notice.status !== "published") return false;
  const expiresAtMs = safeMillis(notice.expiresAtMs, 0);
  if (expiresAtMs > 0 && expiresAtMs <= now) return false;
  const audience = notice.audience && typeof notice.audience === "object" ? notice.audience : {};
  const roles = Array.isArray(audience.roles) ? audience.roles : ["owner"];
  if (!roles.includes(recipient.role)) return false;
  const instituteIds = Array.isArray(audience.instituteIds) ? audience.instituteIds : [];
  return instituteIds.length === 0 || instituteIds.includes(recipient.instituteId);
}

function publicNotice(id, notice, isRead = false) {
  return {
    noticeId: id,
    title: typeof notice.title === "string" ? notice.title : "",
    body: typeof notice.body === "string" ? notice.body : "",
    category: typeof notice.category === "string" ? notice.category : "update",
    senderName: "BatchFee Team",
    publishedAtMs: safeMillis(notice.publishedAtMs),
    updatedAtMs: safeMillis(notice.updatedAtMs || notice.publishedAtMs),
    expiresAtMs: safeMillis(notice.expiresAtMs),
    isRead,
  };
}

function publicAdminNotice(id, notice) {
  return {
    ...publicNotice(id, notice, false),
    status: NOTICE_STATUSES.has(notice.status) ? notice.status : "archived",
    audience: {
      roles: Array.isArray(notice.audience?.roles) ? notice.audience.roles.filter((role) => RECIPIENT_ROLES.has(role)) : [],
      instituteIds: Array.isArray(notice.audience?.instituteIds) ? notice.audience.instituteIds.filter((id) => typeof id === "string") : [],
    },
    createdByName: typeof notice.createdByName === "string" ? notice.createdByName : "BatchFee Team",
  };
}

function stateId(uid, noticeId) {
  return `${uid}_${noticeId}`;
}

async function listMyNotices({ db, request }) {
  const recipient = await resolveTenantRecipient(db, request.auth);
  const tab = optionalString(request.data, "tab", 16).toLowerCase() || "all";
  if (!["all", "unread", "archived"].includes(tab)) throw new HttpsError("invalid-argument", "Invalid notice tab.");
  const requestedSize = Number(request.data?.pageSize || 50);
  if (!PAGE_SIZES.has(requestedSize)) throw new HttpsError("invalid-argument", "Invalid notice page size.");
  const now = Date.now();
  const status = tab === "archived" ? "archived" : "published";
  const snapshot = await db.collection("platform_notices")
    .where("status", "==", status)
    .orderBy("publishedAtMs", "desc")
    .limit(MAX_NOTICE_LIST_WINDOW)
    .get();
  const eligible = snapshot.docs.filter((doc) => {
    const data = doc.data();
    return tab === "archived"
      ? data && data.audience && isEligibleForNotice({ ...data, status: "published", expiresAtMs: 0 }, recipient, now)
      : isEligibleForNotice(data, recipient, now);
  });
  const states = eligible.length
    ? await db.getAll(...eligible.map((doc) => db.collection("platform_notice_states").doc(stateId(recipient.uid, doc.id))))
    : [];
  const stateByNotice = new Map(states.filter((doc) => doc.exists).map((doc) => [doc.get("noticeId"), doc.data() || {}]));
  const notices = eligible
    .map((doc) => publicNotice(doc.id, doc.data() || {}, stateByNotice.get(doc.id)?.isRead === true))
    .filter((notice) => tab !== "unread" || !notice.isRead)
    .slice(0, requestedSize);
  const unreadCount = eligible.reduce((count, doc) => count + (stateByNotice.get(doc.id)?.isRead === true ? 0 : 1), 0);
  return {
    notices,
    unreadCount,
    hasMore: eligible.length > notices.length,
    checkedAtMs: now,
  };
}

async function markNoticeState({ db, request, operationId, requestHash, now }) {
  const recipient = await resolveTenantRecipient(db, request.auth);
  const noticeId = requiredString(request.data, "noticeId", 128);
  if (typeof request.data?.isRead !== "boolean") throw new HttpsError("invalid-argument", "Invalid read state.");
  const isRead = request.data.isRead;
  const noticeRef = db.collection("platform_notices").doc(noticeId);
  const stateRef = db.collection("platform_notice_states").doc(stateId(recipient.uid, noticeId));
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  return db.runTransaction(async (transaction) => {
    const [operationSnap, noticeSnap] = await Promise.all([transaction.get(operationRef), transaction.get(noticeRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== recipient.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return operationSnap.get("result");
    }
    if (!noticeSnap.exists || !isEligibleForNotice(noticeSnap.data(), recipient, now)) {
      throw new HttpsError("not-found", "This notice is not available to this account.");
    }
    const result = { noticeId, isRead, updatedAtMs: now };
    transaction.set(stateRef, {
      userId: recipient.uid,
      instituteId: recipient.instituteId,
      noticeId,
      isRead,
      readAtMs: isRead ? now : 0,
      updatedAtMs: now,
    }, { merge: true });
    transaction.create(operationRef, { actorUid: recipient.uid, requestHash, action: "mark_notice_state", result, createdAtMs: now });
    return result;
  });
}

/** Registers one authenticated tenant device for server-owned notice pushes. */
async function registerNoticePushToken({ db, request, now }) {
  const recipient = await resolveTenantRecipient(db, request.auth);
  const token = requiredString(request.data, "token", 4_096);
  if (token.length < 20) throw new HttpsError("invalid-argument", "Invalid notification token.");
  await db.collection("notice_push_tokens").doc(noticePushTokenId(token)).set({
    token,
    userId: recipient.uid,
    instituteId: recipient.instituteId,
    role: recipient.role,
    updatedAtMs: now,
  }, { merge: true });
  return { registered: true };
}

function invalidPushToken(error) {
  const code = error && error.code;
  return code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token";
}

/**
 * Push is best-effort and never changes the published notice transaction.
 * In-app notice reads remain the durable source of truth if FCM is disabled,
 * a device is offline, or the user has denied notification permission.
 */
async function sendNoticePush({ db, messaging, noticeId, notice, now }) {
  if (!messaging || typeof messaging.sendEachForMulticast !== "function") return { targeted: 0, sent: 0 };
  const roles = Array.isArray(notice.audience?.roles) ? notice.audience.roles.filter((role) => RECIPIENT_ROLES.has(role)) : [];
  if (!roles.length) return { targeted: 0, sent: 0 };
  const snapshot = await db.collection("notice_push_tokens")
    .where("role", "in", roles)
    .limit(MAX_NOTICE_PUSH_TOKENS)
    .get();
  const restrictedInstitutes = Array.isArray(notice.audience?.instituteIds) ? notice.audience.instituteIds : [];
  // FCM's complete payload has a 4 KB limit. Bengali text commonly uses three
  // UTF-8 bytes per character and is included in both notification and data,
  // so keep this preview intentionally small. The full notice remains in
  // Firestore and opens in the app.
  const pushBody = notice.body.slice(0, 300);
  const targets = snapshot.docs.filter((doc) => {
    const data = doc.data() || {};
    const token = typeof data.token === "string" ? data.token : "";
    const fresh = safeMillis(data.updatedAtMs) >= now - MAX_PUSH_AGE_MS;
    return token.length >= 20 && fresh && (!restrictedInstitutes.length || restrictedInstitutes.includes(data.instituteId));
  });
  let sent = 0;
  const staleRefs = [];
  for (let index = 0; index < targets.length; index += 500) {
    const batch = targets.slice(index, index + 500);
    const response = await messaging.sendEachForMulticast({
      tokens: batch.map((doc) => doc.get("token")),
      notification: { title: notice.title, body: pushBody },
      data: { type: "platform_notice", noticeId, title: notice.title, body: pushBody },
      android: { priority: "high", notification: { channelId: "batchfee_notices", sound: "default" } },
    });
    sent += response.successCount || 0;
    response.responses.forEach((result, responseIndex) => {
      if (!result.success && invalidPushToken(result.error)) staleRefs.push(batch[responseIndex].ref);
    });
  }
  await Promise.all(staleRefs.map((ref) => ref.delete().catch(() => {})));
  return { targeted: targets.length, sent };
}

function publicSupportItem(id, item) {
  return {
    itemId: id,
    type: SUPPORT_ITEM_TYPES.has(item.type) ? item.type : "suggestion",
    title: typeof item.title === "string" ? item.title : "",
    body: typeof item.body === "string" ? item.body : "",
    status: SUPPORT_ITEM_STATUSES.has(item.status) ? item.status : "open",
    instituteId: typeof item.instituteId === "string" ? item.instituteId : "",
    instituteName: typeof item.instituteName === "string" ? item.instituteName : "",
    createdByName: typeof item.createdByName === "string" ? item.createdByName : "",
    createdAtMs: safeMillis(item.createdAtMs),
    updatedAtMs: safeMillis(item.updatedAtMs || item.createdAtMs),
  };
}

function publicSupportItemNote(id, note) {
  return {
    noteId: id,
    body: typeof note.body === "string" ? note.body : "",
    status: SUPPORT_ITEM_STATUSES.has(note.status) ? note.status : "open",
    createdByName: typeof note.createdByName === "string" ? note.createdByName : "BatchFee Team",
    createdAtMs: safeMillis(note.createdAtMs),
  };
}

/**
 * Only persist the canonical video ID. It prevents a platform administrator
 * from accidentally embedding an arbitrary website inside the Android app.
 */
function parseYouTubeVideo(value) {
  const candidate = normalisedIdentifier(value, 2_000);
  if (!candidate) throw new HttpsError("invalid-argument", "A YouTube video link is required.");
  if (/^[A-Za-z0-9_-]{11}$/.test(candidate)) {
    return { videoId: candidate, videoLayout: "landscape" };
  }
  let url;
  try {
    url = new URL(candidate);
  } catch (_) {
    throw new HttpsError("invalid-argument", "Use a valid YouTube video link.");
  }
  const host = url.hostname.toLowerCase().replace(/^www\./, "");
  let id = "";
  let videoLayout = "landscape";
  if (host === "youtu.be") {
    id = url.pathname.split("/").filter(Boolean)[0] || "";
  } else if (["youtube.com", "m.youtube.com", "youtube-nocookie.com"].includes(host)) {
    const parts = url.pathname.split("/").filter(Boolean);
    if (parts[0] === "watch") id = url.searchParams.get("v") || "";
    else if (["embed", "shorts", "live"].includes(parts[0])) {
      id = parts[1] || "";
      if (parts[0] === "shorts") videoLayout = "portrait";
    }
  }
  if (!/^[A-Za-z0-9_-]{11}$/.test(id)) {
    throw new HttpsError("invalid-argument", "Use a single YouTube video link, not a playlist or channel link.");
  }
  return { videoId: id, videoLayout };
}

function extractYouTubeVideoId(value) {
  return parseYouTubeVideo(value).videoId;
}

function normalizedTutorial(data, now, existing = null) {
  const title = requiredString(data, "title", 120);
  const description = optionalString(data, "description", 1_000);
  const category = optionalString(data, "category", 60) || "Getting started";
  const displayOrder = Number(data?.displayOrder ?? existing?.displayOrder ?? 0);
  if (!Number.isInteger(displayOrder) || displayOrder < 0 || displayOrder > 10_000) {
    throw new HttpsError("invalid-argument", "Tutorial display order must be between 0 and 10000.");
  }
  assertNoCredentialMaterial(`${title}\n${description}\n${category}`);
  const video = parseYouTubeVideo(data?.youtubeUrl);
  return {
    title,
    description,
    category,
    displayOrder,
    youtubeVideoId: video.videoId,
    // A YouTube Shorts URL is the reliable signal for a 9:16 player. Normal
    // watch/embed URLs remain 16:9, while existing tutorials default safely to
    // that original landscape layout.
    videoLayout: video.videoLayout,
    ...(existing ? {} : { status: "published", publishedAtMs: now }),
  };
}

function publicTutorial(id, tutorial) {
  return {
    tutorialId: id,
    title: typeof tutorial.title === "string" ? tutorial.title : "",
    description: typeof tutorial.description === "string" ? tutorial.description : "",
    category: typeof tutorial.category === "string" ? tutorial.category : "Getting started",
    displayOrder: safeMillis(tutorial.displayOrder),
    youtubeVideoId: typeof tutorial.youtubeVideoId === "string" ? tutorial.youtubeVideoId : "",
    videoLayout: tutorial.videoLayout === "portrait" ? "portrait" : "landscape",
    status: TUTORIAL_STATUSES.has(tutorial.status) ? tutorial.status : "archived",
    publishedAtMs: safeMillis(tutorial.publishedAtMs),
    updatedAtMs: safeMillis(tutorial.updatedAtMs || tutorial.publishedAtMs),
  };
}

async function listTutorials({ db, request }) {
  // Resolve the tenant explicitly: guides are a platform resource but are only
  // available to a signed-in, active BatchFee institute account.
  await resolveTenantRecipient(db, request.auth);
  const snapshot = await db.collection("platform_tutorials")
    .orderBy("updatedAtMs", "desc")
    .limit(MAX_NOTICE_LIST_WINDOW)
    .get();
  const tutorials = snapshot.docs
    .map((doc) => publicTutorial(doc.id, doc.data() || {}))
    .filter((tutorial) => tutorial.status === "published" && tutorial.youtubeVideoId)
    .sort((left, right) => left.displayOrder - right.displayOrder || right.updatedAtMs - left.updatedAtMs);
  return { tutorials, checkedAtMs: Date.now() };
}

async function createTutorial({ db, request, actor, operationId, requestHash, now }) {
  const tutorial = normalizedTutorial(request.data, now);
  const tutorialId = randomUUID();
  const tutorialRef = db.collection("platform_tutorials").doc(tutorialId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const result = { tutorialId, status: "published", createdAtMs: now };
  await db.runTransaction(async (transaction) => {
    const operationSnap = await transaction.get(operationRef);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return;
    }
    transaction.create(tutorialRef, {
      tutorialId,
      ...tutorial,
      createdByUid: actor.uid,
      createdAtMs: now,
      updatedAtMs: now,
      schemaVersion: 1,
    });
    transaction.create(db.collection("platform_audit").doc(`tutorial_${operationId}`), {
      operationId, action: "tutorial_created", actorUid: actor.uid, createdAtMs: now,
      details: { tutorialId, displayOrder: tutorial.displayOrder },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: "create_tutorial", result, createdAtMs: now });
  });
  return result;
}

async function updateTutorial({ db, request, actor, operationId, requestHash, now }) {
  const tutorialId = requiredString(request.data, "tutorialId", 128);
  const tutorialRef = db.collection("platform_tutorials").doc(tutorialId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  let result = null;
  await db.runTransaction(async (transaction) => {
    const [operationSnap, tutorialSnap] = await Promise.all([transaction.get(operationRef), transaction.get(tutorialRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      result = operationSnap.get("result");
      return;
    }
    if (!tutorialSnap.exists) throw new HttpsError("not-found", "Tutorial not found.");
    const tutorial = normalizedTutorial(request.data, now, tutorialSnap.data() || {});
    result = { tutorialId, status: tutorialSnap.get("status") || "published", updatedAtMs: now };
    transaction.update(tutorialRef, { ...tutorial, updatedAtMs: now });
    transaction.create(db.collection("platform_audit").doc(`tutorial_${operationId}`), {
      operationId, action: "tutorial_updated", actorUid: actor.uid, createdAtMs: now,
      details: { tutorialId, displayOrder: tutorial.displayOrder },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: "update_tutorial", result, createdAtMs: now });
  });
  return result;
}

async function archiveOrRestoreTutorial({ db, request, actor, operationId, requestHash, now, restore }) {
  const tutorialId = requiredString(request.data, "tutorialId", 128);
  const tutorialRef = db.collection("platform_tutorials").doc(tutorialId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const nextStatus = restore ? "published" : "archived";
  let result = null;
  await db.runTransaction(async (transaction) => {
    const [operationSnap, tutorialSnap] = await Promise.all([transaction.get(operationRef), transaction.get(tutorialRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      result = operationSnap.get("result");
      return;
    }
    if (!tutorialSnap.exists) throw new HttpsError("not-found", "Tutorial not found.");
    if (tutorialSnap.get("status") === nextStatus) {
      throw new HttpsError("failed-precondition", `Tutorial is already ${nextStatus}.`);
    }
    result = { tutorialId, status: nextStatus, updatedAtMs: now };
    transaction.update(tutorialRef, {
      status: nextStatus,
      updatedAtMs: now,
      ...(restore ? { restoredAtMs: now } : { archivedAtMs: now }),
    });
    transaction.create(db.collection("platform_audit").doc(`tutorial_${operationId}`), {
      operationId, action: restore ? "tutorial_restored" : "tutorial_archived", actorUid: actor.uid, createdAtMs: now,
      details: { tutorialId },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: restore ? "restore_tutorial" : "archive_tutorial", result, createdAtMs: now });
  });
  return result;
}

async function listPlatformTutorials({ db, request }) {
  const snapshot = await db.collection("platform_tutorials")
    .orderBy("updatedAtMs", "desc")
    .limit(MAX_NOTICE_LIST_WINDOW)
    .get();
  const tutorials = snapshot.docs
    .map((doc) => publicTutorial(doc.id, doc.data() || {}))
    .sort((left, right) => left.displayOrder - right.displayOrder || right.updatedAtMs - left.updatedAtMs);
  return { tutorials };
}

async function submitSupportItem({ db, request, operationId, requestHash, now }) {
  const recipient = await resolveTenantRecipient(db, request.auth);
  if (recipient.role !== "owner") throw new HttpsError("permission-denied", "Only an institute owner can submit product feedback.");
  const { type, title, body } = normalizeSupportItem(request.data);
  const itemId = randomUUID();
  const itemRef = db.collection("platform_support_items").doc(itemId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const result = { itemId, type, status: "open", createdAtMs: now };
  await db.runTransaction(async (transaction) => {
    const operationSnap = await transaction.get(operationRef);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== recipient.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return;
    }
    transaction.create(itemRef, {
      itemId,
      type,
      title,
      body,
      status: "open",
      instituteId: recipient.instituteId,
      instituteName: recipient.instituteName,
      createdByUid: recipient.uid,
      createdByName: recipient.displayName,
      createdAtMs: now,
      updatedAtMs: now,
    });
    transaction.create(db.collection("platform_audit").doc(`support_${operationId}`), {
      operationId,
      action: "support_item_submitted",
      actorUid: recipient.uid,
      instituteId: recipient.instituteId,
      createdAtMs: now,
      details: { type, itemId },
    });
    transaction.create(operationRef, { actorUid: recipient.uid, requestHash, action: "submit_support_item", result, createdAtMs: now });
  });
  return result;
}

async function publishNotice({ db, request, actor, operationId, requestHash, now, messaging }) {
  const notice = normalizeNotice(request.data, now);
  const noticeId = randomUUID();
  const noticeRef = db.collection("platform_notices").doc(noticeId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const result = { noticeId, status: "published", publishedAtMs: now };
  await db.runTransaction(async (transaction) => {
    const operationSnap = await transaction.get(operationRef);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return;
    }
    transaction.create(noticeRef, {
      noticeId,
      ...notice,
      createdByUid: actor.uid,
      createdByName: typeof actor.user.name === "string" ? actor.user.name : "BatchFee Team",
      createdAtMs: now,
      updatedAtMs: now,
      schemaVersion: 1,
    });
    transaction.create(db.collection("platform_audit").doc(`notice_${operationId}`), {
      operationId,
      action: "notice_published",
      actorUid: actor.uid,
      createdAtMs: now,
      details: { noticeId, category: notice.category, audience: notice.audience, expiresAtMs: notice.expiresAtMs },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: "publish_notice", result, createdAtMs: now });
  });
  await sendNoticePush({ db, messaging, noticeId, notice, now }).catch(() => {});
  return result;
}

async function updateNotice({ db, request, actor, operationId, requestHash, now }) {
  const noticeId = requiredString(request.data, "noticeId", 128);
  const noticeRef = db.collection("platform_notices").doc(noticeId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  let result = null;
  await db.runTransaction(async (transaction) => {
    const [operationSnap, noticeSnap] = await Promise.all([transaction.get(operationRef), transaction.get(noticeRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      result = operationSnap.get("result");
      return;
    }
    if (!noticeSnap.exists || noticeSnap.get("status") !== "published") {
      throw new HttpsError("failed-precondition", "Only a published notice can be edited.");
    }
    const next = normalizeNotice(request.data, now, noticeSnap.data());
    result = { noticeId, status: "published", updatedAtMs: now };
    transaction.update(noticeRef, { ...next, updatedAtMs: now });
    transaction.create(db.collection("platform_audit").doc(`notice_${operationId}`), {
      operationId, action: "notice_updated", actorUid: actor.uid, createdAtMs: now,
      details: { noticeId, category: next.category, audience: next.audience, expiresAtMs: next.expiresAtMs },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: "update_notice", result, createdAtMs: now });
  });
  return result;
}

async function archiveOrRestoreNotice({ db, request, actor, operationId, requestHash, now, restore }) {
  const noticeId = requiredString(request.data, "noticeId", 128);
  const noticeRef = db.collection("platform_notices").doc(noticeId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const nextStatus = restore ? "published" : "archived";
  let result = null;
  await db.runTransaction(async (transaction) => {
    const [operationSnap, noticeSnap] = await Promise.all([transaction.get(operationRef), transaction.get(noticeRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      result = operationSnap.get("result");
      return;
    }
    if (!noticeSnap.exists) throw new HttpsError("not-found", "Notice not found.");
    if (noticeSnap.get("status") === nextStatus) {
      throw new HttpsError("failed-precondition", `Notice is already ${nextStatus}.`);
    }
    result = { noticeId, status: nextStatus, updatedAtMs: now };
    transaction.update(noticeRef, { status: nextStatus, updatedAtMs: now, ...(restore ? { restoredAtMs: now } : { archivedAtMs: now }) });
    transaction.create(db.collection("platform_audit").doc(`notice_${operationId}`), {
      operationId, action: restore ? "notice_restored" : "notice_archived", actorUid: actor.uid, createdAtMs: now,
      details: { noticeId },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: restore ? "restore_notice" : "archive_notice", result, createdAtMs: now });
  });
  return result;
}

async function listPlatformNotices({ db, request }) {
  const pageSize = Number(request.data?.pageSize || 50);
  if (!PAGE_SIZES.has(pageSize)) throw new HttpsError("invalid-argument", "Invalid notice page size.");
  const snapshot = await db.collection("platform_notices")
    .orderBy("publishedAtMs", "desc")
    .limit(pageSize)
    .get();
  return { notices: snapshot.docs.map((doc) => publicAdminNotice(doc.id, doc.data() || {})), hasMore: snapshot.size === pageSize };
}

async function listSupportItems({ db, request }) {
  const pageSize = Number(request.data?.pageSize || 50);
  if (!PAGE_SIZES.has(pageSize)) throw new HttpsError("invalid-argument", "Invalid support-item page size.");
  const status = optionalString(request.data, "status", 24).toLowerCase();
  if (status && !SUPPORT_ITEM_STATUSES.has(status)) throw new HttpsError("invalid-argument", "Invalid support-item status.");
  let query = db.collection("platform_support_items");
  if (status) query = query.where("status", "==", status);
  const snapshot = await query.orderBy("updatedAtMs", "desc").limit(pageSize).get();
  return { items: snapshot.docs.map((doc) => publicSupportItem(doc.id, doc.data() || {})), hasMore: snapshot.size === pageSize };
}

async function getSupportItemDetails({ db, request }) {
  const itemId = requiredString(request.data, "itemId", 128);
  const itemRef = db.collection("platform_support_items").doc(itemId);
  const [itemSnap, notesSnap] = await Promise.all([
    itemRef.get(),
    itemRef.collection("internal_notes").orderBy("createdAtMs", "desc").limit(100).get(),
  ]);
  if (!itemSnap.exists) throw new HttpsError("not-found", "Support item not found.");
  return {
    item: publicSupportItem(itemSnap.id, itemSnap.data() || {}),
    notes: notesSnap.docs.map((doc) => publicSupportItemNote(doc.id, doc.data() || {})),
  };
}

async function addSupportItemNote({ db, request, actor, operationId, requestHash, now }) {
  const itemId = requiredString(request.data, "itemId", 128);
  const body = requiredString(request.data, "body", 2_000);
  const status = requiredString(request.data, "status", 24).toLowerCase();
  if (!SUPPORT_ITEM_STATUSES.has(status)) throw new HttpsError("invalid-argument", "Invalid support-item status.");
  assertNoCredentialMaterial(body);
  const itemRef = db.collection("platform_support_items").doc(itemId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const noteId = randomUUID();
  const result = { itemId, noteId, status, createdAtMs: now };
  await db.runTransaction(async (transaction) => {
    const [operationSnap, itemSnap] = await Promise.all([transaction.get(operationRef), transaction.get(itemRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return;
    }
    if (!itemSnap.exists) throw new HttpsError("not-found", "Support item not found.");
    transaction.create(itemRef.collection("internal_notes").doc(noteId), {
      noteId, body, status, createdByUid: actor.uid,
      createdByName: typeof actor.user.name === "string" ? actor.user.name : "BatchFee Team",
      createdAtMs: now,
    });
    transaction.update(itemRef, { status, updatedAtMs: now, lastInternalNoteAtMs: now });
    transaction.create(db.collection("platform_audit").doc(`support_${operationId}`), {
      operationId, action: "support_item_noted", actorUid: actor.uid, createdAtMs: now,
      details: { itemId, status, noteId },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: "add_support_item_note", result, createdAtMs: now });
  });
  return result;
}

async function updateSupportItemStatus({ db, request, actor, operationId, requestHash, now }) {
  const itemId = requiredString(request.data, "itemId", 128);
  const status = requiredString(request.data, "status", 24).toLowerCase();
  if (!SUPPORT_ITEM_STATUSES.has(status)) throw new HttpsError("invalid-argument", "Invalid support-item status.");
  const itemRef = db.collection("platform_support_items").doc(itemId);
  const operationRef = db.collection("notice_center_operations").doc(operationId);
  const result = { itemId, status, updatedAtMs: now };
  await db.runTransaction(async (transaction) => {
    const [operationSnap, itemSnap] = await Promise.all([transaction.get(operationRef), transaction.get(itemRef)]);
    if (operationSnap.exists) {
      if (operationSnap.get("requestHash") !== requestHash || operationSnap.get("actorUid") !== actor.uid) {
        throw new HttpsError("already-exists", "Operation ID was already used for another request.");
      }
      return;
    }
    if (!itemSnap.exists) throw new HttpsError("not-found", "Support item not found.");
    transaction.update(itemRef, { status, updatedAtMs: now });
    transaction.create(db.collection("platform_audit").doc(`support_${operationId}`), {
      operationId, action: "support_item_status_updated", actorUid: actor.uid, createdAtMs: now,
      details: { itemId, status },
    });
    transaction.create(operationRef, { actorUid: actor.uid, requestHash, action: "update_support_item_status", result, createdAtMs: now });
  });
  return result;
}

function createNoticeCenterHandler({ db, messaging = null }) {
  return async (request) => {
    const action = requiredString(request.data, "action", 64);
    const operationId = requiredString(request.data, "operationId", 128);
    if (!NOTICE_ACTIONS.has(action) || !validOperationId(operationId)) {
      throw new HttpsError("invalid-argument", "Invalid notice-center operation.");
    }
    const now = Date.now();
    const requestHash = hashRequest(request.data);
    if (["list_my_notices", "mark_notice_state", "register_notice_push_token", "submit_support_item", "list_tutorials"].includes(action)) {
      if (action === "list_my_notices") return listMyNotices({ db, request });
      if (action === "mark_notice_state") return markNoticeState({ db, request, operationId, requestHash, now });
      if (action === "register_notice_push_token") return registerNoticePushToken({ db, request, now });
      if (action === "list_tutorials") return listTutorials({ db, request });
      return submitSupportItem({ db, request, operationId, requestHash, now });
    }
    const actor = await assertRoot(db, request.auth);
    if (action === "publish_notice") return publishNotice({ db, request, actor, operationId, requestHash, now, messaging });
    if (action === "update_notice") return updateNotice({ db, request, actor, operationId, requestHash, now });
    if (action === "archive_notice") return archiveOrRestoreNotice({ db, request, actor, operationId, requestHash, now, restore: false });
    if (action === "restore_notice") return archiveOrRestoreNotice({ db, request, actor, operationId, requestHash, now, restore: true });
    if (action === "list_platform_notices") return listPlatformNotices({ db, request });
    if (action === "list_support_items") return listSupportItems({ db, request });
    if (action === "get_support_item_details") return getSupportItemDetails({ db, request });
    if (action === "add_support_item_note") return addSupportItemNote({ db, request, actor, operationId, requestHash, now });
    if (action === "create_tutorial") return createTutorial({ db, request, actor, operationId, requestHash, now });
    if (action === "update_tutorial") return updateTutorial({ db, request, actor, operationId, requestHash, now });
    if (action === "archive_tutorial") return archiveOrRestoreTutorial({ db, request, actor, operationId, requestHash, now, restore: false });
    if (action === "restore_tutorial") return archiveOrRestoreTutorial({ db, request, actor, operationId, requestHash, now, restore: true });
    if (action === "list_platform_tutorials") return listPlatformTutorials({ db, request });
    return updateSupportItemStatus({ db, request, actor, operationId, requestHash, now });
  };
}

module.exports = {
  NOTICE_ACTIONS,
  NOTICE_CATEGORIES,
  RECIPIENT_ROLES,
  SUPPORT_ITEM_TYPES,
  SUPPORT_ITEM_STATUSES,
  TUTORIAL_STATUSES,
  createNoticeCenterHandler,
  extractYouTubeVideoId,
  hasCredentialMaterial,
  isEligibleForNotice,
  normalizeAudience,
  normalizeSupportItem,
  publicAdminNotice,
  publicNotice,
  publicSupportItem,
  publicTutorial,
};
