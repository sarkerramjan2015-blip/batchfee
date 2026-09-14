"use strict";

const assert = require("node:assert/strict");
const { randomBytes } = require("node:crypto");
const test = require("node:test");

const {
  ENDPOINT,
  SmsNetBdError,
  createSmsNetBdProvider,
  normalizeBangladeshPhone,
  parseProviderResponse,
} = require("../src/smsNetBdProvider");

function opaqueCredential() {
  return randomBytes(24).toString("base64url");
}

test("normalizes supported Bangladesh mobile formats to the provider country-code format", () => {
  assert.equal(normalizeBangladeshPhone("01712-345678"), "8801712345678");
  assert.equal(normalizeBangladeshPhone("+880 1712 345678"), "8801712345678");
  assert.throws(() => normalizeBangladeshPhone("012345"), /valid Bangladesh/i);
});

test("keeps a successful provider submission pending until delivery is reported", () => {
  assert.deepEqual(parseProviderResponse('{"error":0,"msg":"Request successfully submitted","data":{"request_id":44}}'), {
    accepted: true,
    status: "pending",
    providerStatus: "0",
    messageId: "44",
    providerMessage: "Request successfully submitted",
  });
});

test("maps documented rejections and retains the provider's actionable reason", () => {
  const rejected = parseProviderResponse('{"error":417,"msg":"Insufficient balance"}');
  assert.deepEqual(rejected, {
    accepted: false,
    status: "failed",
    providerStatus: "417",
    messageId: "",
    failureReason: "The SMS provider balance is insufficient.",
  });
  assert.throws(
    () => parseProviderResponse('{"error":409,"msg":"Unknown error occurred"}'),
    (error) => error instanceof SmsNetBdError && error.ambiguous,
  );
});

test("uses POST form data and keeps the API key out of the URL", async () => {
  let captured;
  const apiKey = opaqueCredential();
  const provider = createSmsNetBdProvider({
    apiKey: () => apiKey,
    fetchImpl: async (url, options) => {
      captured = { url, options };
      return { ok: true, status: 200, text: async () => '{"error":0,"data":{"request_id":55}}' };
    },
  });
  const result = await provider.sendTextSms({ number: "01712345678", message: "Fee reminder" });
  const form = new URLSearchParams(captured.options.body);
  assert.equal(captured.url, ENDPOINT);
  assert.equal(captured.url.includes(apiKey), false);
  assert.equal(captured.options.method, "POST");
  assert.equal(form.get("api_key"), apiKey);
  assert.equal(form.get("msg"), "Fee reminder");
  assert.equal(form.get("to"), "8801712345678");
  assert.equal(result.status, "pending");
});

test("does not contact the provider without a configured key", async () => {
  let called = false;
  const provider = createSmsNetBdProvider({ apiKey: "", fetchImpl: async () => { called = true; } });
  await assert.rejects(
    provider.sendTextSms({ number: "01712345678", message: "Hello" }),
    (error) => error instanceof SmsNetBdError && error.code === "NOT_CONFIGURED",
  );
  assert.equal(called, false);
});
