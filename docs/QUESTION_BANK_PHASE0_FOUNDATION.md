# Question bank — Phase 0 foundation

Status: Phase 0 is deployed and Phase 1 exam setup/document scanning is implemented in the Android client. No AI generation, upload, wallet top-up or charge is enabled yet.

## Product boundary

The institute question bank remains private. The first time an owner or authorized teacher opens the AI generator, a versioned one-time T&C dialog explains that finalized questions can be copied anonymously for research and global question-bank moderation. Consent is personal to the authenticated actor. A finalized question is eligible for automatic backend sync only when its creator accepted the current policy.

Consent starts off and is stored by a callable with an immutable acceptance event. An older policy version is treated as unaccepted and must be shown again. The owner cannot accept for a teacher. `manage_exams` is required for staff; owner and institute admin retain exam access under the existing role model. Students have no question-bank access.

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

- Show the T&C before opening the generator. Record a positive rights acknowledgement, exact policy version and server timestamp.
- Keep drafts private. Only a `finalized` question created by an actor with current consent can enter the server-only pending moderation queue.
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
| `institutes/{id}/question_contribution_consents/{uid}` | Current personal versioned T&C consent |
| `institutes/{id}/question_contribution_consent_events/{operationId}` | Immutable acceptance history |
| `institutes/{id}/question_bank_wallet/{walletId}` | Separate AI credit balance (future phase) |
| `institutes/{id}/question_bank_wallet_ledger/{entryId}` | Server-only immutable credit ledger (future phase) |
| `global_pending_review/{hash}` | Anonymous, allow-listed finalized content awaiting moderation |
| `global_question_bank/{questionId}` | Curator-approved central asset (future phase) |

The callable is `questionBankFoundation` in `asia-south1`. It supports `get_foundation` and `accept_ai_tnc`. `syncFinalizedQuestionToGlobalPending` uses a strict allow-list and a hashed idempotent destination ID; institute name, teacher name/UID, institute ID and source URLs are not copied. Android reaches the generator from Exams & Results.

## AI wallet rules before charging is enabled

The question wallet is separate from the SMS wallet and student fee collection. No client balance write is allowed. `aiBilling.enabled` is `false`, pricing is `not_configured`, and no free attempts are granted yet. This prevents a displayed price from being mistaken for an approved charge.

For a later paid phase: represent money as integer poisha, quote on the server, reserve the maximum cost before invoking AI, settle once from the actual billable result, and refund failed/unused reservations. Each operation must have an idempotency key, immutable ledger entry, institute/actor binding and a server timestamp. Never charge from a Compose button alone. A per-institute trial budget, rates and refund rules require a measured cost pilot and owner approval before they are activated.

## Phase 0 exit checks

- Existing exam screens still compile and the AI generator loads only for an authorized institute actor.
- Consent is off by default and the rights acknowledgement is mandatory.
- Retried operation IDs cannot change their actor or institute.
- Firestore rules deny direct client access to private questions, consent history, AI wallet and central bank, including through the generic tenant wildcard.
- No AI request or wallet deduction can be made through this phase.
