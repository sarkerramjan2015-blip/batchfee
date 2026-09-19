# Question bank — Phase 0 foundation

Status: implemented in code; backend callable and Firestore rules still require deployment. No question generation, upload, wallet top-up or charge is enabled by this phase.

## Product boundary

The first question bank is private to each institute. A teacher can later choose individual questions for central review only after personally enabling a versioned contribution preference. Enabling the preference alone never copies a question. Central publication will require a separate per-question submission and curator approval in a later phase.

The preference is per authenticated user and institute, starts off, can be revoked, and is stored by a callable with an immutable event for each change. An older policy version is treated as off. The owner cannot opt in for a teacher. `manage_exams` is required for staff; owner and institute admin retain exam access under the existing role model. Students have no question-bank access.

## Canonical academic taxonomy (schema v1)

| Field | Values / rule |
| --- | --- |
| `questionType` | `mcq`, `short`, `creative` (CQ) |
| `difficulty` | `easy`, `medium`, `hard` |
| `language` | `bn`, `en` |
| `sourceType` | `manual`, `teacher_note`, `licensed_material`, `ai_assisted` |
| `reviewStatus` | `draft`, `teacher_reviewed`, `pending_curation`, `curated`, `retired` |
| Academic identity | `curriculum`, `syllabusYear`, `className`, `subject`, `chapter`, `topic` |

These are server-returned schema values, not client-supplied authority. A future question document also needs marks, answer key, optional explanation/stimulus, creator UID, institute ID, source reference, schema version, created/updated timestamps and revision history. Curriculum, class, subject and chapter are plain values for now; a canonical catalogue and migration mapping are Phase 1/4 work. A new syllabus year must not silently reuse an old question.

## Content and source policy

- Show the contribution terms before enabling the preference. Require a positive rights acknowledgement, and record the exact policy version and server timestamp.
- Keep manual and AI-assisted drafts private. Future central submission is an explicit action for each selected question, followed by curation.
- Accept only original work or material the contributor has permission to share. Do not put student names, phone numbers, answer scripts or other personal data into a question or source scan.
- A scan of a book page is an input for drafting, not a permission to publish that page or reproduce its questions centrally. Later upload flow must use protected Storage objects, no public download token, size/type/page limits and a verified retention/deletion job.
- A future withdrawal stops new central submissions. Handling of already published question revisions or printed papers needs a separately reviewed policy before the curation phase.
- Teacher-reviewed and curated are different statuses. AI output cannot be labelled board-verified without human review.

## Firestore and Storage boundaries

The existing broad institute rule explicitly excludes the reserved paths below. Android clients cannot directly read or write them; trusted callables must enforce role and tenant checks. Current `storage.rules` already denies all client reads/writes.

| Path | Intended ownership |
| --- | --- |
| `institutes/{id}/question_bank/{questionId}` | Private institute draft/reviewed questions (future phase) |
| `institutes/{id}/question_generation_jobs/{jobId}` | AI job state and cost audit (future phase) |
| `institutes/{id}/question_sources/{sourceId}` | Protected scan metadata and retention state (future phase) |
| `institutes/{id}/question_contribution_consents/{uid}` | Current personal preference (Phase 0 callable) |
| `institutes/{id}/question_contribution_consent_events/{operationId}` | Immutable preference history (Phase 0 callable) |
| `institutes/{id}/question_bank_wallet/{walletId}` | Separate AI credit balance (future phase) |
| `institutes/{id}/question_bank_wallet_ledger/{entryId}` | Server-only immutable credit ledger (future phase) |
| `global_question_bank/{questionId}` | Curator-approved central asset (future phase) |

The Phase 0 callable is `questionBankFoundation` in `asia-south1`. It supports `get_foundation` and `set_contribution_preference`. It does not expose content or payment operations. Android reaches the preference page from Exams & Results.

## AI wallet rules before charging is enabled

The question wallet is separate from the SMS wallet and student fee collection. No client balance write is allowed. `aiBilling.enabled` is `false`, pricing is `not_configured`, and no free attempts are granted yet. This prevents a displayed price from being mistaken for an approved charge.

For a later paid phase: represent money as integer poisha, quote on the server, reserve the maximum cost before invoking AI, settle once from the actual billable result, and refund failed/unused reservations. Each operation must have an idempotency key, immutable ledger entry, institute/actor binding and a server timestamp. Never charge from a Compose button alone. A per-institute trial budget, rates and refund rules require a measured cost pilot and owner approval before they are activated.

## Phase 0 exit checks

- Existing exam screens still compile and the new preference page loads only for an authorized institute actor.
- Consent is off by default, rights acknowledgement is mandatory to enable it, and revocation is available.
- Retried operation IDs cannot change their actor, institute or choice.
- Firestore rules deny direct client access to private questions, consent history, AI wallet and central bank, including through the generic tenant wildcard.
- No AI request or wallet deduction can be made through this phase.
