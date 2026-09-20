# Question Generator — Phase 7: Scalable, Curated Question Library

## What is now added

### Stable catalogue pagination

- Approved questions are listed in newest-curated order through opaque server-issued cursors.
- Institute users can tap **Load more approved questions** without downloading the whole global collection.
- Each request remains bounded to 50 records or fewer, and the device still has no direct Firestore access to the global bank.
- A Firestore composite index supports the approved-status and curation-time query shape.

### Curriculum-aware filtering

- Curated questions now retain and return academic `curriculum` and `syllabusYear` metadata.
- Institute exam managers can filter the approved bank by curriculum, syllabus year, class, subject, chapter, type, difficulty, and text/topic.
- These are academic fields only; no institute, teacher, contributor, source file, or moderation identity is exposed.

### Duplicate protection at approval time

- Before Super Admin approval, the server creates a normalized fingerprint from academic metadata, question text, options, and answer.
- The fingerprint is checked and recorded atomically in a server-only `global_question_dedup` registry.
- An identical approved question cannot enter the central bank twice, including through concurrent review attempts or retry requests.
- The duplicate message never identifies the existing contributor, institute, or question source. The review item remains pending so the moderator can reject it with the appropriate audit reason.

## Security boundary

- `global_question_bank`, `global_question_dedup`, and per-institute question-bank usage audit documents remain denied to direct Firestore client reads and writes.
- Pagination cursors are validated on the callable before being used.
- Every chosen question is still re-read from the canonical approved collection before the Phase 5 PDF flow starts.

## Verification

- Cloud Function syntax checks: passed.
- Full Cloud Functions suite: 209 passing tests.
- New moderation and library pagination tests: 9 passing tests.
- Android Kotlin compile: verified before commit.

## Deliberately next, not included here

Quality ratings, curriculum-version migration tools, semantic near-duplicate review, automatic balanced-paper selection, and question-performance analytics need their own product rules and will be safer as the following phase.
