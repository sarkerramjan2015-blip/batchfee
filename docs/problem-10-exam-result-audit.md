# Problem 10 — Exam & Result audit

## Confirmed existing flow

- `ExamEntity` is a single batch exam with optional subject, date, total/pass marks, teacher, note, status and archive timestamp. `ResultEntity` stores one row per student/exam with marks, grade, rank, remark and publish flag. There is no separate subject-exam or combined-result model.
- Marks are stored locally in Room and mirrored to Firestore `institutes/{instituteId}/exams` and `results`. The existing sync previously treated status as `scheduled`/`completed` and had no absence field.
- The existing grade rule is percentage based after the configured pass mark: `A+` at 80%, `A` 70%, `A-` 60%, `B` 50%, `C` 40%, `D` below 40%, and `F` below pass mark.
- The existing screen supported batch entry, result messages, a merit text list, publish, and archive. It did not provide explicit draft/review states, absence, zero-mark saving, complete-result publish validation, a Result Card presentation, or multi-exam card.
- Existing access is route-gated by the effective `manage_exams` permission. There were no granular exam permissions and Firestore uses the established linked-user rule. This change retains that model and adds the same permission check in the ViewModel; it does not weaken Firestore rules.
- Existing result sharing was text only. There was no reusable document/image/PDF exporter for results, branding snapshot, or weighted report model.
- Existing published marks were protected by a UI/ViewModel guard added in the working tree, but batch deletion still removed all result/exam records locally and remotely.

## Implemented, minimal safe changes

- Exam creation now captures a practical type (`Class Test`, `Weekly`, `Monthly`, `Half-Yearly`, `Final`, `Other`) and `includeInReportCard` (default on). Both are additive Room/Firestore fields with a 17→18 Room migration.
- Lifecycle is now `draft`, `marks_pending`, `ready_to_publish`, `published`, and `archived`. Historic Firestore statuses remain readable; new operations use the new states.
- Mark entry accepts zero, validates `0 <= mark <= total`, supports an explicit absence checkbox and an optional short remark, preserves draft result IDs, applies the existing grade rule, and assigns competition ranks (for example `1, 1, 3`). Absentees have no rank.
- Review summarizes total students, marks entered, missing marks, absent, pass and fail. Publishing is blocked until every batch student has either a valid mark or explicit absence.
- Published and archived results cannot be edited. Published exams cannot be edited; a staff member can archive while records remain stored. Publish and archive actions append to the existing Problem 08 audit log.
- A professional in-app Result Card presents existing institute branding/contact when available, student/batch/exam data, total/obtained marks, percentage, grade, pass/fail/absence, rank, remark, and an authorized-signature placeholder. It shares as Unicode-safe text through the existing Android share channels.
- A separate in-app Report Card shows only the student's most recent three **published**, report-enabled exams in the same batch. It is intentionally unweighted, shares as Unicode-safe text, and does not alter original marks. There is no combined-weight implementation.
- Batch cleanup no longer deletes local or Firestore exam/result history, preventing silent removal of published academic records.

## Intentional deferrals

- No PDF/image result-card export was added because this app has no existing exam document/export pipeline; the implementation uses the existing text-share infrastructure rather than screenshots.
- No configurable subject groups, terms, academic years, weighting, or a separate report-card persistence model was introduced. These need a product decision and a snapshot/export design before they can safely become historical records.
- No new permission keys were added because existing staff permission assignments use the established coarse `manage_exams` capability. Adding new keys would silently lock out existing staff until migration of their permissions.
