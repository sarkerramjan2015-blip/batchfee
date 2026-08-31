"use strict";

function subscriptionEntitlementPatch({
  institute,
  plan,
  now,
  freeTrialDurationMs,
  freeTrialStudentLimit,
}) {
  const planId = typeof institute.currentPlanId === "string" && institute.currentPlanId
    ? institute.currentPlanId : "plan_free_trial";
  const createdAt = Number(institute.createdAtMs || institute.createdAt || now);
  const endMs = Number.isSafeInteger(institute.currentPeriodEndMs)
    ? institute.currentPeriodEndMs
    : Number.isSafeInteger(institute.trialEndDate)
      ? institute.trialEndDate
      : createdAt + freeTrialDurationMs;
  const validStatus = ["trial", "active", "expired", "blocked"]
    .includes(institute.subscriptionStatus);
  const nextStatus = institute.isActive === false ? "blocked"
    : endMs <= now ? "expired"
      : planId === "plan_free_trial" ? "trial" : "active";
  const patch = {};

  if (institute.isActive == null) patch.isActive = true;
  if (!Number.isSafeInteger(institute.currentPeriodEndMs)) patch.currentPeriodEndMs = endMs;
  if (typeof institute.currentPlanId !== "string" || !institute.currentPlanId) {
    patch.currentPlanId = planId;
  }
  if (!validStatus || (institute.subscriptionStatus !== "blocked" &&
      institute.subscriptionStatus !== "expired" &&
      institute.subscriptionStatus !== nextStatus)) {
    patch.subscriptionStatus = nextStatus;
  }
  if (planId === "plan_free_trial" && institute.studentLimit !== freeTrialStudentLimit) {
    patch.studentLimit = freeTrialStudentLimit;
  } else if (planId !== "plan_free_trial" &&
      (!Number.isSafeInteger(institute.studentLimit) || institute.studentLimit < 1)) {
    patch.studentLimit = Number.isSafeInteger(plan.maxStudents) && plan.maxStudents > 0
      ? plan.maxStudents : 50;
  }
  if (!Number.isSafeInteger(institute.staffLimit) || institute.staffLimit < 1) {
    patch.staffLimit = Number.isSafeInteger(plan.maxUsers) && plan.maxUsers > 0
      ? plan.maxUsers : 1;
  }
  return { patch, planId };
}

async function mapWithConcurrency(items, concurrency, worker) {
  const results = new Array(items.length);
  let nextIndex = 0;
  const workerCount = Math.min(Math.max(1, concurrency), items.length);
  const runners = Array.from({ length: workerCount }, async () => {
    while (true) {
      const index = nextIndex;
      nextIndex += 1;
      if (index >= items.length) return;
      results[index] = await worker(items[index], index);
    }
  });
  await Promise.all(runners);
  return results;
}

module.exports = { mapWithConcurrency, subscriptionEntitlementPatch };
