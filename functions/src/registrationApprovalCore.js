"use strict";

// Resolve a transport retry without turning a later manual approval attempt
// into a second student.  The original request and student IDs must both
// match, and the committed student document must still exist.
function resolveApprovalReplay({ operation, existingStudent, requestedStudentId }) {
  if (!operation) return { kind: "new" };
  if (operation.studentId === requestedStudentId && existingStudent) {
    return {
      kind: "replay",
      photoUri: typeof existingStudent.photoUri === "string" && existingStudent.photoUri
        ? existingStudent.photoUri : null,
    };
  }
  return { kind: "conflict" };
}

module.exports = { resolveApprovalReplay };
