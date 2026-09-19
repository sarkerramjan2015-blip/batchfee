# Question Generator — Phase 2 implementation

Phase 2 adds a bounded, read-only AI question preview. It does **not** collect
payment, debit an SMS or question wallet, finalize an exam, or publish a question.
Teacher editing/selection and private-bank finalization remain Phase 3 work.

## Request path

1. The existing one-time contribution consent and `manage_exams` authorization
   are verified on the server for the current institute and actor.
2. The Android ML Kit scanner supplies one or two cropped JPEG pages. The app
   reads at most 6 MiB per page and 10 MiB combined, then calls
   `generateExamQuestions` in `asia-south1`. It never receives a Gemini API key
   or direct Firebase Storage write permission.
3. The callable repeats size, MIME, JPEG signature, field and count validation;
   reserves an idempotent generation job and daily preview quota transactionally.
4. The server sends validated JPEG bytes inline to the Gemini Developer API.
   This avoids a second image copy or a reusable public/private source URL.
   The model is pinned to stable `gemini-3.8-flash` rather than the mutable
   `gemini-flash-latest` alias. It uses a constrained JSON response schema and
   validates every returned question again before sending it to Android.

The backend keeps the generated preview and token usage in its server-only
`question_generation_jobs` audit record. No original image bytes or arbitrary
URL are saved to Firestore. Existing Firestore rules deny direct client access
to generation jobs, consent records and global review/bank collections.

## Cost and privacy controls

- Per actor: 5 previews per Dhaka calendar day. Per institute: 25.
  Platform-wide: 100.
- Each request: at most two pages and 30 questions. At most two function
  instances process one request each concurrently.
- A retry with the same operation ID and identical payload replays a completed
  result without another model call. Failed or in-progress operations do not
  automatically re-run; start a new attempt explicitly.
- These are *preview limits*, not the original proposed five free lifetime
  attempts or approved commercial pricing. Gemini Developer API usage may
  incur cloud charges; promotional credit is not a guarantee of zero cost.
- Teachers must review answers, copyright rights and personal information;
  scanned pages and generated content are sent to Google Cloud for processing.
  Prompt-injection text inside documents is explicitly treated as data.

## Activation checklist (not performed by this implementation)

Create the `BATCHFEE_GEMINI_API_KEY` Firebase Secret Manager secret using a
rotated, restricted Gemini API key; never put it in Android resources, Git,
an `.env` file or a command argument. The key shared in a conversation should
be considered exposed before production use. Confirm a billing budget and
API-key restrictions before deploying only `generateExamQuestions`. Run a
consented, rights-cleared two-page smoke test and inspect the preview, job
audit and quota. Re-enable strict App Check enforcement once the Play-
distributed app attestation is verified; the existing global callable
configuration currently leaves it observational.
