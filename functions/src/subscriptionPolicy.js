"use strict";

// The canonical free-trial duration used by every trusted backend flow.
const FREE_TRIAL_DURATION_MS = 30 * 24 * 60 * 60 * 1000;
// New institute records use zero as the display value for unlimited trial
// student seats. Entitlement checks must still use the plan/status helper so
// older records that stored a numeric limit remain unlimited while live.
const FREE_TRIAL_STUDENT_LIMIT = 0;

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

// Student seats are unlimited only for a live Free Trial.  Checking the plan
// ID as well as the status prevents a malformed paid institute record from
// accidentally bypassing its paid-plan student limit.
function hasUnlimitedTrialStudents(institute, now = Date.now()) {
  return Boolean(
    institute &&
    institute.currentPlanId === "plan_free_trial" &&
    institute.subscriptionStatus === "trial" &&
    hasCurrentSubscription(institute, now),
  );
}

module.exports = {
  FREE_TRIAL_DURATION_MS,
  FREE_TRIAL_STUDENT_LIMIT,
  hasCurrentSubscription,
  hasUnlimitedTrialStudents,
};
