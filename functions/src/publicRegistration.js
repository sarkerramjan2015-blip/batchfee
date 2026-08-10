"use strict";

const { randomUUID } = require("node:crypto");
const { canonicalRegistrationPayload, stableHash } = require("./publicRegistrationCore");

const RATE_WINDOW_MS = 60 * 60 * 1000;
const MAX_SUBMISSIONS_PER_IP = 4;
const MAX_SUBMISSIONS_PER_PROFILE = 120;
const DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1000;

function clientIp(request) {
  const forwarded = request.get("x-forwarded-for");
  return (forwarded ? forwarded.split(",")[0] : request.ip || "unknown").trim().slice(0, 128);
}

function sendJson(response, status, body) {
  response.status(status).set("Cache-Control", "no-store").json(body);
}

function configuredSecret(secretParameter) {
  if (process.env.FUNCTIONS_EMULATOR === "true") return "local-registration-rate-secret-for-emulator-only";
  const value = secretParameter.value();
  if (typeof value !== "string" || value.length < 24) {
    throw new Error("Registration protection is not configured.");
  }
  return value;
}

function createPublicRegistrationHandler({ db, rateLimitSecret }) {
  return async (request, response) => {
    if (request.method !== "POST") {
      sendJson(response, 405, { error: "method_not_allowed" });
      return;
    }
    if (!request.is("application/json")) {
      sendJson(response, 415, { error: "json_required" });
      return;
    }
    const body = request.body && typeof request.body === "object" ? request.body : {};
    if (typeof body.website === "string" && body.website.trim()) {
      sendJson(response, 400, { error: "invalid_submission" });
      return;
    }
    const now = Date.now();
    const startedAtMs = Number(body.startedAtMs);
    if (!Number.isSafeInteger(startedAtMs) || startedAtMs > now || now - startedAtMs < 2500 ||
        now - startedAtMs > 2 * 60 * 60 * 1000) {
      sendJson(response, 400, { error: "invalid_submission" });
      return;
    }

    let registration;
    try {
      registration = canonicalRegistrationPayload(body);
    } catch (error) {
      sendJson(response, 400, { error: "validation_failed", message: error.message });
      return;
    }

    let secret;
    try {
      secret = configuredSecret(rateLimitSecret);
    } catch (_) {
      sendJson(response, 503, { error: "temporarily_unavailable" });
      return;
    }
    const profileRef = db.collection("public_registration_profiles").doc(registration.slug);
    const ipKey = stableHash(secret, `ip:${registration.slug}:${clientIp(request)}`);
    const phoneKey = stableHash(secret, `phone:${registration.slug}:${registration.phone}`);
    const ipRateRef = db.collection("public_registration_rate_limits").doc(`ip_${ipKey}`);
    const profileRateRef = db.collection("public_registration_rate_limits").doc(
      `profile_${stableHash(secret, `profile:${registration.slug}`)}`,
    );
    const duplicateRef = db.collection("public_registration_dedup").doc(phoneKey);
    const requestId = `reg_${randomUUID().replaceAll("-", "")}`;

    try {
      const result = await db.runTransaction(async (transaction) => {
        const [profileSnap, ipRateSnap, profileRateSnap, duplicateSnap] = await Promise.all([
          transaction.get(profileRef),
          transaction.get(ipRateRef),
          transaction.get(profileRateRef),
          transaction.get(duplicateRef),
        ]);
        if (!profileSnap.exists || profileSnap.get("slug") !== registration.slug) {
          return { kind: "not_found" };
        }
        const profile = profileSnap.data();
        const instituteId = typeof profile.instituteId === "string" ? profile.instituteId : "";
        if (!instituteId || instituteId.length > 128) return { kind: "not_found" };

        const updatedRate = (snapshot, max) => {
          const current = snapshot.exists ? snapshot.data() : {};
          const windowStartedAtMs = Number(current.windowStartedAtMs || 0);
          const inWindow = now - windowStartedAtMs < RATE_WINDOW_MS;
          const count = inWindow ? Number(current.count || 0) : 0;
          if (!Number.isSafeInteger(count) || count >= max) return null;
          return { count: count + 1, windowStartedAtMs: inWindow ? windowStartedAtMs : now };
        };
        const ipRate = updatedRate(ipRateSnap, MAX_SUBMISSIONS_PER_IP);
        const profileRate = updatedRate(profileRateSnap, MAX_SUBMISSIONS_PER_PROFILE);
        if (!ipRate || !profileRate) return { kind: "rate_limited" };
        if (duplicateSnap.exists && now - Number(duplicateSnap.get("submittedAtMs") || 0) < DUPLICATE_WINDOW_MS) {
          return { kind: "duplicate" };
        }

        const pendingRef = db.collection("registrations").doc(instituteId)
          .collection("pending").doc(requestId);
        transaction.create(pendingRef, {
          requestId,
          instituteId,
          fullName: registration.fullName,
          phone: registration.phone,
          guardianName: registration.guardianName,
          whatsappNumber: registration.whatsappNumber,
          gender: registration.gender,
          dateOfBirthMs: registration.dateOfBirthMs,
          schoolName: registration.schoolName,
          className: registration.className,
          address: registration.address,
          submittedAt: now,
          status: "pending",
          source: "public_web",
          publicProfileSlug: registration.slug,
        });
        transaction.set(ipRateRef, { ...ipRate, updatedAtMs: now });
        transaction.set(profileRateRef, { ...profileRate, updatedAtMs: now });
        transaction.set(duplicateRef, {
          submittedAtMs: now,
          instituteId,
          profileSlug: registration.slug,
        });
        return { kind: "accepted", requestId };
      });
      if (result.kind === "accepted") {
        sendJson(response, 201, { status: "accepted", requestId: result.requestId });
      } else if (result.kind === "not_found") {
        sendJson(response, 404, { error: "registration_link_not_found" });
      } else if (result.kind === "duplicate") {
        sendJson(response, 409, { error: "already_submitted" });
      } else {
        sendJson(response, 429, { error: "try_again_later" });
      }
    } catch (_) {
      sendJson(response, 503, { error: "temporarily_unavailable" });
    }
  };
}

module.exports = { createPublicRegistrationHandler };
