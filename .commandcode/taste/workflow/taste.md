# Workflow
- Use `.\\gradlew.bat` (not `./gradlew`) for Gradle commands on this Windows project. Confidence: 0.85
- Use `--offline --no-daemon` flags with Gradle commands for this project. Confidence: 0.70
- Set `JAVA_HOME` and `PATH` explicitly per session before running Gradle commands. Confidence: 0.70
- Run `git diff --check` on changed files before marking validation as complete. Confidence: 0.70
- Run Gradle builds as background processes (`Start-Process -WindowStyle Hidden`) with redirected stdout/stderr to log files rather than inline, then poll the logs for completion. Confidence: 0.80
- When an operation (emulator boot, build, etc.) fails repeatedly, stop and report the exact error instead of retrying indefinitely. Confidence: 0.80
- Structure multi-issue work sequentially — complete each problem fully (with visual/functional verification) before moving to the next, and use the explicit end-message the user requested. Confidence: 0.75
- Before making any code changes, first inspect and audit the current project state (git status, diffs, what's complete vs. incomplete) and report findings. Wait for explicit user approval before modifying anything. Confidence: 0.90
- Do not automatically reset, undo, restore, refactor, or clean anything when the app is working correctly — only make the changes the user explicitly requests. Confidence: 0.85
- Do not add UI elements, features, or information displays that the user did not explicitly request — even if they seem useful or related to the current task. Stick strictly to what was asked. Confidence: 0.85

- When fixing a bug, grep the codebase for all callers/consumers exhibiting the same anti-pattern and fix them in the same pass rather than addressing only the reported symptom location. Confidence: 0.80

- When reverting changes, use granular `git checkout <commit> -- <file>` to restore individual files rather than `git reset --hard` or other wholesale project-level operations — this preserves unrelated work across other problem areas intact. Confidence: 0.85

- When auditing code changes, categorize diff hunks by their origin/purpose (e.g., which problem fix, which optimization attempt) rather than just listing them — present a forensic breakdown with clear attribution so the user can distinguish completed work from unfinished or misclassified edits. Confidence: 0.80

- When new APIs or methods are introduced in a diff, trace all call sites (grep/search) to verify they are actually wired to consumers — explicitly flag dead code that has zero external callers. Confidence: 0.80

- After an audit, present actionable options with pros/cons rather than a single recommendation — let the user choose the path forward. Confidence: 0.75

- When implementing data-loading screens, use cache-first architecture: start local Room/DB observation immediately so cached data renders without delay, then launch a narrow scoped background sync (not a full-institute refresh). Never block the UI collector on a network sync. Confidence: 0.80

- Exclude non-product artifacts from Git commits — keep them on disk but never stage them. This includes: `.commandcode/taste/**`, screenshots, videos, emulator captures, build logs, and temporary/debug files. Confidence: 0.80

- For performance optimization work, scope changes to one screen at a time — fully validate the current screen and stop; do not cascade optimizations to other screens without explicit approval. Confidence: 0.80

- End task reports with a structured status line: either "[FEATURE] READY FOR VISUAL APPROVAL" (next step is user visual review) or "USER ACTION REQUIRED" (intervention needed before proceeding). Confidence: 0.80

- When fixing missing/incomplete data, use an idempotent fill-gaps approach: create only what's missing, never modify or delete existing records. Existing data safety is paramount — even when auto-generating corrections. Confidence: 0.75
- When validating correctness, verify actual database rows (before/after) rather than relying solely on expected behavior reasoning. Report concrete row counts, IDs, and states for representative scenarios. Confidence: 0.80
- Do not claim concurrency safety unless it has been tested with at least two simultaneous or repeated invocations producing the expected single-row result. Design-level arguments alone are insufficient to assert thread-safety. Confidence: 0.80

- When conducting a correctness audit, trace every code path exhaustively — read all call sites, entity models, DAO queries, seeder/format helpers, and screen-level consumers rather than inferring behavior from a single file. Include direct code snippets or line references as evidence for each finding. Confidence: 0.80

- Structure code audits with numbered risk sections, each containing: a clear statement of the risk, current-behavior analysis with code evidence, and an explicit ✅/❌ verdict. End the audit with a summary table plus a binary overall conclusion (e.g. "PROBLEM 05 AUDIT PASSED" or "PROBLEM 05 NEEDS CORRECTION"). Confidence: 0.75
- Before modifying any code that touches existing calculation or business-logic paths, first trace the runtime data flow end-to-end — read all call sites, state variables, callbacks, and downstream consumers — and confirm understanding of the current behavior. Do not make changes until the existing path is fully understood. Confidence: 0.85
- When implementing a feature that overlaps with existing business rules (payment allocation, discount logic, fee calculation, admission-date constraints, etc.), trace and preserve the current behavior rather than inventing new rules. If the existing behavior is unclear or insufficient, stop and ask the user rather than guessing. Confidence: 0.80
- When a requested change would require modifying areas explicitly scoped as out-of-bounds for the current phase (or explicitly listed as "Do NOT touch"), stop and report the conflict for a later phase rather than creeping scope — even if the change seems small or justified. Confidence: 0.85
- When you cannot access or verify a resource (e.g., a Firebase project with 403/INSUFFICIENT_PERMISSION), explicitly qualify every conclusion — state what you cannot determine due to the access gap rather than making definitive claims about the inaccessible resource. Never assert facts about something you were blocked from inspecting. Confidence: 0.85
- When the user asks for evidence or verification, provide exact forensic references: file paths, line numbers, raw `git diff` output, commit hashes, and terminal command transcripts — not prose summaries that merely claim a fix was made. Every factual claim must be traceable to concrete output. Confidence: 0.90
- When the user issues an explicit "do not make any edits, deploys, exports, imports, migrations, reinstalls, or deletions" directive and says "wait for my approval," enter a strict read-only mode: only inspect, audit, and report. Treat this as extending to all mutating actions — tests, deploys, exports, and data changes — not just source-code edits. Confidence: 0.85
- When the user asks for a verification/test plan, present a structured checklist with checkboxes organized by feature area and wait for explicit approval before executing any of it — never run tests, even read-only ones, without the user's go-ahead. Confidence: 0.75
- When asked whether a feature is working or what it depends on, decompose the answer into orthogonal concern categories (e.g., source code, Firestore data, security rules, Auth role, hosting config) and give a clear yes/no/explanation for each — don't give a single monolithic answer that conflates them. Confidence: 0.75
- Commit and push to Git as a backup/savepoint before transitioning to a new phase or major feature area — the user treats pushes as checkpoints they can return to if needed. Confidence: 0.80
- When implementing features that involve amounts/prices/fees, make them configurable by the end user (institute owner) rather than hardcoded or static. Support standard business operations like editable amounts, discounts, and partial payments — don't ship a minimal/hardcoded version that lacks real-world flexibility. Confidence: 0.70
- Push all changes to Git before ending a working session — the user treats pushed commits as savepoints so they can `git pull` and resume from exactly where they left off later. Confidence: 0.85
- When closing out a working session, provide a structured summary (table or checklist) of every completed feature so the user can mentally checkpoint what was done before stepping away. Confidence: 0.80
- When the user asks for an audit "since version X" (e.g., "Version 1.4-এর পর থেকে"), use git tags or the corresponding merge-commit hash as explicit range boundaries (`tag..HEAD`) for scoping the audit — the user treats version tags as milestone markers, not ad-hoc date or file-based cutoffs. Confidence: 0.80
- When verifying which Firebase project an Android app is bound to, treat `google-services.json` (SDK-level project binding) and `.firebaserc` (CLI/tools-level project binding) as the two canonical proof files — if both point to the same project, the binding is confirmed; these are the user's definitive config artifacts for project identity. Confidence: 0.80
- Git commit messages should use a structured format: conventional commit type prefix (e.g., `fix:`, `feat:`), a short summary line, blank line, then categorized sections with bullet points listing individual changes. Confidence: 0.75
