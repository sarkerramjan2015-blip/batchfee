"use strict";

// ZendSMS REST API adapter. Credentials are passed by the caller as Secret
// Manager accessors and are deliberately kept out of URLs, message records,
// error strings, and logs.
const ENDPOINT = "https://api.zendsms.com/api/v1/send-sms";
const BALANCE_ENDPOINT = "https://api.zendsms.com/api/v1/balance";
const DLR_ENDPOINT_PREFIX = "https://api.zendsms.com/api/v1/dlr/";

const KNOWN_FAILURES = new Map([
  ["2001", "The SMS provider rejected the API key."],
  ["2002", "The SMS provider API key is disabled."],
  ["2003", "The SMS provider rejected this server IP."],
  ["2004", "The SMS provider account is suspended."],
  ["2101", "The SMS provider rejected the recipient number."],
  ["2102", "The SMS provider rejected the sender ID."],
  ["2103", "The SMS provider sender ID is not approved."],
  ["2104", "The SMS message cannot be empty."],
  ["2105", "The SMS message is too long."],
  ["2106", "The SMS provider rejected this message."],
  ["2201", "The SMS provider balance is insufficient."],
  ["2202", "The SMS provider rate limit was reached. Try again shortly."],
  ["2203", "The SMS provider usage limit was reached. Try again shortly."],
  ["2204", "The SMS provider usage limit was reached. Try again shortly."],
  ["2408", "The SMS provider rate limit was reached. Try again shortly."],
]);

class ZendSmsError extends Error {
  constructor(code, message, { ambiguous = false } = {}) {
    super(message);
    this.name = "ZendSmsError";
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
    throw new ZendSmsError("INVALID_NUMBER", "Enter a valid Bangladesh mobile number.");
  }
  return `880${local.slice(1)}`;
}

function validatedMessage(value) {
  const message = typeof value === "string" ? value.trim() : "";
  if (!message) throw new ZendSmsError("INVALID_MESSAGE", "The SMS message cannot be empty.");
  if (message.length > 1000) {
    throw new ZendSmsError("INVALID_MESSAGE", "The SMS message is too long.");
  }
  return message;
}

function secretValue(source, label) {
  const value = typeof source === "function" ? source() : source;
  if (typeof value !== "string" || !value.trim()) {
    throw new ZendSmsError("NOT_CONFIGURED", `${label} is not configured.`);
  }
  return value.trim();
}

function stringValue(value, maxLength = 160) {
  return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
}

function parseProviderResponse(body) {
  let payload;
  try {
    payload = JSON.parse(String(body || ""));
  } catch (_) {
    throw new ZendSmsError("MALFORMED_RESPONSE", "The SMS provider returned an unreadable response.", { ambiguous: true });
  }
  if (!payload || typeof payload !== "object" || typeof payload.success !== "boolean") {
    throw new ZendSmsError("MALFORMED_RESPONSE", "The SMS provider returned an unreadable response.", { ambiguous: true });
  }

  const providerStatus = payload.code != null ? String(payload.code).slice(0, 32) : "";
  const providerMessage = stringValue(payload.message);
  const data = payload.data && typeof payload.data === "object" ? payload.data : {};
  const messageId = stringValue(data.message_id, 128);
  if (payload.success === true && providerStatus === "1000" && messageId) {
    // ZendSMS acknowledges queueing, not handset delivery. Keep the wallet
    // record pending until a trusted DLR integration updates it.
    return {
      accepted: true,
      status: "pending",
      providerStatus,
      messageId,
      providerMessage,
    };
  }
  if (payload.success === true) {
    throw new ZendSmsError("MALFORMED_RESPONSE", "The SMS provider returned an incomplete acceptance response.", { ambiguous: true });
  }
  return {
    accepted: false,
    status: "failed",
    providerStatus: providerStatus || "REJECTED",
    messageId,
    failureReason: KNOWN_FAILURES.get(providerStatus) || providerMessage || "The SMS provider rejected this message.",
  };
}

function parsePayload(body) {
  try {
    const payload = JSON.parse(String(body || ""));
    if (!payload || typeof payload !== "object" || typeof payload.success !== "boolean") {
      throw new Error("invalid payload");
    }
    return payload;
  } catch (_) {
    throw new ZendSmsError("MALFORMED_RESPONSE", "The SMS provider returned an unreadable response.", { ambiguous: true });
  }
}

function providerFailure(payload) {
  const code = payload.code != null ? String(payload.code).slice(0, 32) : "REJECTED";
  return new ZendSmsError(code, KNOWN_FAILURES.get(code) || stringValue(payload.message) || "The SMS provider rejected this request.");
}

function validMessageId(value) {
  return typeof value === "string" && /^[A-Za-z0-9-]{16,128}$/.test(value);
}

function createZendSmsProvider({ apiKey, senderId, fetchImpl = globalThis.fetch, timeoutMs = 12_000 } = {}) {
  if (typeof fetchImpl !== "function") throw new TypeError("A fetch implementation is required.");
  return {
    async sendTextSms({ number, message }) {
      const recipient = normalizeBangladeshPhone(number);
      const content = validatedMessage(message);
      const key = secretValue(apiKey, "ZendSMS API key");
      const sender = secretValue(senderId, "ZendSMS Sender ID");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetchImpl(ENDPOINT, {
          method: "POST",
          headers: {
            accept: "application/json",
            authorization: `Bearer ${key}`,
            "content-type": "application/json",
          },
          body: JSON.stringify({ recipient, sender_id: sender, message: content }),
          signal: controller.signal,
        });
        const body = await response.text();
        const result = parseProviderResponse(body);
        if (result.accepted && response.ok) return result;
        if (!result.accepted) return result;
        throw new ZendSmsError(
          `HTTP_${response.status}`,
          "The SMS provider is temporarily unavailable.",
          { ambiguous: response.status >= 500 },
        );
      } catch (error) {
        if (error instanceof ZendSmsError) throw error;
        const timedOut = error && error.name === "AbortError";
        throw new ZendSmsError(
          timedOut ? "TIMEOUT" : "NETWORK_ERROR",
          timedOut ? "The SMS provider did not respond in time." : "Could not reach the SMS provider.",
          { ambiguous: true },
        );
      } finally {
        clearTimeout(timer);
      }
    },
    async getBalance() {
      const key = secretValue(apiKey, "ZendSMS API key");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetchImpl(BALANCE_ENDPOINT, {
          method: "GET",
          headers: { accept: "application/json", authorization: `Bearer ${key}` },
          signal: controller.signal,
        });
        const payload = parsePayload(await response.text());
        if (!response.ok || payload.success !== true || String(payload.code) !== "1000") throw providerFailure(payload);
        const balance = Number(payload.data && payload.data.balance);
        if (!Number.isFinite(balance) || balance < 0) {
          throw new ZendSmsError("MALFORMED_RESPONSE", "The SMS provider returned an invalid wallet balance.", { ambiguous: true });
        }
        return { balance, currency: stringValue(payload.data && payload.data.currency, 12) || "BDT" };
      } catch (error) {
        if (error instanceof ZendSmsError) throw error;
        const timedOut = error && error.name === "AbortError";
        throw new ZendSmsError(timedOut ? "TIMEOUT" : "NETWORK_ERROR", timedOut
          ? "The SMS provider did not respond in time." : "Could not reach the SMS provider.", { ambiguous: true });
      } finally { clearTimeout(timer); }
    },
    async getDeliveryStatus(messageId) {
      if (!validMessageId(messageId)) throw new ZendSmsError("INVALID_MESSAGE_ID", "Invalid SMS provider message ID.");
      const key = secretValue(apiKey, "ZendSMS API key");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetchImpl(`${DLR_ENDPOINT_PREFIX}${encodeURIComponent(messageId)}`, {
          method: "GET",
          headers: { accept: "application/json", authorization: `Bearer ${key}` },
          signal: controller.signal,
        });
        const payload = parsePayload(await response.text());
        if (!response.ok || payload.success !== true || String(payload.code) !== "1000") throw providerFailure(payload);
        const data = payload.data && typeof payload.data === "object" ? payload.data : {};
        const status = stringValue(data.status, 32).toUpperCase();
        if (!status) throw new ZendSmsError("MALFORMED_RESPONSE", "The SMS provider returned an invalid delivery status.", { ambiguous: true });
        return { messageId: stringValue(data.message_id, 128) || messageId, status, submittedAt: stringValue(data.submitted_at, 48), deliveredAt: stringValue(data.delivered_at, 48) };
      } catch (error) {
        if (error instanceof ZendSmsError) throw error;
        const timedOut = error && error.name === "AbortError";
        throw new ZendSmsError(timedOut ? "TIMEOUT" : "NETWORK_ERROR", timedOut
          ? "The SMS provider did not respond in time." : "Could not reach the SMS provider.", { ambiguous: true });
      } finally { clearTimeout(timer); }
    },
  };
}

module.exports = {
  ENDPOINT,
  BALANCE_ENDPOINT,
  DLR_ENDPOINT_PREFIX,
  KNOWN_FAILURES,
  ZendSmsError,
  createZendSmsProvider,
  normalizeBangladeshPhone,
  parseProviderResponse,
  validatedMessage,
};
