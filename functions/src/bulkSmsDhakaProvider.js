"use strict";

const ENDPOINT = "https://bulksmsdhaka.net/api/sendtext";
const ACCEPTED_CODES = new Set(["1000", "1001", "1002"]);
const KNOWN_FAILURES = new Map([
  ["1003", "The SMS provider could not send this message."],
  ["1005", "The SMS provider rejected the message as spam."],
  ["1006", "The SMS content did not pass provider validation."],
  ["1008", "The SMS server IP is not whitelisted."],
  ["1009", "The SMS provider account is not verified."],
  ["1010", "The SMS provider account is disabled."],
  ["1011", "The configured SMS Sender ID is unavailable."],
  ["1012", "This masking Sender ID only accepts Bengali messages."],
  ["1013", "The SMS provider balance validity is unavailable."],
  ["1014", "The SMS provider reported an internal error."],
  ["1015", "The SMS provider rejected the API credentials."],
  ["1016", "The SMS provider rejected the message ID."],
  ["1017", "The SMS provider requires a message ID."],
  ["1018", "The SMS provider API key is missing."],
  ["2001", "The SMS provider balance is insufficient."],
]);

class BulkSmsDhakaError extends Error {
  constructor(code, message, { ambiguous = false } = {}) {
    super(message);
    this.name = "BulkSmsDhakaError";
    this.code = code;
    this.ambiguous = ambiguous;
  }
}

function normalizeBangladeshPhone(value) {
  const digits = String(value || "").replace(/\D/g, "");
  let local = digits;
  if (digits.startsWith("880")) local = `0${digits.slice(3)}`;
  else if (/^1[3-9]\d{8}$/.test(digits)) local = `0${digits}`;
  if (!/^01[3-9]\d{8}$/.test(local)) {
    throw new BulkSmsDhakaError("INVALID_NUMBER", "Enter a valid Bangladesh mobile number.");
  }
  return local;
}

function validatedMessage(value) {
  const message = typeof value === "string" ? value.trim() : "";
  if (!message) throw new BulkSmsDhakaError("INVALID_MESSAGE", "The SMS message cannot be empty.");
  if (message.length > 1000) {
    throw new BulkSmsDhakaError("INVALID_MESSAGE", "The SMS message is too long.");
  }
  return message;
}

function firstString(source, keys) {
  for (const key of keys) {
    const value = source && source[key];
    if (value != null && String(value).trim()) return String(value).trim();
  }
  return "";
}

function parseProviderResponse(body) {
  const text = String(body || "").trim();
  let payload = null;
  try { payload = JSON.parse(text); } catch (_) { /* Some provider responses are plain text. */ }
  const statusCandidate = payload && typeof payload === "object"
    ? firstString(payload, ["Status", "status", "Code", "code"])
    : "";
  const status = statusCandidate || ((text.match(/\b(?:1000|1001|1002|1003|1005|1006|1008|1009|1010|1011|1012|1013|1014|1015|1016|1017|1018|2001)\b/) || [])[0] || "");
  if (!status) {
    throw new BulkSmsDhakaError("MALFORMED_RESPONSE", "The SMS provider returned an unreadable response.", { ambiguous: true });
  }
  const providerMessage = payload && typeof payload === "object"
    ? firstString(payload, ["Message", "message", "msg"])
    : "";
  const messageId = payload && typeof payload === "object"
    ? firstString(payload, ["MessageId", "messageId", "messageid", "SMSID", "smsid", "id"])
    : "";
  if (ACCEPTED_CODES.has(status)) {
    return {
      accepted: true,
      status: status === "1002" ? "pending" : "sent",
      providerStatus: status,
      messageId,
      providerMessage: providerMessage.slice(0, 160),
    };
  }
  return {
    accepted: false,
    status: "failed",
    providerStatus: status,
    messageId,
    failureReason: KNOWN_FAILURES.get(status) || "The SMS provider rejected this message.",
  };
}

function secretValue(source, label) {
  const value = typeof source === "function" ? source() : source;
  if (typeof value !== "string" || !value.trim()) {
    throw new BulkSmsDhakaError("NOT_CONFIGURED", `${label} is not configured.`);
  }
  return value.trim();
}

function createBulkSmsDhakaProvider({ apiKey, callerId, fetchImpl = globalThis.fetch, timeoutMs = 12_000 } = {}) {
  if (typeof fetchImpl !== "function") throw new TypeError("A fetch implementation is required.");
  return {
    async sendTextSms({ number, message }) {
      const recipient = normalizeBangladeshPhone(number);
      const content = validatedMessage(message);
      const key = secretValue(apiKey, "Bulk SMS Dhaka API key");
      const sender = secretValue(callerId, "Bulk SMS Dhaka Caller ID");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const form = new URLSearchParams({ apikey: key, callerID: sender, number: recipient, message: content });
        const response = await fetchImpl(ENDPOINT, {
          method: "POST",
          headers: { "content-type": "application/x-www-form-urlencoded;charset=UTF-8" },
          body: form.toString(),
          signal: controller.signal,
        });
        const body = await response.text();
        if (!response.ok) {
          // Bulk SMS Dhaka may return its documented provider error in a non-2xx
          // HTTP response. Preserve that actionable status rather than reducing
          // it to a generic temporary-outage message.
          if (/\b(?:IP\s+)?not\s+whitelisted\b/i.test(body)) {
            return {
              accepted: false,
              status: "failed",
              providerStatus: "1008",
              messageId: "",
              failureReason: KNOWN_FAILURES.get("1008"),
            };
          }
          try {
            const parsed = parseProviderResponse(body);
            if (!parsed.accepted) return parsed;
          } catch (_) {
            // No documented provider status was supplied; use the HTTP fallback.
          }
          throw new BulkSmsDhakaError(
            `HTTP_${response.status}`,
            "The SMS provider is temporarily unavailable.",
            { ambiguous: response.status >= 500 },
          );
        }
        return parseProviderResponse(body);
      } catch (error) {
        if (error instanceof BulkSmsDhakaError) throw error;
        const timedOut = error && error.name === "AbortError";
        throw new BulkSmsDhakaError(
          timedOut ? "TIMEOUT" : "NETWORK_ERROR",
          timedOut ? "The SMS provider did not respond in time." : "Could not reach the SMS provider.",
          { ambiguous: true },
        );
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

module.exports = {
  ACCEPTED_CODES,
  BulkSmsDhakaError,
  ENDPOINT,
  KNOWN_FAILURES,
  createBulkSmsDhakaProvider,
  normalizeBangladeshPhone,
  parseProviderResponse,
  validatedMessage,
};
