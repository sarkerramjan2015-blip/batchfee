"use strict";

const assert = require("node:assert/strict");
const { randomBytes } = require("node:crypto");
const test = require("node:test");

function opaqueCredential() {
  return randomBytes(18).toString("base64url");
}
const {
  BulkSmsDhakaError,
  ENDPOINT,
  createBulkSmsDhakaProvider,
  normalizeBangladeshPhone,
  parseProviderResponse,
} = require("../src/bulkSmsDhakaProvider");

test("normalizes supported Bangladesh mobile formats", () => {
  assert.equal(normalizeBangladeshPhone("01712-345678"), "01712345678");
  assert.equal(normalizeBangladeshPhone("+880 1712 345678"), "01712345678");
  assert.equal(normalizeBangladeshPhone("8801812345678"), "01812345678");
  assert.throws(() => normalizeBangladeshPhone("012345"), /valid Bangladesh/i);
});

test("maps JSON and plain-text accepted responses without exposing raw data", () => {
  assert.deepEqual(parseProviderResponse(JSON.stringify({ Status: "1000", Success: "true", Message: "Sent", messageid: 44 })), {
    accepted: true, status: "sent", providerStatus: "1000", messageId: "44", providerMessage: "Sent",
  });
  assert.equal(parseProviderResponse("Status: 1002, request pending").status, "pending");
});

test("maps every documented rejection and fails closed for malformed responses", () => {
  const knownFailures = ["1003", "1005", "1006", "1008", "1009", "1010", "1011", "1012", "1013", "1014", "1015", "2001"];
  for (const providerStatus of knownFailures) {
    const rejected = parseProviderResponse(JSON.stringify({ Status: providerStatus }));
    assert.equal(rejected.accepted, false);
    assert.equal(rejected.status, "failed");
    assert.equal(rejected.providerStatus, providerStatus);
    assert.ok(rejected.failureReason);
  }
  assert.match(parseProviderResponse('{"Status":"2001"}').failureReason, /balance/i);
  assert.throws(() => parseProviderResponse("unexpected html"), (error) => error.code === "MALFORMED_RESPONSE" && error.ambiguous);
});

test("rejects blank and oversized messages before contacting the provider", async () => {
  let called = false;
  const provider = createBulkSmsDhakaProvider({
    apiKey: opaqueCredential(),
    callerId: opaqueCredential(),
    fetchImpl: async () => { called = true; },
  });
  await assert.rejects(
    provider.sendTextSms({ number: "01712345678", message: "   " }),
    (error) => error.code === "INVALID_MESSAGE",
  );
  await assert.rejects(
    provider.sendTextSms({ number: "01712345678", message: "x".repeat(1001) }),
    (error) => error.code === "INVALID_MESSAGE",
  );
  assert.equal(called, false);
});

test("uses POST form data and keeps credentials out of the URL", async () => {
  let captured;
  const apiKey = opaqueCredential();
  const callerId = opaqueCredential();
  const provider = createBulkSmsDhakaProvider({
    apiKey: () => apiKey,
    callerId: () => callerId,
    fetchImpl: async (url, options) => {
      captured = { url, options };
      return { ok: true, status: 200, text: async () => '{"Status":"1001","MessageId":"m-1"}' };
    },
  });
  const result = await provider.sendTextSms({ number: "+8801712345678", message: "Fee reminder" });
  const form = new URLSearchParams(captured.options.body);
  assert.equal(captured.url, ENDPOINT);
  assert.equal(captured.url.includes(apiKey), false);
  assert.equal(captured.url.includes(callerId), false);
  assert.equal(captured.options.method, "POST");
  assert.equal(form.get("apikey"), apiKey);
  assert.equal(form.get("callerID"), callerId);
  assert.equal(result.accepted, true);
});

test("preserves a provider IP-whitelist rejection returned with HTTP 401", async () => {
  const provider = createBulkSmsDhakaProvider({
    apiKey: opaqueCredential(),
    callerId: opaqueCredential(),
    fetchImpl: async () => ({
      ok: false,
      status: 401,
      text: async () => "Access Denied. Your IP is not whitelisted for API access.",
    }),
  });
  const result = await provider.sendTextSms({ number: "01712345678", message: "Fee reminder" });
  assert.deepEqual(result, {
    accepted: false,
    status: "failed",
    providerStatus: "1008",
    messageId: "",
    failureReason: "The SMS server IP is not whitelisted.",
  });
});

test("rejects missing configuration before contacting the provider", async () => {
  let called = false;
  const provider = createBulkSmsDhakaProvider({
    apiKey: "",
    callerId: opaqueCredential(),
    fetchImpl: async () => { called = true; },
  });
  await assert.rejects(
    provider.sendTextSms({ number: "01712345678", message: "Hello" }),
    (error) => error instanceof BulkSmsDhakaError && error.code === "NOT_CONFIGURED",
  );
  assert.equal(called, false);
});

test("network and timeout failures are marked ambiguous", async () => {
  const networkProvider = createBulkSmsDhakaProvider({
    apiKey: opaqueCredential(), callerId: opaqueCredential(), fetchImpl: async () => { throw new Error("offline"); },
  });
  await assert.rejects(
    networkProvider.sendTextSms({ number: "01712345678", message: "Hello" }),
    (error) => error.code === "NETWORK_ERROR" && error.ambiguous,
  );

  const timeoutProvider = createBulkSmsDhakaProvider({
    apiKey: opaqueCredential(),
    callerId: opaqueCredential(),
    timeoutMs: 5,
    fetchImpl: (_url, options) => new Promise((_resolve, reject) => {
      options.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
    }),
  });
  await assert.rejects(
    timeoutProvider.sendTextSms({ number: "01712345678", message: "Hello" }),
    (error) => error.code === "TIMEOUT" && error.ambiguous,
  );
});
