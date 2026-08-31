"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { resolveApprovalReplay } = require("../src/registrationApprovalCore");

test("a registration without an approval operation is new", () => {
  assert.deepEqual(resolveApprovalReplay({
    operation: null,
    existingStudent: null,
    requestedStudentId: "student-1",
  }), { kind: "new" });
});

test("the same committed approval can safely replay", () => {
  assert.deepEqual(resolveApprovalReplay({
    operation: { studentId: "student-1" },
    existingStudent: { photoUri: "gs://bucket/student.jpg" },
    requestedStudentId: "student-1",
  }), {
    kind: "replay",
    photoUri: "gs://bucket/student.jpg",
  });
});

test("an approval operation without its student is not reported as success", () => {
  assert.deepEqual(resolveApprovalReplay({
    operation: { studentId: "student-1" },
    existingStudent: null,
    requestedStudentId: "student-1",
  }), { kind: "conflict" });
});

test("a later approval attempt with a different student ID cannot duplicate", () => {
  assert.deepEqual(resolveApprovalReplay({
    operation: { studentId: "student-1" },
    existingStudent: { photoUri: "gs://bucket/student.jpg" },
    requestedStudentId: "student-2",
  }), { kind: "conflict" });
});
