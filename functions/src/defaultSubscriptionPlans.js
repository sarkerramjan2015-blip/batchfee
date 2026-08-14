"use strict";

// These are the public BatchFee plans. Firestore can override a plan for
// platform-managed pricing, but an absent document must never make a real,
// published plan unavailable to billing or entitlement enforcement.
const legacyPricingPlans = [
  ["basic", "Basic", 199, 50, 5, 1, "", 1],
  ["standard", "Standard", 299, 100, 10, 2, "", 2],
  ["spark", "Spark", 399, 150, 15, 3, "", 3],
  ["grow", "Grow", 499, 200, 20, 5, "", 4],
  ["pro", "Pro", 599, 250, 25, 8, "Popular", 5],
  ["elite", "Elite", 699, 300, 30, 10, "", 6],
  ["prime", "Prime", 799, 350, 35, 12, "", 7],
  ["max", "Max", 899, 400, 40, 15, "", 8],
  ["ultra", "Ultra", 999, 450, 45, 18, "", 9],
  ["scale", "Scale", 1099, 500, 50, 20, "Recommended", 10],
].map(([id, name, priceBdt, maxStudents, maxBatches, maxUsers, tag, tierLevel]) => ({
  id,
  name,
  description: `${name} plan for growing institutes`,
  priceBdt,
  priceInr: 0,
  maxStudents,
  maxBatches,
  maxUsers,
  maxBranches: 1,
  tag,
  tierLevel,
}));

const defaultPlans = [
  {
    id: "plan_free_trial", name: "Free Trial", description: "30-day full access trial with unlimited students",
    // Zero is the canonical unlimited-trial display value. Trusted entitlement
    // logic uses the explicit trial policy, never this numeric value, to decide
    // whether a student seat is available.
    priceBdt: 0, priceInr: 0, maxStudents: 0, unlimitedStudents: true, maxBatches: 5, maxUsers: 1,
    maxBranches: 1, tag: "Trial", tierLevel: 0,
  },
  // Existing plans remain supported so no current institute loses access when
  // the public catalog is restored.
  {
    id: "plan_starter", name: "Starter", description: "For private tutors and small batches",
    priceBdt: 499, priceInr: 399, maxStudents: 150, maxBatches: 10, maxUsers: 3,
    maxBranches: 1, tag: "Basic", tierLevel: 101,
  },
  {
    id: "plan_growth", name: "Growth", description: "For growing coaching centers",
    priceBdt: 999, priceInr: 799, maxStudents: 500, maxBatches: 30, maxUsers: 13,
    maxBranches: 1, tag: "Popular", tierLevel: 102,
  },
  {
    id: "plan_pro", name: "Pro", description: "Professional centers and schools",
    priceBdt: 1999, priceInr: 1499, maxStudents: 1500, maxBatches: 100, maxUsers: 60,
    maxBranches: 1, tag: "Recommended", tierLevel: 103,
  },
  {
    id: "plan_institute", name: "Institute", description: "Large institutes and branches",
    priceBdt: 4999, priceInr: 3999, maxStudents: 5000, maxBatches: 300, maxUsers: 999,
    maxBranches: 5, tag: "Advanced", tierLevel: 104,
  },
  ...legacyPricingPlans,
];

const defaultPlansById = new Map(defaultPlans.map((plan) => [plan.id, plan]));

function defaultSubscriptionPlan(planId) {
  return defaultPlansById.get(planId) || null;
}

function planFromSnapshot(planSnap, planId) {
  return planSnap && planSnap.exists ? planSnap.data() : defaultSubscriptionPlan(planId);
}

module.exports = { defaultSubscriptionPlan, legacyPricingPlans, planFromSnapshot };
