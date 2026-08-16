"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { createExamFeeBillingHandler } = require("../src/examFeeBilling");

class FakeSnapshot {
  constructor(ref, data) {
    this.ref = ref;
    this.id = ref.path.split("/").at(-1);
    this.exists = data !== undefined;
    this._data = data === undefined ? undefined : structuredClone(data);
  }
  data() { return this._data === undefined ? undefined : structuredClone(this._data); }
  get(field) { return this._data && this._data[field]; }
}

class FakeQuery {
  constructor(db, path, filters = []) { this.db = db; this.path = path; this.filters = filters; }
  where(field, operator, value) { return new FakeQuery(this.db, this.path, [...this.filters, [field, operator, value]]); }
  snapshots() {
    const depth = this.path.split("/").length + 1;
    return [...this.db.documents.entries()]
      .filter(([path]) => path.startsWith(`${this.path}/`) && path.split("/").length === depth)
      .filter(([, data]) => this.filters.every(([field, operator, value]) => operator === "==" && data[field] === value))
      .map(([path, data]) => new FakeSnapshot(new FakeDocument(this.db, path), data));
  }
}

class FakeCollection extends FakeQuery {
  doc(id) { return new FakeDocument(this.db, `${this.path}/${id}`); }
}

class FakeDocument {
  constructor(db, path) { this.db = db; this.path = path; }
  collection(name) { return new FakeCollection(this.db, `${this.path}/${name}`); }
}

class FakeDb {
  constructor(seed) { this.documents = new Map(Object.entries(seed).map(([path, data]) => [path, structuredClone(data)])); }
  collection(name) { return new FakeCollection(this, name); }
  async runTransaction(callback) {
    const transaction = {
      get: async (target) => target instanceof FakeQuery
        ? { docs: target.snapshots(), empty: target.snapshots().length === 0 }
        : new FakeSnapshot(target, this.documents.get(target.path)),
      create: (ref, data) => {
        if (this.documents.has(ref.path)) throw new Error(`Document exists: ${ref.path}`);
        this.documents.set(ref.path, structuredClone(data));
      },
    };
    return callback(transaction);
  }
}

function seededDb(now) {
  return new FakeDb({
    "institutes/institute-a": {
      isActive: true, subscriptionStatus: "active", currentPeriodEndMs: now + 24 * 60 * 60 * 1000,
    },
    "institutes/institute-a/batches/batch-a": { status: "active", archivedAtMs: null },
    "institutes/institute-a/students/student-a": { status: "active", archivedAtMs: null },
    "institutes/institute-a/students/student-b": { status: "active", archivedAtMs: null },
    "institutes/institute-a/batch_students/a": { batchId: "batch-a", studentId: "student-a", status: "active" },
    "institutes/institute-a/batch_students/b": { batchId: "batch-a", studentId: "student-b", status: "active" },
  });
}

function request() {
  return {
    auth: { uid: "institute-a" },
    data: {
      operationId: "exam_fee_operation_0001",
      examId: "exam-a",
      instituteId: "institute-a",
      batchId: "batch-a",
      examName: "Final Exam",
      subject: "ICT",
      totalMarks: 100,
      passingMarks: 40,
      examDateMs: 1_786_000_000_000,
      examFeeAmount: 250,
      teacherName: "Teacher A",
      note: "Bring admit card",
    },
  };
}

test("creates one linked exam fee for each currently enrolled active student", async () => {
  const db = seededDb(Date.now());
  const handler = createExamFeeBillingHandler({ db });
  const result = await handler(request());

  assert.equal(result.billedStudentCount, 2);
  assert.equal(result.exam.examFeeAmount, 250);
  assert.equal(result.fees.length, 2);
  result.fees.forEach((fee) => {
    assert.equal(fee.feeType, "exam_fee");
    assert.equal(fee.sourceId, "exam-a");
    assert.equal(fee.dueAmount, 250);
    assert.equal(db.documents.get(`institutes/institute-a/fees/${fee.id}`).feePeriod, "Final Exam");
  });
});

test("repeating the same operation is safe and does not make duplicate fees", async () => {
  const db = seededDb(Date.now());
  const handler = createExamFeeBillingHandler({ db });
  const first = await handler(request());
  const repeat = await handler(request());
  assert.deepEqual(repeat, first);
  assert.equal([...db.documents.keys()].filter((path) => path.includes("/fees/")).length, 2);
});

test("an inactive student is not billed even if a stale enrollment exists", async () => {
  const db = seededDb(Date.now());
  db.documents.set("institutes/institute-a/students/student-b", { status: "inactive", archivedAtMs: null });
  const result = await createExamFeeBillingHandler({ db })(request());
  assert.equal(result.billedStudentCount, 1);
  assert.equal(result.fees[0].studentId, "student-a");
});
