# BatchFee Android Security Guideline & Gap Report — 22 Sep 2026

Purpose: report only (no code changes). Covers the requested controls — reverse engineering,
data tampering, MITM, unauthorized API access — plus OWASP Mobile Top 10 gaps and the
"will running users face problems?" question for each.

---

## 1. What the app ALREADY does (verified in the codebase)

| Control | State | Where |
|---|---|---|
| R8 obfuscation + resource shrinking in release | ✅ ON | `isMinifyEnabled = true`, `isShrinkResources = true` in [`app/build.gradle.kts`](app/build.gradle.kts:117) |
| ProGuard keep rules (Firebase, App Check, models, serialization) | ✅ | [`app/proguard-rules.pro`](app/proguard-rules.pro:23) |
| Secrets Gradle Plugin (API keys outside git) | ✅ configured | [`app/build.gradle.kts`](app/build.gradle.kts:168) |
| Debug keystore forbidden for release artifacts | ✅ | `verifyReleaseSigning` in [`app/build.gradle.kts`](app/build.gradle.kts:86) |
| Backup disabled + extraction rules | ✅ | [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:14) `allowBackup="false"` |
| Minimal permissions (camera, internet, notifications, biometric only) | ✅ | [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:6) |
| Services/FileProvider not exported | ✅ | [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:37) |
| Strict Firestore security rules (deny-lists for sensitive collections) | ✅ deployed | [`firestore.rules`](firestore.rules:1) |
| All callables run through auth-guarded wrapper | ✅ | [`functions/src/index.js`](functions/src/index.js:2357) |
| Server-side AI quotas (actor/institute/platform daily) | ✅ | [`functions/src/questionGeneration.js`](functions/src/questionGeneration.js:511) |
| One pending top-up at a time, idempotent operations | ✅ | [`functions/src/questionBankFoundation.js`](functions/src/questionBankFoundation.js:194) |
| Student login failed-attempt tracking; registration rate-limit secret | ✅ partial | [`functions/src/index.js`](functions/src/index.js:2169) |
| Gemini key kept server-side in Secret Manager | ✅ | [`functions/src/index.js`](functions/src/index.js:189) |
| Crashlytics + App Check **dependency** present | ✅ deps only | [`app/build.gradle.kts`](app/build.gradle.kts:190) |

Verdict: backend guardrails are the strongest area. The client-side hardening list is mostly
missing or only half-done.

---

## 2. Gap-by-gap verdict ("perfect ki na")

### A. Code Protection (ProGuard/R8)
**Not perfect, but acceptable baseline.**
- R8 raises the cost of reverse engineering; it cannot prevent it. The DEX still contains readable
  strings (endpoints, URLs, feature flags).
- Missing niceties: `-renamesourcefileattribute`, crash-deobfuscation mapping upload to Play
  Console, and keeping the mapping file private.
- Stronger options (only if needed): DexGuard/Banuba-style string encryption, or moving critical
  constants/validation into native code (NDK). For this app the realistic gain is small — the real
  secrets are already server-side.

### B. API key hiding
**Good as-is; do not over-engineer.**
- The Secrets plugin keeps keys out of git ✅. NDK embedding is mostly theater — keys embedded in
  the APK are extractable regardless.
- `google-services.json` values are public identifiers by design. Real protection comes from:
  1. Restricting the API key in Google Cloud Console by Android package name + SHA-1/SHA-256
     (this is currently the single highest-value, lowest-cost step — verify it is set).
  2. Security Rules + App Check on the backend.
  3. Any truly sensitive key (like the Gemini key) stays server-side — already done ✅.

### C. Network Security / MITM
**Mostly safe by platform default; SSL pinning is optional, not mandatory.**
- No `network_security_config.xml` and no pinning. Android 9+ (app targets SDK 36) blocks
  user-installed CA certificates by default, and all Firebase traffic is TLS 1.2+ — so classic MITM
  via user CA is already largely mitigated.
- If pinning is added: pin Firebase and your own domains with at least one backup SPKI, and plan
  for certificate rotation. **Running-user risk: a pinning mistake = app-wide login outages on
  older installs.** Recommendation: skip hard pinning for now; rely on App Check + TLS, or
  implement pinning with a remotely configurable escape hatch.

### D. Data Security (local)
**Weakest area — this is a real gap.**
- No `EncryptedSharedPreferences`, no Room DB encryption (searched: 0 usages of
  MasterKey/SQLCipher/EncryptedSharedPreferences).
- Mitigations that already reduce exposure: `allowBackup="false"`, biometric support, session is
  Firebase Auth (no custom JWT stored client-side).
- Still exposed on a rooted device: cached student/fee data in the unencrypted Room database and
  any tokens in plain SharedPreferences.
- Fix (ordered): (1) Jetpack Security `EncryptedSharedPreferences` for any persisted tokens/flags;
  (2) SQLCipher only if offline student data is judged sensitive — it adds migration complexity and
  can slow low-end devices, so decide explicitly instead of doing it "just in case".

### E. Runtime Integrity (root / emulator / Play Integrity)
**Missing entirely (0 usages found) — and that is only a medium problem.**
- Root/emulator detection alone is weak and easily bypassed, and blanket "block rooted device"
  bans legitimate power users (false positives). Prefer **Play Integrity API verdicts**
  (`MEETS_DEVICE_INTEGRITY` / `MEETS_STRONG_INTEGRITY`) evaluated on the backend for
  high-value actions (wallet top-up, AI generation), not a hard app-entry gate.
- App Check Play Integrity provider is declared as a dependency but **never initialized in code** —
  this must be fixed before App Check can be enforced.

### F. Backend Guardrails (App Check, Rules, Rate limiting)
**Rules: strong. App Check: off. Rate limiting: partial.**
- App Check enforcement is disabled: `enforceAppCheck: false` in [`functions/src/index.js`](functions/src/index.js:140),
  with a comment noting it must be completed with a Play-distributed build.
  - **Running-user risk: turning it on before the updated app (with the provider initialized) is on
    users' phones breaks EVERY callable for old installs.** Correct sequence:
    app update shipped → enable enforcement in monitor mode → verify metrics → enforce.
- Rate limiting exists for: registration (secret-backed), student login failures, AI quotas,
  top-up pending. Missing: generic per-UID budgets on costly callables
  (`generateExamQuestions`, `sendBulkSms`, `uploadSecureMedia`) and a storage upload limiter.
  - **Running-user risk: too-tight limits throttle legitimate fee collection / SMS campaigns.**
    Pick per-endpoint budgets, not one global number.
- Storage security rules: media handlers exist; confirm the Storage rules file is deployed and
  denies unauthenticated reads (media is served via `getSecureMediaUrl` ✅).

### G. OWASP Mobile Top 10 — remaining risks
1. **M1 Credential misuse** — Firebase Auth only; do not persist custom JWTs locally. ✅ mostly.
2. **M2 Supply chain** — commented-out dependencies are fine; keep Gradle versions pinned.
3. **M3 Weak authz** — every new callable must keep the `guarded()` wrapper; add a lint/check that
   no `onCall` bypasses it.
4. **M4 Weak input validation** — backend canonical validation is strong ✅; never trust
   client-side checks (already the pattern).
5. **M5 Cleartext** — defaults deny cleartext ✅; pinning optional (see C).
6. **M6 Privacy permissions** — minimal ✅ (no location/contacts/sms read).
7. **M7 Insufficient binary protections** — R8 ✅, tamper detection absent (Play Integrity covers
   install integrity; skip checksum theater).
8. **M8 Code tampering** — Play Integrity + Play App Signing cover the realistic threat.
9. **M9 Reverse engineering** — server-side secrets are the fix; R8 is best-effort ✅.
10. **M10 Extraneous functionality** — ensure no `BuildConfig.DEBUG` backdoors in release; the
    release keystore gate already blocks debug signing ✅.

### Repo-specific extras found
- ⚠ `auth-old.json` in the repo root should be inspected and deleted if it contains any real
  token/credential material; same check for `.firebase/` and `firebase-debug.log`.
- `keystore-credentials.txt` / `keystore.properties` hold the release keystore password in plaintext
  on disk (gitignored ✅). Keep them out of any backup/cloud sync; CI should get them as secrets.
- Emulator debug artifacts (`.build-outputs/`, root-level `.png/.xml`) are clutter only — no
  security impact, but clean before publicizing the repo.

---

## 3. "Running user er jamela hobe ki na" — impact table

| Change | Break risk for current users | Mitigation |
|---|---|---|
| App Check enforce | 🔴 HIGH if enforced before app update | Ship app first → monitor mode → enforce gradually |
| SSL pinning | 🟠 Medium on cert rotation | Backup pins + remote kill-switch |
| Global rate limiting | 🟠 Medium (bulk ops throttled) | Per-endpoint budgets, exclude trusted tenant admin ops |
| EncryptedSharedPreferences | 🟢 Low (one-time flag migration) | Keystore-backed key, fallback if key invalidated |
| SQLCipher on Room | 🟠 Medium (migration time on old devices) | Decide only if offline data is sensitive; test on low-end device |
| Root/emulator block | 🟠 False positives ban real users | Use Play Integrity verdicts, not blanket bans |
| R8 rule changes | 🟢 Low, but test release build on device | Run `bundleRelease` + smoke test before Play upload |
| API key restriction (console) | 🟢 None | Package + SHA-256 restriction is retroactive-safe |

---

## 4. Recommended order (risk / cost / credit-friendly)

1. **App Check end-to-end** — initialize Play Integrity provider in app, ship update, then enforce
   backend-side in monitor→enforce steps. Biggest single win against unauthorized API access.
2. **API key restriction** in Google Cloud Console (package + SHA-256) — 5 minutes, zero code.
3. **EncryptedSharedPreferences** for persisted tokens/flags (no DB migration needed).
4. **Play Integrity verdict checks** on money actions (top-up approval, AI billing).
5. **Per-UID rate limits** on expensive callables, with budgets tuned per endpoint.
6. **SSL pinning** — only after 1–5, and only with backup pins.
7. **Cleanup** — `auth-old.json`, debug artifacts, keystore files out of any sync path.
8. **Deferred** — SQLCipher (business decision), NDK/DexGuard hardening (low ROI for this app).

## 5. Now vs Later — release decision

**Do before/with the next production release (2 items):**
1. **App Check provider init in the app + API key restriction (console).** Reason: if the next app
   update ships without the Play Integrity provider initialized, turning App Check on later forces
   *another* app update before enforcement is possible. Initializing it now costs nothing for users
   (backend stays non-enforcing until you choose). The console API-key restriction is zero-code and
   retroactive-safe.
2. **`auth-old.json` inspect + delete** if it contains credentials — pure leak risk, no user impact.

**Can be done later, staged (no urgency for running users):**
- EncryptedSharedPreferences (safe anytime; tokens only live in memory today).
- Play Integrity verdicts on money actions (top-up/AI) — improves after App Check is live.
- Per-UID rate limits on bulk endpoints (`sendBulkSms`, `uploadSecureMedia`) — tune budgets slowly.
- SSL pinning — only if a specific MITM concern appears; platform TLS already covers the common case.
- SQLCipher — business decision, do only if offline student data is judged sensitive.
- Repo cleanup (`.build-outputs/`, debug logs).

Nothing on the list is an active confirmed exploit: strict Firestore rules + auth-guarded callables
already block the common attack paths. The list is defense-in-depth, so "later" is acceptable for
everything except the two items above.

## 6. Local note (no action taken)
The app version bump (versionCode 14 / versionName 1.9.0) is applied locally but **not committed**
and no release build was created, per instruction. No other code was changed for this report.
