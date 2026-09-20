# Question Generator — Phase 8: Firebase Control Plane

## Super Admin controls

The Super Admin dashboard now links to **Question Bank Controls**. The screen is backed only by root-authorized Firebase callable functions and provides:

- turn AI question generation on or off immediately;
- turn new anonymous contributions on or off immediately;
- set bounded daily preview limits for one teacher, one institute, and the whole platform;
- set the maximum questions allowed in a single generation request;
- open the anonymous approval/rejection queue;
- view active or retired approved questions and retire or restore one question.

## Server enforcement

- The generation callable loads the platform settings before it resolves Gemini or reserves any quota.
- When generation is paused, no source page is sent to Gemini, no AI job is created, and no preview count is consumed.
- Every generated job records the applied control snapshot for later audit.
- Each Super Admin mutation uses an idempotent operation ID and writes a private audit document.
- Retiring a question removes its active duplicate lock; restoring it creates the lock again unless another active identical question exists.

## Security

- `platform_question_bank_settings` and `question_bank_admin_operations` are direct-client-deny Firestore collections.
- The callable requires the existing root-platform authorization gate.
- The control UI and callable expose only academic question data, never the contributing institute, teacher, scan, or source material.

## Deployment note

The source is ready for Firebase deployment, but deployment itself is intentionally separate: it requires the project's authenticated Firebase CLI session and a configured `BATCHFEE_GEMINI_API_KEY` Secret Manager secret.
