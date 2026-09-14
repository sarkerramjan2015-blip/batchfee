"use strict";

const ENDPOINT = "https://api.sms.net.bd/sendsms";

const KNOWN_FAILURES = new Map([
  ["400", "The SMS provider rejected a required or invalid parameter."],
  ["403", "The SMS provider denied this request."],
  ["404", "The SMS provider endpoint was not found."],
  ["405", "The SMS provider requires authorization."],
  ["410", "The SMS provider account has expired."],
  ["411", "The SMS provider account is suspended."],
  ["412", "The SMS schedule is invalid."],
  ["413", "The SMS Sender ID is invalid."],
  ["414", "The SMS message cannot be empty."],
  ["415", "The SMS message is too long."],
  ["416", "The SMS provider rejected the recipient number."],
  ["417", "The SMS provider balance is insufficient."],
  ["420", "The SMS content was blocked by the provider."],
  ["421", "The provider only permits registered test numbers before the first recharge."],
]);

class SmsNetBdError extends Error {
  constructor(code, message, { ambiguous = false } = {}) {
    super(message);
    this.name = "SmsNetBdError";
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
    throw new SmsNetBdError("INVALID_NUMBER", "Enter a valid Bangladesh mobile number.");
  }
  return `880${local.slice(1)}`;
}

function validatedMessage(value) {
  const message = typeof value === "string" ? value.trim() : "";
  if (!message) throw new SmsNetBdError("INVALID_MESSAGE", "The SMS message cannot be empty.");
  if (message.length > 1000) {
    throw new SmsNetBdError("INVALID_MESSAGE", "The SMS message is too long.");
  }
  return message;
}

function secretValue(source) {
  const value = typeof source === "function" ? source() : source;
  if (typeof value !== "string" || !value.trim()) {
    throw new SmsNetBdError("NOT_CONFIGURED", "SMS.net.bd API key is not configured.");
  }
  return value.trim();
}

function parseProviderResponse(body) {
  let payload;
  try {
    payload = JSON.parse(String(body || ""));
  } catch (_) {
    throw new SmsNetBdError("MALFORMED_RESPONSE", "The SMS provider returned an unreadable response.", { ambiguous: true });
  }
  if (!payload || typeof payload !== "object" || payload.error == null) {
    throw new SmsNetBdError("MALFORMED_RESPONSE", "The SMS provider returned an unreadable response.", { ambiguous: true });
  }

  const providerStatus = String(payload.error);
  const providerMessage = typeof payload.msg === "string" ? payload.msg.trim().slice(0, 160) : "";
  const requestId = payload.data && payload.data.request_id != null ? String(payload.data.request_id) : "";
  if (providerStatus === "0") {
    // The provider confirms submission, while final per-recipient delivery is
    // available from its report endpoint. Do not claim delivery prematurely.
    return {
      accepted: true,
      status: "pending",
      providerStatus,
      messageId: requestId,
      providerMessage,
    };
  }
  if (providerStatus === "409") {
    throw new SmsNetBdError(
      providerStatus,
      providerMessage || "The SMS provider reported an unknown server error.",
      { ambiguous: true },
    );
  }
  return {
    accepted: false,
    status: "failed",
    providerStatus,
    messageId: requestId,
    failureReason: KNOWN_FAILURES.get(providerStatus) || providerMessage || "The SMS provider rejected this message.",
  };
}

function createSmsNetBdProvider({ apiKey, fetchImpl = globalThis.fetch, timeoutMs = 12_000 } = {}) {
  if (typeof fetchImpl !== "function") throw new TypeError("A fetch implementation is required.");
  return {
    async sendTextSms({ number, message }) {
      const recipient = normalizeBangladeshPhone(number);
      const content = validatedMessage(message);
      const key = secretValue(apiKey);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const form = new URLSearchParams({ api_key: key, msg: content, to: recipient });
        const response = await fetchImpl(ENDPOINT, {
          method: "POST",
          headers: { "content-type": "application/x-www-form-urlencoded;charset=UTF-8" },
          body: form.toString(),
          signal: controller.signal,
        });
        const body = await response.text();
        try {
          const result = parseProviderResponse(body);
          if (response.ok || !result.accepted) return result;
        } catch (error) {
          if (error instanceof SmsNetBdError) throw error;
          throw error;
        }
        throw new SmsNetBdError(`HTTP_${response.status}`, "The SMS provider is temporarily unavailable.", { ambiguous: response.status >= 500 });
      } catch (error) {
        if (error instanceof SmsNetBdError) throw error;
        const timedOut = error && error.name === "AbortError";
        throw new SmsNetBdError(
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
  ENDPOINT,
  KNOWN_FAILURES,
  SmsNetBdError,
  createSmsNetBdProvider,
  normalizeBangladeshPhone,
  parseProviderResponse,
  validatedMessage,
};
