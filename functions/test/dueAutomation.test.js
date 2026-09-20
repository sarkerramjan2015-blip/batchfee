"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  policyDefaults,
  validatePolicyInput,
  triggerForDue,
  computeDueItems,
  computeMonthlyOutstandingItems,
  runDueAutomationSweep,
  estimatedDaysRemaining,
  buildDueMessage,
} = require("../src/dueAutomation");

// 2026-09-19 12:00 Asia/Dhaka (UTC+6) — a Friday within a default send window.
const NOW = Date.UTC(2026, 8, 19, 6, 0, 0);
const DAY_KEY = "2026-09-19";

function dhakaNoonPlusDays(days) {
  return NOW + days * 86400000;
}

function memoryDb() {
  const records = new Map();

  function collectionRef(path) {
    return {
      path,
      parent: path.includes("/") ? docRef(path.split("/").slice(0, -1).join("/")) : null,
      doc: (id) => docRef(`${path}/${id}`),
      where: (field, op, value) => query(path).where(field, op, value),
      orderBy: (field) => query(path).orderBy(field),
      limit: (value) => query(path).limit(value),
      get: async () => query(path).get(),
    };
  }

  function docRef(path) {
    return {
      path,
      id: path.split("/").at(-1) || "",
      parent: path.includes("/") ? collectionRef(path.split("/").slice(0, -1).join("/")) : null,
      collection: (name) => collectionRef(`${path}/${name}`),
      get: async () => snapshot(path),
      set: async (data, options) => {
        if (options && options.merge) records.set(path, { ...(records.get(path) || {}), ...data });
        else records.set(path, data);
      },
      create: async (data) => {
        if (records.has(path)) throw new Error(`Duplicate ${path}`);
        records.set(path, data);
      },
      update: async (data) => records.set(path, { ...(records.get(path) || {}), ...data }),
      delete: async () => { records.delete(path); },
    };
  }

  function snapshot(path) {
    return {
      id: path.split("/").at(-1) || "",
      exists: records.has(path),
      data: () => records.get(path),
      get: (field) => { const data = records.get(path); return data ? data[field] : undefined; },
      ref: docRef(path),
    };
  }

  function query(path) {
    const clauses = [];
    let order = null;
    let limitValue = null;
    const q = {
      where: (field, op, value) => { clauses.push([field, op, value]); return q; },
      orderBy: (field) => { order = field; return q; },
      limit: (value) => { limitValue = value; return q; },
      get: async () => {
        const depth = path.split("/").length;
        let docs = [...records.entries()]
          .filter(([recordPath]) => recordPath.startsWith(`${path}/`) && recordPath.split("/").length === depth + 1)
          .filter(([, data]) => clauses.every(([field, op, value]) => {
            if (op === "==") return data[field] === value;
            if (op === ">") return data[field] > value;
            if (op === ">=") return data[field] >= value;
            if (op === "<") return data[field] < value;
            if (op === "<=") return data[field] <= value;
            return true;
          }))
          .map(([recordPath, data]) => ({
            id: recordPath.split("/").at(-1),
            data: () => data,
            get: (field) => data[field],
            ref: docRef(recordPath),
          }));
        if (order) docs.sort((a, b) => (a.data()[order] || 0) - (b.data()[order] || 0));
        if (limitValue != null) docs = docs.slice(0, limitValue);
        return { size: docs.length, empty: docs.length === 0, docs };
      },
    };
    return q;
  }

  return {
    records,
    collection: (name) => collectionRef(name),
    runTransaction: async (work) => work({
      get: async (ref) => snapshot(ref.path),
      set: async (ref, data, options) => { await ref.set(data, options); },
      create: async (ref, data) => { await ref.create(data); },
      update: async (ref, data) => { await ref.update(data); },
      delete: async (ref) => { await ref.delete(); },
    }),
  };
}

function instituteDoc(overrides = {}) {
  return {
    instituteName: "Test Institute",
    phone: "01712345678",
    isActive: true,
    sms_balance: 100,
    total_sms_purchased: 100,
    total_sms_used: 0,
    sms_used_today: 0,
    sms_usage_day_key: "",
    sms_used_this_month: 0,
    sms_usage_month_key: "",
    sms_send_method: "server",
    ...overrides,
  };
}

function studentDoc(id, overrides = {}) {
  return {
    instituteId: "inst_1",
    studentCode: `S-${id}`,
    fullName: `Student ${id}`,
    phone: `0171${String(id).padStart(6, "0")}`,
    admissionDateMs: Date.UTC(2026, 6, 10, 6, 0),
    status: "active",
    archivedAtMs: null,
    ...overrides,
  };
}

function batchDoc(id, overrides = {}) {
  return {
    instituteId: "inst_1",
    name: `Batch ${id}`,
    monthlyFeeAmount: 1000,
    admissionFeeAmount: 0,
    courseFeeAmount: 0,
    billingMode: "monthly",
    startDateMs: null,
    status: "active",
    ...overrides,
  };
}

function enrollmentDoc(id, overrides = {}) {
  return {
    instituteId: "inst_1",
    batchId: "batch_1",
    studentId: id,
    joinedAtMs: Date.UTC(2026, 6, 10, 6, 0),
    status: "active",
    leftAtMs: null,
    firstMonthFeePeriod: null,
    firstMonthFeeAmount: null,
    ...overrides,
  };
}

function fakeProvider() {
  const calls = [];
  return {
    calls,
    sendTextSms: async ({ number, message }) => {
      calls.push({ number, message });
      return { accepted: true, status: "pending", providerStatus: "1000", messageId: `zend-${calls.length}` };
    },
  };
}

function seedInstitute(db, { students = ["1"], feeRows = [], institute = {}, batch = {}, enrollments = null, policy = {} } = {}) {
  db.records.set("institutes/inst_1", instituteDoc(institute));
  for (const id of students) {
    db.records.set(`institutes/inst_1/students/${id}`, studentDoc(id));
  }
  db.records.set("institutes/inst_1/batches/batch_1", batchDoc("batch_1", batch));
  const enrollmentList = enrollments ?? students.map((id) => enrollmentDoc(id));
  for (const entry of enrollmentList) {
    db.records.set(`institutes/inst_1/batch_students/${entry.studentId}`, entry);
  }
  for (const fee of feeRows) {
    db.records.set(`institutes/inst_1/fees/${fee.id}`, {
      instituteId: "inst_1",
      studentId: fee.studentId,
      batchId: fee.batchId || "batch_1",
      feePeriod: fee.feePeriod,
      feeType: fee.feeType,
      baseAmount: fee.baseAmount ?? fee.totalAmount ?? 0,
      discountAmount: 0,
      lateFeeAmount: 0,
      totalAmount: fee.totalAmount ?? 0,
      paidAmount: fee.paidAmount ?? 0,
      dueAmount: fee.dueAmount ?? (fee.totalAmount ?? 0) - (fee.paidAmount ?? 0),
      dueDateMs: fee.dueDateMs ?? 0,
      status: fee.status ?? "unpaid",
      cancelledAtMs: fee.cancelledAtMs ?? null,
    });
  }
  db.records.set("institutes/inst_1/due_automation/policy", {
    enabled: true,
    beforeDueDays: [7, 3, 1],
    sendOnDueDay: true,
    afterDueDays: [3, 7, 15],
    channels: ["sms"],
    feeTypes: [],
    excludedStudentIds: [],
    excludedBatchIds: [],
    sendWindowStartHour: 9,
    sendWindowEndHour: 19,
    dailySmsLimit: 0,
    templateId: "",
    lastRunDayKey: "",
    lastRunSentCount: 0,
    ...policy,
  });
}

// ---------------------------------------------------------------------------

test("triggerForDue escalates before/due/overdue cohorts", () => {
  const policy = policyDefaults({ beforeDueDays: [7, 3, 1], sendOnDueDay: true, afterDueDays: [3, 7, 15] });
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(7), policy, now: NOW }), "before_7d");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(6), policy, now: NOW }), "before_7d");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(2), policy, now: NOW }), "before_3d");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(1), policy, now: NOW }), "before_1d");
  assert.equal(triggerForDue({ dueDateMs: NOW, policy, now: NOW }), "due_today");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(-4), policy, now: NOW }), "overdue_3d");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(-10), policy, now: NOW }), "overdue_7d");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(-20), policy, now: NOW }), "overdue_15d");
  assert.equal(triggerForDue({ dueDateMs: dhakaNoonPlusDays(30), policy, now: NOW }), null);
  assert.equal(triggerForDue({ dueDateMs: 0, policy, now: NOW }), null);
});

test("validatePolicyInput builds a canonical policy and rejects bad input", () => {
  const saved = validatePolicyInput({
    enabled: true,
    beforeDueDays: [3, 7, 3],
    sendOnDueDay: false,
    afterDueDays: [],
    channels: ["sms", "whatsapp"],
    feeTypes: ["monthly_fee"],
    excludedStudentIds: ["s1", "s1"],
    excludedBatchIds: [],
    sendWindowStartHour: 9,
    sendWindowEndHour: 19,
    dailySmsLimit: 50,
    templateId: "",
  });
  assert.equal(saved.enabled, true);
  assert.deepEqual(saved.beforeDueDays.sort((a, b) => a - b), [3, 7]);
  assert.equal(saved.sendOnDueDay, false);
  assert.deepEqual(saved.afterDueDays, []);
  assert.deepEqual(saved.channels, ["sms", "whatsapp"]);
  assert.deepEqual(saved.excludedStudentIds, ["s1"]);
  assert.equal(saved.dailySmsLimit, 50);

  const valid = {
    enabled: true,
    channels: ["sms"],
    beforeDueDays: [7],
    sendOnDueDay: true,
    afterDueDays: [3],
    sendWindowStartHour: 9,
    sendWindowEndHour: 19,
    dailySmsLimit: 0,
    templateId: "",
  };
  assert.throws(() => validatePolicyInput({ ...valid, channels: [] }), /channel/i);
  assert.throws(() => validatePolicyInput({ ...valid, sendWindowStartHour: 19, sendWindowEndHour: 9 }), /window/i);
  assert.throws(() => validatePolicyInput({ ...valid, dailySmsLimit: -1 }), /daily/i);
  assert.throws(() => validatePolicyInput({ ...valid, beforeDueDays: [0] }), /whole numbers/i);
});

test("computeMonthlyOutstandingItems ports the virtual monthly due calculator", () => {
  const items = computeMonthlyOutstandingItems({
    billingStartMs: Date.UTC(2026, 6, 10, 6, 0),
    monthlyFeeAmount: 1000,
    existingMonthlyFees: [],
    firstMonthFeePeriod: null,
    firstMonthFeeAmount: null,
    customMonthlyFeeAmount: null,
    customFeeEffectiveFromPeriod: null,
    customFeePolicyTimeline: null,
    billingEndedAtMs: null,
    now: NOW,
  });
  assert.deepEqual(items.map((item) => item.period), ["Jul 2026", "Aug 2026"]);
  assert.equal(items[0].amount, 700);
  assert.equal(items[1].amount, 1000);
});

test("computeDueItems mixes materialized one-time fees and virtual monthly dues", () => {
  const db = memoryDb();
  seedInstitute(db, {
    feeRows: [
      { id: "fee_1", studentId: "1", feePeriod: "Admission", feeType: "admission_fee", totalAmount: 500, dueDateMs: dhakaNoonPlusDays(-2) },
    ],
    batch: { monthlyFeeAmount: 1000, admissionFeeAmount: 0 },
  });
  const fees = [{ id: "fee_1", studentId: "1", batchId: "batch_1", feePeriod: "Admission", feeType: "admission_fee", dueAmount: 500, dueDateMs: dhakaNoonPlusDays(-2), cancelledAtMs: null }];
  const items = computeDueItems({
    fees,
    studentsById: { 1: { id: "1", ...studentDoc("1") } },
    batchesById: { batch_1: batchDoc("batch_1", { monthlyFeeAmount: 1000, admissionFeeAmount: 0 }) },
    enrollmentsByStudent: { 1: [enrollmentDoc("1")] },
    policy: policyDefaults({}),
    now: NOW,
  });
  const admission = items.find((item) => item.feePeriods[0] === "Admission");
  assert.ok(admission, "admission fee row must be due");
  assert.equal(admission.dueAmount, 500);
  const virtual = items.filter((item) => item.kind === "monthly");
  assert.deepEqual(virtual.map((item) => item.feePeriods[0]), ["Jul 2026", "Aug 2026"]);
  assert.equal(virtual[0].dueAmount, 700);
  assert.equal(virtual[1].dueAmount, 1000);
});

test("sweep sends credit-checked reminders, debits the wallet and dedupes the next run", async () => {
  const db = memoryDb();
  seedInstitute(db, {});
  const provider = fakeProvider();
  const result = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW });
  assert.equal(result.sent, 2, "Jul + Aug virtual months must both send");
  assert.equal(provider.calls.length, 2);
  const reminders = [...db.records.entries()]
    .filter(([path]) => path.startsWith("institutes/inst_1/due_reminders/"))
    .map(([, data]) => data);
  const spent = reminders.reduce((sum, row) => sum + (row.credits || 0), 0);
  assert.equal(reminders.length, 2);
  const institute = db.records.get("institutes/inst_1");
  assert.equal(institute.sms_balance, 100 - spent);
  assert.equal(institute.total_sms_used, spent);
  assert.ok(reminders.every((row) => row.status === "sent" && row.runDayKey === DAY_KEY));
  assert.ok(reminders.every((row) => row.message.includes("Student")));

  const policy = db.records.get("institutes/inst_1/due_automation/policy");
  assert.equal(policy.lastRunDayKey, DAY_KEY);
  assert.equal(policy.lastRunSentCount, 2);

  const second = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW + 60 * 60 * 1000 });
  assert.equal(second.sent, 0);
  assert.equal(provider.calls.length, 2, "second run must not re-send");
});

test("insufficient credit records skipped and retries after a top-up", async () => {
  const db = memoryDb();
  seedInstitute(db, { institute: { sms_balance: 0 } });
  const provider = fakeProvider();
  const first = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW });
  assert.equal(first.sent, 0);
  assert.equal(first.skipped, 2);
  assert.equal(first.skippedReasons.insufficient_credit, 2);
  assert.equal(provider.calls.length, 0);

  const skippedRows = [...db.records.entries()]
    .filter(([path]) => path.startsWith("institutes/inst_1/due_reminders/"))
    .map(([, data]) => data);
  assert.ok(skippedRows.every((row) => row.status === "skipped" && row.skipReason === "insufficient_credit"));

  db.records.get("institutes/inst_1").sms_balance = 100;
  const second = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW + 3600 * 1000 });
  assert.equal(second.sent, 2, "retry after top-up must send");
  assert.equal(provider.calls.length, 2);
});

test("daily SMS limit stops the queue with a skipped row", async () => {
  const db = memoryDb();
  seedInstitute(db, { policy: { dailySmsLimit: 1 } });
  const provider = fakeProvider();
  const result = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW });
  assert.equal(result.sent, 1);
  assert.equal(result.skipped, 1);
  assert.equal(result.skippedReasons.daily_limit_reached, 1);
  assert.equal(provider.calls.length, 1);
});

test("send window is enforced", async () => {
  const db = memoryDb();
  seedInstitute(db, {});
  const provider = fakeProvider();
  const outside = await runDueAutomationSweep({ db, smsProvider: provider, now: Date.UTC(2026, 8, 19, 14, 0, 0) });
  assert.equal(outside.sent, 0);
  assert.equal(provider.calls.length, 0);
});

test("excluded students and fee types are skipped silently", async () => {
  const db = memoryDb();
  seedInstitute(db, {
    students: ["1", "2"],
    policy: { excludedStudentIds: ["2"], feeTypes: ["monthly_fee"] },
    feeRows: [
      { id: "fee_adm_1", studentId: "1", feePeriod: "Admission", feeType: "admission_fee", totalAmount: 500, dueDateMs: dhakaNoonPlusDays(-2) },
    ],
  });
  const provider = fakeProvider();
  const result = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW });
  assert.equal(result.sent, 2, "student 1 virtual months; admission excluded by feeTypes; student 2 excluded");
  const recipients = provider.calls.map((call) => call.number);
  assert.ok(recipients.every((number) => number === "0171000001"));
});

test("whatsapp-only channels record honest skips without provider calls", async () => {
  const db = memoryDb();
  seedInstitute(db, { policy: { channels: ["whatsapp"] } });
  const provider = fakeProvider();
  const result = await runDueAutomationSweep({ db, smsProvider: provider, now: NOW });
  assert.equal(result.sent, 0);
  assert.equal(result.skipped, 2);
  assert.equal(result.skippedReasons.whatsapp_provider_unavailable, 2);
  assert.equal(provider.calls.length, 0);
});

test("estimatedDaysRemaining uses the monthly burn rate", () => {
  const wallet = {
    sms_balance: 100,
    sms_used_this_month: 50,
    total_sms_used: 50,
    sms_used_today: 0,
    sms_usage_day_key: DAY_KEY,
    sms_usage_month_key: "2026-09",
  };
  assert.equal(estimatedDaysRemaining({ wallet, institute: {}, now: NOW }), 38);
  assert.equal(estimatedDaysRemaining({ wallet: { ...wallet, sms_balance: 0 }, institute: {}, now: NOW }), 0);
});

test("buildDueMessage applies placeholders and drops empty contact lines", () => {
  const message = buildDueMessage({
    template: "Dear {guardianName},\n\n{studentName}: BDT {amount} for {period}.\n\n- {instituteName}\nContact: {instituteContact}",
    item: { studentName: "Rahim", dueAmount: 1200.5, feePeriods: ["Jul 2026"] },
    institute: { instituteName: "Sunrise Coaching", phone: "" },
    now: NOW,
  });
  assert.ok(message.includes("Dear Guardian"));
  assert.ok(message.includes("Rahim: BDT 1200.50 for Jul 2026"));
  assert.ok(message.includes("Sunrise Coaching"));
  assert.ok(!message.includes("Contact:"), "empty contact line must be dropped");
});

test("applyTemplate keeps placeholder substitution stable for multi-period labels", () => {
  const { applyTemplate } = require("../src/dueAutomation");
  const out = applyTemplate("{studentName} owes for {period}.", {
    studentName: "Rahim",
    period: "Jul 2026, Aug 2026",
  });
  assert.equal(out, "Rahim owes for Jul 2026, Aug 2026.");
});
