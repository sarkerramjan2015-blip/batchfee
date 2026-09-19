"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  SMS_PACKAGES,
  SMS_RECHARGE_CHARGE_PERCENT,
  SMS_SEND_METHODS,
  WALLET_FIELDS,
  createServerSmsHandler,
  createTenantSmsDeliveryRefreshHandler,
  createSmsWalletHandler,
  dhakaUsageKeys,
  platformSmsAnalytics,
  recordPlatformSmsTopup,
  rechargeQuote,
  smsCreditCount,
  updateSmsMessageStatus,
  walletDefaults,
} = require("../src/smsWallet");

class Snapshot {
  constructor(db, path) {
    this.ref = { path };
    this.id = path.split("/").at(-1);
    this.value = db.documents.has(path) ? structuredClone(db.documents.get(path)) : undefined;
    this.exists = this.value !== undefined;
  }
  data() { return this.value === undefined ? undefined : structuredClone(this.value); }
  get(field) { return this.value && this.value[field]; }
}

class Query {
  constructor(db, path, filters = [], orderings = [], limitValue = null) {
    this.db = db;
    this.path = path;
    this.filters = filters;
    this.orderings = orderings;
    this.limitValue = limitValue;
  }
  where(field, operator, value) {
    return new Query(this.db, this.path, [...this.filters, [field, operator, value]], this.orderings, this.limitValue);
  }
  orderBy(field, direction) {
    return new Query(this.db, this.path, this.filters, [...this.orderings, [field, direction]], this.limitValue);
  }
  limit(value) { return new Query(this.db, this.path, this.filters, this.orderings, value); }
  rows() {
    const depth = this.path.split("/").length + 1;
    const matches = [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value));
    matches.sort((a, b) => {
      for (const [field, direction] of this.orderings) {
        const av = a[1][field] ?? 0;
        const bv = b[1][field] ?? 0;
        if (av !== bv) return direction === "desc" ? (av < bv ? 1 : -1) : (av > bv ? 1 : -1);
      }
      return 0;
    });
    const sliced = this.limitValue == null ? matches : matches.slice(0, this.limitValue);
    return sliced.map(([path]) => new Snapshot(this.db, path));
  }
  async get() {
    const docs = this.rows();
    return { docs, empty: docs.length === 0, size: docs.length };
  }
}

class CollectionGroup {
  constructor(db, collectionName, filters = [], orderings = [], limitValue = null) {
    this.db = db;
    this.collectionName = collectionName;
    this.filters = filters;
    this.orderings = orderings;
    this.limitValue = limitValue;
  }
  where(field, operator, value) {
    return new CollectionGroup(this.db, this.collectionName, [...this.filters, [field, operator, value]], this.orderings, this.limitValue);
  }
  orderBy(field, direction) {
    return new CollectionGroup(this.db, this.collectionName, this.filters, [...this.orderings, [field, direction]], this.limitValue);
  }
  limit(value) { return new CollectionGroup(this.db, this.collectionName, this.filters, this.orderings, value); }
  rows() {
    const matches = [...this.db.documents.entries()]
      .filter(([path]) => path.split("/").filter((part) => part === this.collectionName).length > 0)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value));
    matches.sort((a, b) => {
      for (const [field, direction] of this.orderings) {
        const av = a[1][field] ?? 0;
        const bv = b[1][field] ?? 0;
        if (av !== bv) return direction === "desc" ? (av < bv ? 1 : -1) : (av > bv ? 1 : -1);
      }
      return 0;
    });
    const sliced = this.limitValue == null ? matches : matches.slice(0, this.limitValue);
    return sliced.map(([path]) => new Snapshot(this.db, path));
  }
  async get() {
    const docs = this.rows();
    return { docs, empty: docs.length === 0, size: docs.length };
  }
}

class Collection extends Query {
  doc(id) { return new Document(this.db, `${this.path}/${id}`); }
}

class Document {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new Collection(this.db, `${this.path}/${name}`); }
  async get() { return new Snapshot(this.db, this.path); }
  async create(data) {
    if (this.db.documents.has(this.path)) throw Object.assign(new Error("already exists"), { code: 6 });
    this.db.documents.set(this.path, structuredClone(data));
    return { path: this.path };
  }
  async update(data) {
    if (!this.db.documents.has(this.path)) throw Object.assign(new Error("not found"), { code: 5 });
    this.db.documents.set(this.path, { ...this.db.documents.get(this.path), ...structuredClone(data) });
    return { path: this.path };
  }
}

class Db {
  constructor(seed) {
    this.documents = new Map(Object.entries(seed).map(([path, data]) => [path, structuredClone(data)]));
  }
  collection(name) { return new Collection(this, name); }
  collectionGroup(name) { return new CollectionGroup(this, name); }
  async runTransaction(callback) {
    const transaction = {
      get: async (target) => target instanceof Document
        ? new Snapshot(this, target.path)
        : await target.get(),
      create: (ref, data) => {
        if (this.documents.has(ref.path)) throw Object.assign(new Error("already exists"), { code: 6 });
        this.documents.set(ref.path, structuredClone(data));
      },
      set: (ref, data, options) => this.documents.set(
        ref.path,
        options && options.merge ? { ...this.documents.get(ref.path), ...structuredClone(data) } : structuredClone(data),
      ),
      update: (ref, data) => this.documents.set(ref.path, { ...this.documents.get(ref.path), ...structuredClone(data) }),
    };
    return callback(transaction);
  }
}

function seededDb() {
  return new Db({
    "institutes/i": { isActive: true, instituteName: "Test Institute" },
    "institutes/j": { isActive: true, instituteName: "Other Institute" },
    "app_users/owner": { instituteId: "i", role: "InstituteOwner", status: "active" },
    "app_users/staff": { instituteId: "i", role: "Staff", status: "active" },
    "app_users/root": { role: "SuperAdmin", status: "active" },
    "app_users/billing": { platformRole: "billing", status: "active" },
    "app_users/support": { platformRole: "support", status: "active" },
    "institutes/i/staffs/staff": { status: "active" },
  });
}

function handlerFor(db, smsProvider) {
  return createSmsWalletHandler({ db, smsProvider });
}

function serverHandlerFor(db, smsProvider) {
  return createServerSmsHandler({ db, smsProvider });
}

function tenantDlrHandlerFor(db, smsProvider) {
  return createTenantSmsDeliveryRefreshHandler({ db, smsProvider });
}

test("sms wallet exposes only carrier and server methods with safe defaults", () => {
  const now = Date.UTC(2026, 8, 12, 0, 0, 0);
  const keys = dhakaUsageKeys(now);
  assert.deepEqual([...SMS_SEND_METHODS].sort(), ["carrier", "server"]);
  assert.deepEqual(walletDefaults({}, now), {
    sms_balance: 0,
    total_sms_purchased: 0,
    total_sms_used: 0,
    sms_used_today: 0,
    sms_usage_day_key: keys.dayKey,
    sms_used_this_month: 0,
    sms_usage_month_key: keys.monthKey,
    sms_send_method: "server",
  });
  assert.deepEqual(WALLET_FIELDS, {
    sms_balance: 0,
    total_sms_purchased: 0,
    total_sms_used: 0,
    sms_used_today: 0,
    sms_usage_day_key: "",
    sms_used_this_month: 0,
    sms_usage_month_key: "",
    sms_send_method: "server",
  });
});

test("wallet usage rolls over on Dhaka day and month boundaries", () => {
  const september = Date.UTC(2026, 8, 30, 17, 59, 0); // 30 Sep 23:59 in Dhaka
  const october = Date.UTC(2026, 8, 30, 18, 1, 0); // 01 Oct 00:01 in Dhaka
  const previous = dhakaUsageKeys(september);
  const current = dhakaUsageKeys(october);
  const wallet = walletDefaults({
    sms_used_today: 7,
    sms_usage_day_key: previous.dayKey,
    sms_used_this_month: 31,
    sms_usage_month_key: previous.monthKey,
  }, october);

  assert.notEqual(previous.dayKey, current.dayKey);
  assert.notEqual(previous.monthKey, current.monthKey);
  assert.equal(wallet.sms_used_today, 0);
  assert.equal(wallet.sms_used_this_month, 0);
  assert.equal(wallet.sms_usage_day_key, current.dayKey);
  assert.equal(wallet.sms_usage_month_key, current.monthKey);
});

test("get_wallet initializes missing fields with defaults and preserves existing values", async () => {
  const db = seededDb();
  const handler = handlerFor(db);

  const first = await handler({ auth: { uid: "owner" }, data: { action: "get_wallet", operationId: "wallet-get-00000001" } });
  assert.deepEqual(first, {
    smsBalance: 0, totalSmsPurchased: 0, totalSmsUsed: 0,
    smsUsedToday: 0, smsUsedThisMonth: 0, smsSendMethod: "server",
  });
  assert.equal(db.documents.get("institutes/i").sms_balance, 0);
  assert.equal(db.documents.get("institutes/i").sms_send_method, "server");

  db.documents.get("institutes/i").sms_balance = 42;
  db.documents.get("institutes/i").total_sms_purchased = 100;
  db.documents.get("institutes/i").total_sms_used = 58;
  const second = await handler({ auth: { uid: "owner" }, data: { action: "get_wallet", operationId: "wallet-get-00000002" } });
  assert.deepEqual(second, {
    smsBalance: 42, totalSmsPurchased: 100, totalSmsUsed: 58,
    smsUsedToday: 0, smsUsedThisMonth: 0, smsSendMethod: "server",
  });
});

test("staff can read the wallet but cannot change the send method", async () => {
  const db = seededDb();
  const handler = handlerFor(db);

  const wallet = await handler({ auth: { uid: "staff" }, data: { action: "get_wallet", operationId: "wallet-get-00000003" } });
  assert.equal(wallet.smsSendMethod, "server");

  await assert.rejects(
    handler({ auth: { uid: "staff" }, data: { action: "set_send_method", smsSendMethod: "server", operationId: "wallet-set-00000003" } }),
    /owner/i,
  );
  assert.equal(db.documents.get("institutes/i").sms_send_method, "server");
});

test("set_send_method validates the value and writes one audited change", async () => {
  const db = seededDb();
  const handler = handlerFor(db);

  await assert.rejects(
    handler({ auth: { uid: "owner" }, data: { action: "set_send_method", smsSendMethod: "email", operationId: "wallet-set-00000004" } }),
    /carrier or server/i,
  );

  const result = await handler({ auth: { uid: "owner" }, data: { action: "set_send_method", smsSendMethod: "carrier", operationId: "wallet-set-00000005" } });
  assert.equal(result.smsSendMethod, "carrier");
  assert.equal(db.documents.get("institutes/i").sms_send_method, "carrier");

  const audit = db.documents.get("institutes/i/sms_wallet_audit/wallet-set-00000005");
  assert.equal(audit.action, "set_send_method");
  assert.equal(audit.before, "server");
  assert.equal(audit.after, "carrier");
  assert.equal(audit.actorUid, "owner");
  assert.equal(audit.operationId, "wallet-set-00000005");
});

test("replaying the same set_send_method operation does not duplicate the audit row", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  const request = { auth: { uid: "owner" }, data: { action: "set_send_method", smsSendMethod: "carrier", operationId: "wallet-set-replay-01" } };

  const first = await handler(request);
  const replay = await handler(request);
  assert.deepEqual(replay, first);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/sms_wallet_audit/")).length, 1);

  const back = await handler({ auth: { uid: "owner" }, data: { action: "set_send_method", smsSendMethod: "server", operationId: "wallet-set-back-0001" } });
  assert.equal(back.smsSendMethod, "server");
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/sms_wallet_audit/")).length, 2);
});

test("unauthenticated and unknown-institute requests fail closed", async () => {
  const db = seededDb();
  const handler = handlerFor(db);

  await assert.rejects(handler({ auth: null, data: { action: "get_wallet", operationId: "wallet-get-00000006" } }), /sign in/i);
  await assert.rejects(
    handler({ auth: { uid: "ghost" }, data: { action: "get_wallet", operationId: "wallet-get-00000007" } }),
    /cannot manage SMS settings|not ready yet/i,
  );
  await assert.rejects(
    handler({ auth: { uid: "owner" }, data: { action: "buy_sms", operationId: "wallet-buy-00000001" } }),
    /invalid SMS wallet operation/i,
  );
});

test("the seven package quotes keep base SMS rates within 32-38 poisha and add the exact 1.8% charge", () => {
  assert.equal(SMS_PACKAGES.length, 7);
  assert.equal(SMS_RECHARGE_CHARGE_PERCENT, 1.8);
  const expected = [
    ["starter", 100, 265, 101.8],
    ["basic", 200, 540, 203.6],
    ["standard", 500, 1385, 509],
    ["pro", 1000, 2855, 1018],
    ["premium", 2000, 5880, 2036],
    ["advanced", 5000, 15150, 5090],
    ["enterprise", 10000, 31250, 10180],
  ];
  for (const [id, baseAmount, smsCount, payable] of expected) {
    const quote = rechargeQuote(SMS_PACKAGES.find((pkg) => pkg.id === id));
    assert.equal(quote.baseAmount, baseAmount);
    assert.equal(quote.smsCount, smsCount);
    assert.equal(quote.payableAmount, payable);
    assert.equal(quote.chargeAmount, Math.round((payable - baseAmount) * 100) / 100);
    assert.equal(quote.chargePercent, 1.8);
    assert.ok((quote.baseAmount * 100) / quote.smsCount >= 32);
    assert.ok((quote.baseAmount * 100) / quote.smsCount <= 38);
  }
});

test("list_packages returns the full server-quoted price list to a tenant account", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  const result = await handler({ auth: { uid: "owner" }, data: { action: "list_packages", operationId: "packages-list-00001" } });
  assert.equal(result.chargePercent, 1.8);
  assert.equal(result.packages.length, 7);
  assert.equal(result.packages[0].packageId, "starter");
  assert.equal(result.packages[6].payableAmount, 10180);
});

test("submit_recharge_request is owner-only, server-quoted, and idempotent", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  const request = {
    auth: { uid: "owner" },
    data: {
      action: "submit_recharge_request",
      operationId: "recharge-submit-0001",
      packageId: "starter",
      paymentMethod: "bkash",
      senderPhone: "01711111111",
    },
  };

  const first = await handler(request);
  assert.equal(first.request.status, "pending");
  assert.equal(first.request.payableAmount, 101.8);
  assert.equal(first.request.smsCount, 265);
  assert.equal(first.request.paymentMethod, "bkash");

  const stored = db.documents.get("institutes/i/sms_recharge_requests/recharge-submit-0001");
  assert.equal(stored.status, "pending");
  assert.equal(stored.instituteId, "i");
  assert.equal(stored.baseAmount, 100);

  const replay = await handler(request);
  assert.deepEqual(replay, first);
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/sms_recharge_requests/")).length, 1);

  await assert.rejects(
    handler({ auth: { uid: "staff" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-0002", packageId: "basic", paymentMethod: "nagad", senderPhone: "01811111111" } }),
    /owner/i,
  );
  await assert.rejects(
    handler({ auth: { uid: "owner" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-0003", packageId: "unknown", paymentMethod: "bkash", senderPhone: "01711111111" } }),
    /unknown SMS package/i,
  );
});

test("review approval credits the wallet once, writes audit and activity, and replays safely", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({ auth: { uid: "owner" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-0001", packageId: "starter", paymentMethod: "bkash", senderPhone: "01711111111" } });

  const review = {
    auth: { uid: "root" },
    data: { action: "review_recharge_request", operationId: "review-approve-0001", instituteId: "i", requestId: "recharge-submit-0001", decision: "approve", receivedAmount: 101.8, note: "" },
  };
  const approved = await handler(review);
  assert.equal(approved.request.status, "approved");

  const institute = db.documents.get("institutes/i");
  assert.equal(institute.sms_balance, 265);
  assert.equal(institute.total_sms_purchased, 265);

  const audit = db.documents.get("institutes/i/sms_wallet_audit/review-approve-0001");
  assert.equal(audit.action, "recharge_approved");
  assert.equal(audit.smsCount, 265);
  assert.equal(audit.payableAmount, 101.8);

  const activity = db.documents.get("institutes/i/platform_activity_events/review-approve-0001");
  assert.equal(activity.action, "sms_recharge_approved");
  assert.equal(activity.summary.includes("265 SMS"), true);

  // Replaying the same review must not double-credit the wallet.
  await handler(review);
  assert.equal(db.documents.get("institutes/i").sms_balance, 265);
  assert.equal(db.documents.get("institutes/i").total_sms_purchased, 265);
});

test("a rate-card update never invalidates an already submitted server quote", async () => {
  const db = seededDb();
  db.documents.set("institutes/i/sms_recharge_requests/legacy-pending-quote", {
    instituteId: "i",
    status: "pending",
    packageId: "starter",
    packageName: "Starter",
    layer: "Small & Medium Batches",
    // This was the previous server-issued quote. Today's Starter package has
    // a different SMS count, but this owner must still receive the quote they paid for.
    smsCount: 275,
    requestedSmsCount: 275,
    creditedSmsCount: 0,
    baseAmount: 100,
    chargePercent: 1.8,
    chargeAmount: 2,
    payableAmount: 102,
    paymentMethod: "bkash",
    senderPhone: "01711111111",
    createdAtMs: 100,
  });

  const result = await handlerFor(db)({
    auth: { uid: "root" },
    data: {
      action: "review_recharge_request",
      operationId: "approve-legacy-quote-001",
      instituteId: "i",
      requestId: "legacy-pending-quote",
      decision: "approve",
      receivedAmount: 102,
    },
  });

  assert.equal(result.request.status, "approved");
  assert.equal(result.request.creditedSmsCount, 275);
  assert.equal(db.documents.get("institutes/i").sms_balance, 275);
});

test("billing role can review but support cannot", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({ auth: { uid: "owner" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-0001", packageId: "basic", paymentMethod: "nagad", senderPhone: "01811111111" } });

  const billingReview = {
    auth: { uid: "billing" },
    data: { action: "review_recharge_request", operationId: "review-billing-0001", instituteId: "i", requestId: "recharge-submit-0001", decision: "approve", receivedAmount: 203.6, note: "" },
  };
  const result = await handler(billingReview);
  assert.equal(result.request.status, "approved");
  assert.equal(db.documents.get("institutes/i").sms_balance, 540);

  await assert.rejects(
    handler({ auth: { uid: "support" }, data: { action: "review_recharge_request", operationId: "review-support-0001", instituteId: "i", requestId: "recharge-submit-0001", decision: "reject", note: "" } }),
    /platform access/i,
  );
});

test("partial approval credits only the server-calculated portion of a short payment", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({ auth: { uid: "owner" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-partial", packageId: "standard", paymentMethod: "bkash", senderPhone: "01711111111" } });

  await assert.rejects(
    handler({ auth: { uid: "root" }, data: { action: "review_recharge_request", operationId: "review-underpaid-full", instituteId: "i", requestId: "recharge-submit-partial", decision: "approve", receivedAmount: 450 } }),
    /below the quoted amount/i,
  );

  const result = await handler({
    auth: { uid: "root" },
    data: {
      action: "review_recharge_request", operationId: "review-partial-0001", instituteId: "i",
      requestId: "recharge-submit-partial", decision: "approve_partial", receivedAmount: 450,
      note: "Verified partial payment",
    },
  });

  // Standard quote is BDT 509 for 1,385 credits: floor(450 / 509 * 1385) = 1,224.
  assert.equal(result.request.status, "approved");
  assert.equal(result.request.requestedSmsCount, 1385);
  assert.equal(result.request.creditedSmsCount, 1224);
  assert.equal(result.request.smsCount, 1224);
  assert.equal(db.documents.get("institutes/i").sms_balance, 1224);
  const stored = db.documents.get("institutes/i/sms_recharge_requests/recharge-submit-partial");
  assert.equal(stored.receivedAmount, 450);
  assert.equal(stored.creditedSmsCount, 1224);

});

test("rejection never credits the wallet and writes an activity event", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({ auth: { uid: "owner" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-0001", packageId: "starter", paymentMethod: "bkash", senderPhone: "01711111111" } });

  const rejected = await handler({
    auth: { uid: "root" },
    data: { action: "review_recharge_request", operationId: "review-reject-0001", instituteId: "i", requestId: "recharge-submit-0001", decision: "reject", note: "Payment not received" },
  });
  assert.equal(rejected.request.status, "rejected");
  assert.equal(rejected.request.reviewerNote, "Payment not received");
  assert.equal(db.documents.get("institutes/i").sms_balance ?? 0, 0);
  const activity = db.documents.get("institutes/i/platform_activity_events/review-reject-0001");
  assert.equal(activity.action, "sms_recharge_rejected");
});

test("sms_accounting aggregates collected, credited, used, cost, and profit", async () => {
  const db = seededDb();
  const handler = handlerFor(db);

  db.documents.set("institutes/i/sms_recharge_requests/req-a", {
    instituteId: "i", status: "approved", payableAmount: 102, smsCount: 275, packageId: "starter", createdAtMs: 1,
  });
  db.documents.set("institutes/j/sms_recharge_requests/req-b", {
    instituteId: "j", status: "approved", payableAmount: 509, smsCount: 1450, packageId: "standard", createdAtMs: 2,
  });
  db.documents.set("institutes/j/sms_recharge_requests/req-c", {
    instituteId: "j", status: "pending", payableAmount: 204, smsCount: 560, packageId: "basic", createdAtMs: 3,
  });
  db.documents.set("institutes/i", { ...db.documents.get("institutes/i"), total_sms_used: 100, sms_balance: 50 });
  db.documents.set("institutes/j", { ...db.documents.get("institutes/j"), total_sms_used: 25, sms_balance: 10 });

  const accounting = await handler({ auth: { uid: "root" }, data: { action: "sms_accounting", operationId: "accounting-0000001" } });
  assert.equal(accounting.rechargeRequestCount, 2);
  assert.equal(accounting.pendingRequestCount, 1);
  assert.equal(accounting.totalCollectedTaka, 611);
  assert.equal(accounting.totalCreditedSms, 1725);
  assert.equal(accounting.totalUsedSms, 125);
  assert.equal(accounting.outstandingBalance, 60);
  assert.equal(accounting.totalCostTaka, 439.88);
  assert.equal(accounting.profitTaka, 171.12);

  await assert.rejects(
    handler({ auth: { uid: "billing" }, data: { action: "sms_accounting", operationId: "accounting-0000002" } }),
    /platform access/i,
  );
});

test("platform SMS analytics reconciles DLR and separates owner liability from Zend balance", async () => {
  const db = seededDb();
  const now = Date.UTC(2026, 8, 15, 4, 0, 0);
  const keys = dhakaUsageKeys(now);
  db.documents.set("institutes/i", {
    ...db.documents.get("institutes/i"),
    sms_balance: 5, total_sms_purchased: 13, total_sms_used: 8, sms_used_today: 2, sms_usage_day_key: keys.dayKey,
    sms_used_this_month: 2, sms_usage_month_key: keys.monthKey,
  });
  db.documents.set("institutes/j", {
    ...db.documents.get("institutes/j"),
    sms_balance: 3, total_sms_purchased: 7, total_sms_used: 4, sms_used_today: 1, sms_usage_day_key: keys.dayKey,
    sms_used_this_month: 1, sms_usage_month_key: keys.monthKey,
  });
  db.documents.set("institutes/i/sms_recharge_requests/paid-a", {
    instituteId: "i", status: "approved", creditedSmsCount: 6, receivedAmount: 3.5,
  });
  db.documents.set("institutes/j/sms_recharge_requests/paid-b", {
    instituteId: "j", status: "approved", creditedSmsCount: 4, receivedAmount: 2.5,
  });
  db.documents.set("institutes/i/sms_messages/pending-a", {
    instituteId: "i", channel: "server", status: "pending", credits: 2,
    providerMessageId: "11111111-1111-1111-1111-111111111111", createdAtMs: now,
  });
  db.documents.set("institutes/j/sms_messages/delivered-b", {
    instituteId: "j", channel: "server", status: "delivered", credits: 1, createdAtMs: now,
  });
  db.documents.set("institutes/j/sms_messages/failed-c", {
    instituteId: "j", channel: "server", status: "failed", credits: 1, createdAtMs: now,
  });

  const analytics = await platformSmsAnalytics({
    db,
    request: { auth: { uid: "root" } },
    now,
    smsProvider: {
      getBalance: async () => ({ balance: 1.25, currency: "BDT" }),
      getDeliveryStatus: async () => ({ status: "DELIVERED", deliveredAt: "2026-09-15T04:01:00Z" }),
    },
  });

  assert.equal(analytics.todaySms, 3);
  assert.equal(analytics.weekSms, 4);
  assert.equal(analytics.monthSms, 3);
  assert.equal(analytics.lifetimeSms, 12);
  assert.equal(analytics.delivered, 3);
  assert.equal(analytics.failed, 1);
  assert.equal(analytics.pending, 0);
  assert.equal(analytics.soldSms, 10);
  assert.equal(analytics.collectedTaka, 6);
  assert.equal(analytics.buyerInstituteCount, 2);
  assert.equal(analytics.outstandingSms, 8);
  assert.equal(analytics.providerBalanceBdt, 1.25);
  assert.equal(analytics.centralCapacitySms, 5);
  assert.equal(analytics.reorderSms, 3);
  assert.equal(analytics.reorderAmountBdt, 0.77);
  assert.equal(analytics.financials.lifetime.providerEstimatedCostBdt, 2.55);
  assert.equal(analytics.financials.lifetime.grossProfitBdt, 3.45);
  assert.deepEqual(analytics.dlrSync, { attempted: 1, updated: 1 });
  assert.equal(db.documents.get("institutes/i/sms_messages/pending-a").status, "delivered");
  assert.equal(analytics.institutes.find((row) => row.instituteId === "i").lifetimeSms, 8);
  assert.equal(analytics.institutes.find((row) => row.instituteId === "i").totalSmsPurchased, 13);
  assert.equal(analytics.institutes.find((row) => row.instituteId === "j").walletBalance, 3);
  assert.equal(analytics.recentMessages.length, 3);
  assert.equal(analytics.recentMessages.find((row) => row.messageId === "pending-a").status, "delivered");

  await assert.rejects(
    platformSmsAnalytics({ db, request: { auth: { uid: "billing" } }, now, smsProvider: {} }),
    /platform access/i,
  );
});

test("institute History refresh reconciles only that institute's pending DLR rows", async () => {
  const db = seededDb();
  const now = Date.UTC(2026, 8, 16, 1, 0, 0);
  db.documents.set("institutes/i/sms_messages/pending-owner", {
    instituteId: "i", channel: "server", status: "pending", credits: 1,
    providerMessageId: "11111111-1111-1111-1111-111111111111", createdAtMs: now,
  });
  db.documents.set("institutes/j/sms_messages/pending-other", {
    instituteId: "j", channel: "server", status: "pending", credits: 1,
    providerMessageId: "22222222-2222-2222-2222-222222222222", createdAtMs: now,
  });
  const handler = tenantDlrHandlerFor(db, {
    getDeliveryStatus: async () => ({ status: "DELIVERED", deliveredAt: "2026-09-16T01:02:00Z" }),
  });

  const result = await handler({ auth: { uid: "owner" }, data: {} });
  assert.deepEqual(result, { attempted: 1, updated: 1 });
  assert.equal(db.documents.get("institutes/i/sms_messages/pending-owner").status, "delivered");
  assert.equal(db.documents.get("institutes/j/sms_messages/pending-other").status, "pending");
});

test("the existing report action refreshes DLR for older app builds", async () => {
  const db = seededDb();
  const now = Date.UTC(2026, 8, 16, 2, 0, 0);
  db.documents.set("institutes/i/sms_messages/pending-report", {
    instituteId: "i", channel: "server", status: "pending", credits: 1,
    providerMessageId: "33333333-3333-3333-3333-333333333333", createdAtMs: now,
  });
  const handler = handlerFor(db, {
    getDeliveryStatus: async () => ({ status: "DELIVERED", deliveredAt: "2026-09-16T02:01:00Z" }),
  });

  const report = await handler({
    auth: { uid: "owner" },
    data: { action: "list_sms_report", operationId: "refresh-history-00001" },
  });
  assert.equal(report.counts.delivered, 1);
  assert.equal(report.counts.pending, 0);
  assert.equal(db.documents.get("institutes/i/sms_messages/pending-report").status, "delivered");
});

test("root records an immutable, idempotent central SMS top-up ledger", async () => {
  const db = seededDb();
  const request = {
    auth: { uid: "root" },
    data: {
      operationId: "central-topup-000001",
      supplier: "ZendSMS",
      paidAmountBdt: 2500,
      purchasedSms: 10000,
      reference: "TXN-1234",
      note: "September stock",
    },
  };
  const first = await recordPlatformSmsTopup({ db, request, now: 1000 });
  assert.equal(first.replayed, false);
  assert.equal(first.topup.purchasedSms, 10000);
  assert.equal(first.topup.paidAmountBdt, 2500);
  const replay = await recordPlatformSmsTopup({ db, request, now: 2000 });
  assert.equal(replay.replayed, true);
  assert.equal(replay.topup.recordedAtMs, 1000);
  assert.equal([...db.documents.keys()].filter((path) => path.startsWith("platform_sms_topups/")).length, 1);

  await assert.rejects(
    recordPlatformSmsTopup({
      db,
      request: { ...request, data: { ...request.data, paidAmountBdt: 2400 } },
      now: 3000,
    }),
    /already used with different data/i,
  );
  await assert.rejects(
    recordPlatformSmsTopup({ db, request: { ...request, auth: { uid: "billing" } }, now: 3000 }),
    /platform access/i,
  );

  const report = await platformSmsAnalytics({
    db,
    request: { auth: { uid: "root" } },
    now: 4000,
    smsProvider: { getBalance: async () => ({ balance: 0, currency: "BDT" }) },
  });
  assert.equal(report.centralTopupCount, 1);
  assert.equal(report.centralTopupPaidBdt, 2500);
  assert.equal(report.centralTopupSms, 10000);
  assert.equal(report.centralTopups[0].supplier, "ZendSMS");
});

test("list_my_recharge_requests returns only the institute's own history", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({ auth: { uid: "owner" }, data: { action: "submit_recharge_request", operationId: "recharge-submit-0001", packageId: "starter", paymentMethod: "bkash", senderPhone: "01711111111" } });
  db.documents.set("institutes/j/sms_recharge_requests/other-req", {
    instituteId: "j", status: "pending", payableAmount: 204, smsCount: 560, packageId: "basic", createdAtMs: 9,
  });

  const mine = await handler({ auth: { uid: "owner" }, data: { action: "list_my_recharge_requests", operationId: "my-requests-00001" } });
  assert.equal(mine.requests.length, 1);
  assert.equal(mine.requests[0].requestId, "recharge-submit-0001");
});

test("record_sms_batch stores carrier sends as sent and replays without duplicates", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  const request = {
    auth: { uid: "owner" },
    data: {
      action: "record_sms_batch",
      operationId: "sms-batch-0000001",
      channel: "carrier",
      messages: [
        { recipient: "01711111111", purpose: "Absent message · Rahim" },
        { recipient: "01811111111", purpose: "Due fee reminder · Karim · Jun 2026" },
      ],
    },
  };

  const first = await handler(request);
  assert.deepEqual(first, { recorded: 2, replayed: false });
  assert.equal(db.documents.get("institutes/i/sms_messages/sms-batch-0000001-0001").status, "sent");
  assert.equal(db.documents.get("institutes/i/sms_messages/sms-batch-0000001-0002").channel, "carrier");

  const replay = await handler(request);
  assert.deepEqual(replay, { recorded: 0, replayed: true });
  assert.equal([...db.documents.keys()].filter((key) => key.includes("/sms_messages/")).length, 2);

  await assert.rejects(
    handler({ auth: { uid: "owner" }, data: { action: "record_sms_batch", operationId: "sms-batch-0000002", channel: "email", messages: [{ recipient: "01711111111" }] } }),
    /carrier or server/i,
  );
});

test("staff can record and read the SMS report but never mark delivery", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({
    auth: { uid: "staff" },
    data: {
      action: "record_sms_batch", operationId: "sms-batch-0000003", channel: "carrier",
      messages: [{ recipient: "01711111111", purpose: "Bulk message", messageBody: "Dear guardian, this is a test." }],
    },
  });

  const report = await handler({ auth: { uid: "owner" }, data: { action: "list_sms_report", operationId: "report-000000001" } });
  assert.equal(report.counts.sent, 1);
  assert.equal(report.counts.delivered, 0);
  assert.equal(report.messages.length, 1);
  assert.equal(report.messages[0].purpose, "Bulk message");
  assert.equal(report.messages[0].messageBody, "Dear guardian, this is a test.");
  assert.equal(report.messages[0].credits, 0);

  // No tenant action can change a message status; only the trusted server helper can.
  await assert.rejects(
    handler({ auth: { uid: "owner" }, data: { action: "mark_sms_delivered", operationId: "deliver-000000001" } }),
    /invalid SMS wallet operation/i,
  );
});

test("SMS history keeps full body, period totals, failures, and tenant isolation", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  const now = Date.now();
  db.documents.set("institutes/i/sms_messages/history-now-delivered", {
    instituteId: "i", recipient: "8801711111111", purpose: "Attendance", messageBody: "Rahim is present today.",
    channel: "server", status: "delivered", credits: 2, createdAtMs: now, deliveredAtMs: now,
  });
  db.documents.set("institutes/i/sms_messages/history-now-failed", {
    instituteId: "i", recipient: "8801811111111", purpose: "Due reminder", messageBody: "BDT 500 due.",
    channel: "server", status: "failed", credits: 1, failureReason: "Provider rejected the destination.", createdAtMs: now,
  });
  db.documents.set("institutes/i/sms_messages/history-week-sent", {
    instituteId: "i", recipient: "01711111111", purpose: "Receipt", messageBody: "Payment receipt.",
    channel: "carrier", status: "sent", credits: 0, createdAtMs: now - 24 * 60 * 60 * 1000,
  });
  db.documents.set("institutes/j/sms_messages/other-tenant", {
    instituteId: "j", recipient: "8801999999999", purpose: "Private", messageBody: "Must never leak.",
    channel: "server", status: "delivered", credits: 1, createdAtMs: now,
  });

  const report = await handler({ auth: { uid: "owner" }, data: { action: "list_sms_report", operationId: "history-report-00001" } });
  assert.equal(report.periods.today.total, 2);
  assert.equal(report.periods.today.credits, 3);
  assert.equal(report.periods.today.delivered, 1);
  assert.equal(report.periods.today.failed, 1);
  assert.equal(report.periods.lifetime.total, 3);
  assert.equal(report.periods.lifetime.credits, 3);
  assert.equal(report.messages.some((message) => message.messageBody === "Rahim is present today."), true);
  assert.equal(report.messages.some((message) => message.recipient === "8801999999999"), false);
});

test("updateSmsMessageStatus transitions a server message through gateway callbacks", async () => {
  const db = seededDb();
  const handler = handlerFor(db);
  await handler({
    auth: { uid: "owner" },
    data: { action: "record_sms_batch", operationId: "sms-batch-0000004", channel: "server", messages: [{ recipient: "01711111111", purpose: "Server message" }] },
  });
  assert.equal(db.documents.get("institutes/i/sms_messages/sms-batch-0000004-0001").status, "pending");

  await updateSmsMessageStatus(db, "i", "sms-batch-0000004-0001", "sent", { now: 1000 });
  assert.equal(db.documents.get("institutes/i/sms_messages/sms-batch-0000004-0001").status, "sent");

  await updateSmsMessageStatus(db, "i", "sms-batch-0000004-0001", "delivered", { now: 2000 });
  const delivered = db.documents.get("institutes/i/sms_messages/sms-batch-0000004-0001");
  assert.equal(delivered.status, "delivered");
  assert.equal(delivered.deliveredAtMs, 2000);

  await assert.rejects(updateSmsMessageStatus(db, "i", "sms-batch-0000004-0001", "lost"), /invalid SMS message status/i);
});

test("SMS credit accounting distinguishes English and Bengali segments", () => {
  assert.equal(smsCreditCount("A".repeat(160)), 1);
  assert.equal(smsCreditCount("A".repeat(161)), 2);
  assert.equal(smsCreditCount("আ".repeat(70)), 1);
  assert.equal(smsCreditCount("আ".repeat(71)), 2);
});

test("server SMS reserves wallet credits, sends once, and replays without duplication", async () => {
  const db = seededDb();
  db.documents.set("institutes/i", {
    ...db.documents.get("institutes/i"),
    sms_balance: 5,
    total_sms_purchased: 5,
    total_sms_used: 0,
    sms_send_method: "server",
  });
  let providerCalls = 0;
  const handler = serverHandlerFor(db, {
    sendTextSms: async () => {
      providerCalls += 1;
      return { accepted: true, status: "sent", providerStatus: "1000", messageId: `p-${providerCalls}` };
    },
  });
  const request = {
    auth: { uid: "owner" },
    data: {
      operationId: "server-send-batch-0001",
      messages: [
        { targetKey: "s1", recipient: "01712345678", message: "Fee reminder", purpose: "due_fee" },
        { targetKey: "s2", recipient: "01812345678", message: "অনুপস্থিতির বার্তা", purpose: "attendance" },
      ],
    },
  };

  const sent = await handler(request);
  assert.equal(sent.replayed, false);
  assert.equal(sent.results.length, 2);
  assert.equal(sent.results.every((item) => item.status === "sent"), true);
  assert.equal(sent.wallet.smsBalance, 3);
  assert.equal(sent.wallet.totalSmsUsed, 2);
  assert.equal(sent.wallet.smsUsedToday, 2);
  assert.equal(sent.wallet.smsUsedThisMonth, 2);
  assert.equal(providerCalls, 2);

  const replay = await handler(request);
  assert.equal(replay.replayed, true);
  assert.equal(replay.wallet.smsBalance, 3);
  assert.equal(providerCalls, 2);
});

test("definite provider rejection refunds reserved SMS credits", async () => {
  const db = seededDb();
  db.documents.set("institutes/i", {
    ...db.documents.get("institutes/i"), sms_balance: 2, total_sms_used: 0, sms_send_method: "server",
  });
  const handler = serverHandlerFor(db, {
    sendTextSms: async () => ({
      accepted: false, status: "failed", providerStatus: "2001", failureReason: "Provider balance is insufficient.",
    }),
  });
  const result = await handler({
    auth: { uid: "owner" },
    data: { operationId: "server-send-refund-01", messages: [{ targetKey: "s1", recipient: "01712345678", message: "Hello" }] },
  });
  assert.equal(result.results[0].status, "failed");
  assert.equal(result.wallet.smsBalance, 2);
  assert.equal(result.wallet.totalSmsUsed, 0);
  assert.equal(result.wallet.smsUsedToday, 0);
  assert.equal(result.wallet.smsUsedThisMonth, 0);
  assert.ok(db.documents.get(`institutes/i/sms_wallet_audit/${result.results[0].messageId}-refund`));
});

test("ambiguous provider timeout stays pending and cannot be resent by replay", async () => {
  const db = seededDb();
  db.documents.set("institutes/i", {
    ...db.documents.get("institutes/i"), sms_balance: 2, total_sms_used: 0, sms_send_method: "server",
  });
  let providerCalls = 0;
  const handler = serverHandlerFor(db, {
    sendTextSms: async () => {
      providerCalls += 1;
      throw Object.assign(new Error("Provider confirmation timed out."), { code: "TIMEOUT", ambiguous: true });
    },
  });
  const request = {
    auth: { uid: "owner" },
    data: { operationId: "server-send-timeout-01", messages: [{ targetKey: "s1", recipient: "01712345678", message: "Hello" }] },
  };
  const pending = await handler(request);
  assert.equal(pending.results[0].status, "pending");
  assert.equal(pending.wallet.smsBalance, 1);
  await handler(request);
  assert.equal(providerCalls, 1);
});

test("a lost provider settlement stays pending and replay never sends twice", async () => {
  const db = seededDb();
  db.documents.get("institutes/i").sms_send_method = "server";
  db.documents.get("institutes/i").sms_balance = 2;
  let providerCalls = 0;
  const handler = serverHandlerFor(db, {
    sendTextSms: async () => {
      providerCalls += 1;
      return { accepted: true, status: "sent", providerStatus: "1000", messageId: "provider-settlement" };
    },
  });
  const originalRunTransaction = db.runTransaction.bind(db);
  let transactionCount = 0;
  db.runTransaction = async (callback) => {
    transactionCount += 1;
    if (transactionCount === 2) throw new Error("firestore acknowledgement unavailable");
    return originalRunTransaction(callback);
  };
  const request = {
    auth: { uid: "owner" },
    data: {
      operationId: "server-send-settlement-001",
      messages: [{ recipient: "01712345678", message: "Fee reminder", purpose: "due", targetKey: "s1" }],
    },
  };

  const first = await handler(request);
  assert.equal(first.results[0].status, "pending");
  assert.equal(first.results[0].providerStatus, "SETTLEMENT_PENDING");
  assert.equal(db.documents.get("institutes/i").sms_balance, 1);

  const replay = await handler(request);
  assert.equal(replay.replayed, true);
  assert.equal(replay.results[0].status, "pending");
  assert.equal(providerCalls, 1);
});

test("server SMS fails closed for insufficient balance and unauthorized staff", async () => {
  const db = seededDb();
  db.documents.set("institutes/i", {
    ...db.documents.get("institutes/i"), sms_balance: 0, total_sms_used: 0, sms_send_method: "server",
  });
  let providerCalls = 0;
  const handler = serverHandlerFor(db, { sendTextSms: async () => { providerCalls += 1; } });
  const data = {
    operationId: "server-send-denied-001",
    messages: [{ targetKey: "s1", recipient: "01712345678", message: "Hello" }],
  };
  await assert.rejects(handler({ auth: { uid: "owner" }, data }), /Insufficient SMS balance/i);
  await assert.rejects(handler({ auth: { uid: "staff" }, data: { ...data, operationId: "server-send-denied-002" } }), /permission/i);
  assert.equal(providerCalls, 0);

  db.documents.get("institutes/i/staffs/staff").permissions = "send_due_message";
  db.documents.get("institutes/i").sms_balance = 1;
  const allowed = serverHandlerFor(db, {
    sendTextSms: async () => ({ accepted: true, status: "sent", providerStatus: "1000", messageId: "p-staff" }),
  });
  const result = await allowed({ auth: { uid: "staff" }, data: { ...data, operationId: "server-send-staff-001" } });
  assert.equal(result.results[0].status, "sent");
});
