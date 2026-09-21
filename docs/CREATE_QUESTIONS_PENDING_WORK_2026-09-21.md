# Create Questions — Pending Work Notes (21 Sep 2026)

Purpose: track issues found around the Create Questions / Question Generator
feature. No fixes applied yet — planning document only.

## A. In-progress uncommitted work (main pending item)

There is a large uncommitted enhancement in the working tree (~639 insertions,
173 deletions across 13 files) that upgrades Create Questions with board-aligned
question patterns, structured chapters, and mark rules. It is **not verified
yet** (no fresh build/test run documented for this diff).

### Android side
- [`QuestionBankFoundationScreen.kt`](app/src/main/java/com/example/ui/exams/QuestionBankFoundationScreen.kt)
  - Chapter input split into chapter number → canonical `Chapter N` + chapter
    name + topic fields (`canonicalQuestionChapter`, `displayQuestionChapter`).
  - New subject-driven pattern profiles: English 1st Paper (seen/unseen
    comprehension, writing), English 2nd Paper (grammar, composition),
    Bangla 2nd Paper (grammar MCQ, written) with focus/format variant dropdowns.
  - Creative questions now require the four sub-questions ক, খ, গ, ঘ
    (1 + 2 + 3 + 4 = 10 marks). Validation blocks blank sub-questions.
  - Fixed marks: MCQ = 1, CQ = 10, short = `shortQuestionMarks` (1–100).
    Marks field is editable only for short type.
- [`QuestionGenerationRepository.kt`](app/src/main/java/com/example/data/repository/QuestionGenerationRepository.kt) —
  setup carries `shortQuestionMarks`, `chapterName`, `topic`, `patternKey`,
  `patternVariant`.
- [`QuestionReviewModels.kt`](app/src/main/java/com/example/data/repository/QuestionReviewModels.kt) —
  review policy enforces MCQ=1 and CQ=10.
- [`QuestionFinalizationRepository.kt`](app/src/main/java/com/example/data/repository/QuestionFinalizationRepository.kt),
  [`QuestionCurationRepository.kt`](app/src/main/java/com/example/data/repository/QuestionCurationRepository.kt),
  [`QuestionBankLibraryRepository.kt`](app/src/main/java/com/example/data/repository/QuestionBankLibraryRepository.kt),
  [`QuestionBankAdminRepository.kt`](app/src/main/java/com/example/data/repository/QuestionBankAdminRepository.kt) —
  models/DTOs extended with chapterName/patternVariant fields.
- [`MainActivity.kt`](app/src/main/java/com/example/MainActivity.kt) — unrelated
  polish: animated notification-permission dialog (bell swing + glowing border).

### Backend side
- [`questionGeneration.js`](functions/src/questionGeneration.js) — pattern keys,
  chapter name/topic/pattern instructions in the Gemini prompt, expected marks
  enforcement per type (MCQ=1, short=shortQuestionMarks, CQ=10).
- [`questionFinalization.js`](functions/src/questionFinalization.js) —
  `shortQuestionMarks`, chapterName, pattern fields; every finalized question
  must use the exact expected mark per type.
- [`questionBankFoundation.js`](functions/src/questionBankFoundation.js) —
  taxonomy relaxed: required academic fields now `className/subject/chapter`;
  `curriculum/syllabusYear/chapterName/topic` optional; payload gains
  patternKey/patternVariant/shortQuestionMarks.
- [`questionCuration.js`](functions/src/questionCuration.js),
  [`questionBankLibrary.js`](functions/src/questionBankLibrary.js) — DTO/search
  propagate the new fields.
- Tests partially updated:
  [`questionFinalization.test.js`](functions/test/questionFinalization.test.js),
  [`questionBankFoundation.test.js`](functions/test/questionBankFoundation.test.js),
  [`noticeCenter.test.js`](functions/test/noticeCenter.test.js).

### Open risks in this diff
1. Full Android compile + unit tests not yet rerun against the new UI.
2. Full Cloud Functions test suite (was 209 passing at Phase 7) not rerun after
   the taxonomy change (`requiredAcademicFields` relaxed) — older tests may
   assert the strict taxonomy.
3. Firestore rules/index impact of new fields not rechecked.
4. Work is uncommitted; needs a dedicated commit after verification.

## B. Create Questions deployment pending (Phase 8)

[`docs/QUESTION_GENERATOR_PHASE8_2026-09-20.md`](docs/QUESTION_GENERATOR_PHASE8_2026-09-20.md):
the Firebase control-plane source is ready but **deployment is intentionally
separate** — needs authenticated Firebase CLI session and the
`BATCHFEE_GEMINI_API_KEY` Secret Manager secret configured.

## C. Question Generator roadmap items still open

From [`docs/QUESTION_GENERATOR_PHASE7_2026-09-20.md`](docs/QUESTION_GENERATOR_PHASE7_2026-09-20.md):
quality ratings, curriculum-version migration tools, semantic near-duplicate
review, automatic balanced-paper selection, question-performance analytics.

## D. Broader repo issues already tracked elsewhere

- [`docs/V1.8_DEFERRED_ISSUE_LIBRARY.md`](docs/V1.8_DEFERRED_ISSUE_LIBRARY.md) —
  11 confirmed live-app issues (V18-001..011); several implemented locally but
  awaiting deployment + device verification.
- Untracked debug artifacts clutter the repo root (emulator screenshots/XML
  dumps, smoke outputs, `.build-outputs/`).

## Plan understood (per owner instruction)

- Focus: finish and verify the pending Create Questions work (section A).
- Do not implement yet; await further direction.
