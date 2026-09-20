# AI Question Generator — Phase 4: Anonymous Global Moderation

## What is live in the code

Finalized questions follow a server-owned route:

```text
Private institute question bank
  → anonymous Firestore sync trigger
  → global_pending_review
  → Super Admin approve or reject
  → global_question_bank (approved only)
```

The pending and global collections are closed to direct client reads and writes.
The app uses `commitQuestionCurationOperation`, which verifies that the caller is
an active platform root before it lists, approves or rejects anything.

## Privacy boundary

Only academic content is copied into the moderation queue and curated bank:

- Class, subject, chapter, topic and curriculum metadata
- Question type, language, difficulty and marks
- Question, options, correct answer and explanation

Institute ID/name, teacher/user ID/name, source image URLs, original private
document path and generation operation data are never returned to the Super
Admin screen and are never published to the global bank. A strict allow-list is
applied again at approval time, so even an old queue document with extra fields
cannot leak identity.

## Moderation behaviour

- **Approve** creates one curated global record atomically and marks the queue
  item approved.
- **Reject** marks the queue item rejected and preserves a short reason only in
  the server-side audit operation.
- Each action has a UUID operation ID. Retrying the same action is safe and
  cannot publish the question twice.
- Invalid/incomplete queue content cannot be approved.

## Super Admin UI

Platform → **Question Bank Moderation** opens the anonymous queue. It shows the
question and its academic metadata, not its contributor. The reviewer can
approve after confirmation or reject with an audit reason.

## Deliberately deferred

This phase does not yet let teachers search/use the curated global bank. That
is the next consumption phase, where we should add curriculum/version filters,
duplicate detection, usage attribution and paper composition. It also does not
change the separately disabled AI wallet billing policy.

## Verification

- `npm.cmd run check` in `functions/`
- `npm.cmd test` in `functions/` — 204 tests passing
