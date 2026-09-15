"use strict";

const assert = require("node:assert/strict");
const { randomBytes } = require("node:crypto");
const test = require("node:test");

const {
  ENDPOINT,
  BALANCE_ENDPOINT,
  DLR_ENDPOINT_PREFIX,
  ZendSmsError,
  createZendSmsProvider,
  normalizeBangladeshPhone,
  parseProviderResponse,
} = require("../src/zendSmsProvider");

function opaqueCredential() {
  return randomBytes(24).toString("base64url");
}

test("normalizes supported Bangladesh mobile formats to the ZendSMS international format", () => {
  assert.equal(normalizeBangladeshPhone("01712-345678"), "8801712345678");
  assert.equal(normalizeBangladeshPhone("+880 1712 345678"), "8801712345678");
  assert.throws(() => normalizeBangladeshPhone("012345"), /valid Bangladesh/i);
});

test("keeps a ZendSMS queue acknowledgement pending until delivery is reported", () => {
  assert.deepEqual(parseProviderResponse(JSON.stringify({
    success: true,
    code: 1000,
    message: "SMS accepted",
    data: { message_id: "6f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8", status: "QUEUED" },
  })), {
    accepted: true,
    status: "pending",
    providerStatus: "1000",
    messageId: "6f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8",
    providerMessage: "SMS accepted",
  });
});

test("maps ZendSMS documented rejections without exposing credentials", () => {
  const rejected = parseProviderResponse(JSON.stringify({
    success: false,
    code: 2201,
    message: "Insufficient wallet balance",
    data: {},
  }));
  assert.deepEqual(rejected, {
    accepted: false,
    status: "failed",
    providerStatus: "2201",
    messageId: "",
    failureReason: "The SMS provider balance is insufficient.",
  });
  assert.throws(
    () => parseProviderResponse('{"success":true,"code":1000,"data":{}}'),
    (error) => error instanceof ZendSmsError && error.ambiguous,
  );
});

test("uses JSON POST plus bearer authentication and never puts secrets in the URL or body", async () => {
  let captured;
  const apiKey = opaqueCredential();
  const senderId = "8809612781000";
  const provider = createZendSmsProvider({
    apiKey: () => apiKey,
    senderId: () => senderId,
    fetchImpl: async (url, options) => {
      captured = { url, options };
      return {
        ok: true,
        status: 202,
        text: async () => JSON.stringify({
          success: true,
          code: 1000,
          message: "SMS accepted",
          data: { message_id: "6f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8", status: "QUEUED" },
        }),
      };
    },
  });
  const result = await provider.sendTextSms({ number: "01712345678", message: "Fee reminder" });

  assert.equal(captured.url, ENDPOINT);
  assert.equal(captured.url.includes(apiKey), false);
  assert.equal(captured.options.method, "POST");
  assert.equal(captured.options.headers.authorization, `Bearer ${apiKey}`);
  assert.equal(captured.options.headers["content-type"], "application/json");
  assert.equal(captured.options.body.includes(apiKey), false);
  assert.deepEqual(JSON.parse(captured.options.body), {
    recipient: "8801712345678",
    sender_id: senderId,
    message: "Fee reminder",
  });
  assert.equal(result.status, "pending");
});

test("does not contact ZendSMS without a configured API key and sender ID", async () => {
  let called = false;
  const provider = createZendSmsProvider({
    apiKey: "",
    senderId: "",
    fetchImpl: async () => { called = true; },
  });
  await assert.rejects(
    provider.sendTextSms({ number: "01712345678", message: "Hello" }),
    (error) => error instanceof ZendSmsError && error.code === "NOT_CONFIGURED",
  );
  assert.equal(called, false);
});

test("reads the ZendSMS wallet and DLR using bearer authentication", async () => {
  const apiKey = opaqueCredential();
  const messageId = "6f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";
  const requests = [];
  const provider = createZendSmsProvider({
    apiKey,
    senderId: "8809612781000",
    fetchImpl: async (url, options) => {
      requests.push({ url, options });
      const body = url === BALANCE_ENDPOINT
        ? { success: true, code: 1000, data: { balance: 115, currency: "BDT" } }
        : { success: true, code: 1000, data: { message_id: messageId, status: "DELIVERED" } };
      return { ok: true, status: 200, text: async () => JSON.stringify(body) };
    },
  });
  assert.deepEqual(await provider.getBalance(), { balance: 115, currency: "BDT" });
  assert.deepEqual(await provider.getDeliveryStatus(messageId), {
    messageId, status: "DELIVERED", submittedAt: "", deliveredAt: "",
  });
  assert.equal(requests[0].url, BALANCE_ENDPOINT);
  assert.equal(requests[1].url, `${DLR_ENDPOINT_PREFIX}${messageId}`);
  assert.equal(requests.every(({ options }) => options.headers.authorization === `Bearer ${apiKey}`), true);
  assert.equal(requests.every(({ options }) => !String(options.body || "").includes(apiKey)), true);
});
