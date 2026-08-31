"use strict";

function buildRegistrationSlug(name, instituteId) {
  const cleanName = typeof name === "string" ? name.normalize("NFKC").trim() : "";
  const words = cleanName
    .toLocaleLowerCase("en-US")
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48) || "institute";
  const suffix = String(instituteId || "").slice(-6).toLocaleLowerCase("en-US");
  return `${words}-${suffix}`;
}

function registrationFormUrl(slug) {
  return `https://batchfee-477b8.web.app/register/${encodeURIComponent(slug)}`;
}

module.exports = { buildRegistrationSlug, registrationFormUrl };
