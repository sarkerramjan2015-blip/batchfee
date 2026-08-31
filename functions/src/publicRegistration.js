"use strict";

const { randomUUID } = require("node:crypto");
const { canonicalRegistrationPayload, stableHash } = require("./publicRegistrationCore");
const {
  canonicalRegistrationPhoto,
  uploadPendingRegistrationPhoto,
  cleanupPendingRegistrationPhoto,
} = require("./registrationPhoto");
const { hasCurrentSubscription } = require("./subscriptionPolicy");

const RATE_WINDOW_MS = 60 * 60 * 1000;
// A whole coaching centre can share one Wi-Fi/public mobile IP. Keep a
// meaningful abuse ceiling without blocking a normal class after four forms.
const MAX_SUBMISSIONS_PER_IP = 30;
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

function createPublicRegistrationHandler({ db, bucket, rateLimitSecret }) {
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
    let studentPhoto;
    try {
      registration = canonicalRegistrationPayload(body);
      studentPhoto = canonicalRegistrationPhoto(body.studentPhoto);
    } catch (error) {
      console.warn("Public registration validation failed", {
        reason: error instanceof Error ? error.message : "Unknown validation error",
      });
      sendJson(response, 400, { error: "validation_failed", message: error.message });
      return;
    }

    let secret;
    try {
      secret = configuredSecret(rateLimitSecret);
    } catch (error) {
      console.error("Public registration submission failed", {
        reason: error instanceof Error ? error.message : "Unknown error",
      });
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
    let temporaryPhoto = null;
    let photoInstituteId = null;
    let accepted = false;

    // Resolve and validate the target before accepting media or rate-limit
    // writes. Old public profile documents can survive a legacy institute
    // deletion; those links must not keep creating pending records that no
    // owner can ever approve.
    let profileInstituteId = null;
    try {
      const profileSnap = await profileRef.get();
      const profile = profileSnap.exists ? profileSnap.data() : null;
      profileInstituteId = profile && profile.slug === registration.slug &&
        typeof profile.instituteId === "string" ? profile.instituteId : null;
      if (!profileInstituteId || profileInstituteId.length > 128) {
        sendJson(response, 404, { error: "registration_link_not_found" });
        return;
      }
      const instituteSnap = await db.collection("institutes").doc(profileInstituteId).get();
      if (!instituteSnap.exists) {
        sendJson(response, 404, { error: "registration_link_not_found" });
        return;
      }
      if (!hasCurrentSubscription(instituteSnap.data(), now)) {
        sendJson(response, 409, { error: "registration_unavailable" });
        return;
      }
    } catch (error) {
      console.error("Public registration institute validation failed", {
        reason: error instanceof Error ? error.message : "Unknown error",
      });
      sendJson(response, 503, { error: "temporarily_unavailable" });
      return;
    }

    // An image is stored privately before the Firestore transaction. This keeps
    // registration records small and lets us return an error without consuming a
    // student's one allowed submission when Storage is unavailable.
    if (studentPhoto) {
      try {
        photoInstituteId = profileInstituteId;
        temporaryPhoto = await uploadPendingRegistrationPhoto({
          bucket, instituteId: profileInstituteId, requestId, photo: studentPhoto,
        });
      } catch (error) {
        console.error("Public registration photo upload failed", {
          reason: error instanceof Error ? error.message : "Unknown error",
        });
        sendJson(response, 503, { error: "temporarily_unavailable" });
        return;
      }
    }

    try {
      const result = await db.runTransaction(async (transaction) => {
        const instituteRef = db.collection("institutes").doc(profileInstituteId);
        const [profileSnap, instituteSnap, ipRateSnap, profileRateSnap, duplicateSnap] = await Promise.all([
          transaction.get(profileRef),
          transaction.get(instituteRef),
          transaction.get(ipRateRef),
          transaction.get(profileRateRef),
          transaction.get(duplicateRef),
        ]);
        if (!profileSnap.exists || profileSnap.get("slug") !== registration.slug) {
          return { kind: "not_found" };
        }
        const profile = profileSnap.data();
        const instituteId = typeof profile.instituteId === "string" ? profile.instituteId : "";
        if (!instituteId || instituteId.length > 128 || instituteId !== profileInstituteId) {
          return { kind: "not_found" };
        }
        if (!instituteSnap.exists) return { kind: "not_found" };
        if (!hasCurrentSubscription(instituteSnap.data(), now)) return { kind: "unavailable" };
        if (temporaryPhoto && (temporaryPhoto.storageBucket !== bucket.name || photoInstituteId !== instituteId)) {
          return { kind: "not_found" };
        }

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
          ...(temporaryPhoto ? { photoUpload: temporaryPhoto } : {}),
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
        accepted = true;
        sendJson(response, 201, { status: "accepted", requestId: result.requestId });
      } else if (result.kind === "not_found") {
        sendJson(response, 404, { error: "registration_link_not_found" });
      } else if (result.kind === "duplicate") {
        sendJson(response, 409, { error: "already_submitted" });
      } else if (result.kind === "unavailable") {
        sendJson(response, 409, { error: "registration_unavailable" });
      } else {
        sendJson(response, 429, { error: "try_again_later" });
      }
    } catch (_) {
      sendJson(response, 503, { error: "temporarily_unavailable" });
    } finally {
      // A rejected submission must not leave a private image behind. Accepted
      // submissions keep it until the institute approves the student.
      if (temporaryPhoto && photoInstituteId && !accepted) {
        await cleanupPendingRegistrationPhoto({
          bucket,
          instituteId: photoInstituteId,
          requestId,
        });
      }
    }
  };
}

module.exports = { createPublicRegistrationHandler };
