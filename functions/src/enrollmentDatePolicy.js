"use strict";

// Explicit policy wins. Legacy records without frozen terms used admission.
// A matching month alone cannot identify an admission-linked enrollment.
function isAdmissionLinkedEnrollment(enrollment, previousAdmissionDateMs) {
  if (typeof enrollment.admissionDateLinked === "boolean") return enrollment.admissionDateLinked;
  if (!enrollment.firstMonthFeePeriod) return true;
  return Number(previousAdmissionDateMs) > 0 &&
    Number(enrollment.joinedAtMs) === Number(previousAdmissionDateMs);
}

module.exports = { isAdmissionLinkedEnrollment };
