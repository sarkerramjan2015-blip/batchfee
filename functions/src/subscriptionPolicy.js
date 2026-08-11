"use strict";

// The canonical free-trial duration used by every trusted backend flow.
const FREE_TRIAL_DURATION_MS = 30 * 24 * 60 * 60 * 1000;

function hasCurrentSubscription(institute, now = Date.now()) {
  const endMs = Number(institute && institute.currentPeriodEndMs);
  return Boolean(
    institute &&
    institute.isActive !== false &&
    institute.deletionState !== "retained" &&
    ["trial", "active"].includes(institute.subscriptionStatus) &&
    Number.isSafeInteger(endMs) && endMs > now,
  );
}

module.exports = { FREE_TRIAL_DURATION_MS, hasCurrentSubscription };
