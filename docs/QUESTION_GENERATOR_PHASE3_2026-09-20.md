# Question Generator — Phase 3 review and finalization

Phase 3 turns a completed Gemini preview into teacher-reviewed private question-bank
records. It deliberately does **not** activate a financial debit or PDF generation.
Those operations require a separately approved, server-authoritative question-wallet
policy and Page Setup/PDF phase respectively.

## Teacher flow

1. After generation, the app opens **Review & edit** rather than showing a read-only
   preview.
2. The teacher can select or remove each question, edit the question, four MCQ
   options, answer, explanation, difficulty and marks.
3. The fixed bottom summary updates the selected count, marks and a proposed price:
   MCQ 25 poisha, short 50 poisha, creative 75 poisha. The UI treats this as an
   estimate only — it is never a payment receipt or a client-authorized debit.
4. The app refuses an empty selection, malformed question, duplicate/incorrect MCQ
   answer, or selected marks greater than the configured exam total.
5. **Finalize** calls `finalizeExamQuestions`; it never writes Firestore directly.

## Trusted finalization

The callable rechecks authentication, `manage_exams`, current T&C consent, preview
ownership, completion status, question type, source question IDs, field lengths,
MCQ constraints and the exam mark ceiling. A preview may be finalized only once.

It atomically creates one finalized document per selected question under
`institutes/{id}/question_bank`, marks its review status as `teacher_reviewed`, and
records an idempotent finalization operation. The existing document trigger then
copies only allow-listed academic/question data to `global_pending_review`; names,
UIDs, institute IDs and source scans never cross that boundary.

## Billing safety

`aiBilling.enabled` remains `false`, so the callable returns
`pricing_not_configured_no_debit` and stores a quoted amount in integer poisha only.
No question-bank wallet ledger is written and no balance is changed. Enabling real
charges needs a separately reviewed rate card, top-up/approval flow, server quote,
reservation, settlement/refund logic and immutable ledger before this status changes.

## Validation

- Android compile: `:app:compileDebugKotlin`
- Cloud Functions suite: `npm test` (includes finalization ownership, replay and
  validation tests)
