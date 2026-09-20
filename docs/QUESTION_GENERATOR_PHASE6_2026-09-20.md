# Question Generator — Phase 6: Approved Question Bank

## Delivered flow

1. A Super Admin approves a reviewed question from the anonymous moderation queue.
2. The approved question is stored in the server-owned `global_question_bank` collection.
3. An active Institute Owner, Admin, or exam-enabled staff member opens **Exams → Approved Question Bank**.
4. They can filter by class, subject, chapter, question type, difficulty, or text search, then choose up to 30 questions.
5. Before a paper is created, the callable function re-reads every selected question from the approved global bank. Retired, altered, or unapproved records are rejected instead of being trusted from the device.
6. The verified questions open the Phase 5 page setup and branded PDF workflow. This route does not call Gemini or spend AI wallet balance.

## Privacy and access boundary

- The Android app never reads `global_question_bank` directly.
- The callable returns an academic-only allowlist: question content, answers, options, class, subject, chapter, type, difficulty, marks, and timestamps needed for ordering.
- Institute identity, teacher identity, contributor identity, source document paths, and moderation notes are never returned to the institute.
- Each successful paper preparation creates a server-only audit record at `institutes/{instituteId}/question_bank_usage/{operationId}`. The client has no Firestore read or write access to this audit path.
- Only an authenticated, active institute owner/admin or staff member with `manage_exams` permission can use the callable.

## Initial operating limits

- Library search returns a maximum of 50 approved questions per request.
- A paper can include 1–30 unique approved questions.
- Filtering is enforced on the server. The first version intentionally favors safe, bounded requests over an unrestricted global query.

## Next scale-up work

When the central bank grows, add cursor pagination, indexed catalogue/filter fields, curriculum/version tags, duplicate detection, quality/rating signals, and balanced-paper generation rules. Those are separate from this phase so the current privacy and approval boundaries stay simple and auditable.

## Verification

- Cloud Functions checks: 207 passing tests.
- Question-bank library handler: 3 passing unit tests.
- Android Kotlin compile: successful (`:app:compileDebugKotlin`).
- Firestore emulator rules: 65 passing tests.
