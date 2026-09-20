# Super Admin Business Intelligence — Feasibility & Phase Roadmap

Date: 2026-09-20 · Scope: `SuperAdminScreen.kt`, `PlatformAdminRepository.kt`, `functions/src/platformAdmin.js`, `smsWallet.js`, `financialLedger.js`, `instituteOwnerLoginActivity.js`

> **Implementation status (2026-09-20): Phases 1–3 implemented.** Phase 4 remains optional.

### What is live in code now
- **Backend** (`functions/src/platformAdmin.js`): dashboard metrics extended with `newInstitutesThisMonth`, `pendingSubscriptionAmountBdt`, `pendingSubscriptionCount`, `paymentRequestApprovalRate` (+ approved/rejected counts). New root-only action `get_business_intelligence` returning `churnRisk`, `rankings`, `forecast.subscription`, `forecast.sms`. The callable now carries the Zend secrets solely so the SMS forecast can read the gateway balance server-side.
- **Backend counters**: `financialLedger.js` writes `lastCollectionAtMs` on every payment commit (grouped collect, single collect/adjust, online-payment approval). New trigger `reconcileAttendanceActivitySignal` (`functions/src/activitySignal.js`) maintains `lastAttendanceAtMs` on the institute doc.
- **Android**: new `BI` tab in Super Admin with Decision KPIs, Forecast cards (subscription + SMS), Rankings (revenue / outstanding / SMS consumers / fastest growing), and Churn Watch (Low/Medium/High with reasons + Call/SMS/WhatsApp actions, tap opens institute detail).
- Churn scoring signals: inactivity (7d/21d), SMS credit exhausted, subscription expired/blocked/expiring ≤3d, students lost > added in 30d, no fee collection in 30d, no attendance in 30d.

### Deployment note
Deploy `functions` (`firebase deploy --only functions`) and ship the app build together. Until the new backend is deployed the app falls back to safe defaults (dashboard extra fields default to 0, BI tab shows an unavailable message). The attendance/fee counters only start populating after deployment — legacy institutes contribute no fee/attendance churn signal until new activity occurs (by design: nothing is guessed).

---

## 1. Verdict

**Yes — implementable on the current running system.** Roughly 85% of the requested KPIs already exist server-side and are already streaming into the Super Admin screen (SMS today/week/month/lifetime, SMS profit & cost breakdown, institute SMS ledger, subscription revenue, expiring 7/30 days, pending request count). The remaining work is:

- A few **light aggregations** on the existing dashboard callable
- **New counter fields** on the institute doc for attendance/fee activity (written by triggers/callables, no schema migration)
- One **new "Business Intelligence" callable action** (risk + forecast + rankings)
- **UI**: a new BI tab in SuperAdminScreen with cards, rankings, risk lists, and proactive contact actions (Call/SMS/WhatsApp buttons already exist per the established taste rules)

No composite indexes are required for the proposed design (single-field `count()`/range queries only). No Firestore rules changes are strictly required (all sensitive reads go through root-only callables, which is already the platform's pattern).

---

## 2. Current data inventory vs. requested features

| Requested feature | Already exists? | Where |
|---|---|---|
| Today/week/month/lifetime SMS sent | ✅ Yes | `getPlatformSmsAnalytics` (`todaySms`, `weekSms`, `monthSms`, `lifetimeSms`) — already rendered in Super Admin |
| SMS revenue, provider cost, VAT/charge, net profit | ✅ Yes | `financials` periods (`smsSalesBdt`, `serviceChargeBdt`, `providerEstimatedCostBdt`, `grossProfitBdt`) + `smsAccounting` — already rendered |
| Institute-wise purchased/used/remaining SMS | ✅ Yes | `analytics.institutes` (`totalSmsPurchased`, `lifetimeSms`, `walletBalance`) — already rendered |
| Subscription collection (lifetime/this month) | ✅ Yes | `dashboardMetrics` (`lifetimeRevenue`, `thisMonthRevenue`) |
| Total active institutes | ✅ Yes | `dashboardMetrics.activeInstitutes` |
| Expiring next 7 / 30 days | ✅ Yes | `dashboardMetrics.expiringIn7Days` / `expiringIn30Days` |
| Pending subscription amount (BDT) | ⚠️ Data exists, not aggregated | `subscriptionRequests` pending docs have `amountPaid`; the client only counts them today |
| New institutes this month | ⚠️ Data exists, not aggregated | `createdAtMs` on every institute doc |
| Payment request approval rate | ⚠️ Data exists, not aggregated | `payment_requests` collectionGroup (`status`, `reviewedAtMs`) is already readable by platform roles (existing UI reads it live) |
| Churn risk score (Low/Med/High) | ❌ Not built | Signals partially exist: `lastActiveAt`, `sms_balance`, `subscriptionStatus`, `currentPeriodEndMs` |
| Attendance / fee-collection activity signal | ❌ No counters | Attendance rows + payments exist per institute, but no `lastAttendanceAtMs` / `lastCollectionAtMs` anywhere |
| Student count decreasing | ❌ No history | Only current `studentCount`; `dashboard_summary/current` is overwritten each reconcile |
| Payment requests / feature usage per institute | ⚠️ Queryable | Per-institute `payment_requests` + `sms_messages` subcollections; no counters |
| 7/30-day subscription collection forecast | ❌ Not built | Historical receipts exist in `subscription_receipts` (collectionGroup) — basis for a moving-average forecast |
| SMS purchase forecast / days until central balance ends | ⚠️ Partially exists | `reorderSms`, `reorderAmountBdt`, `centralCapacitySms` already computed; run-rate/depletion-date not surfaced |
| Fastest-growing institute | ❌ Not built | `collectionGroup("students").where("createdAtMs", ">=", X).count()` is a single call — very feasible |
| Highest SMS-consuming institute | ⚠️ Data exists, no ranking UI | `analytics.institutes` sorted by `monthSms`/`lifetimeSms` |
| Highest revenue / highest outstanding institute | ❌ Not built | Revenue: aggregate `subscription_receipts`; outstanding: pending subscription requests + pending `payment_requests` |

---

## 3. Key gaps (and how they get closed)

1. **No fee-collection counter** — `payments` are callable-written, so `financialLedger.js` can update `institutes/{id}.lastCollectionAtMs` (and `collectionCount30d`) in the same transaction when a payment is written.
2. **No attendance counter** — attendance is written client-side (staff), so add an `onDocumentWritten` trigger on `institutes/{id}/attendance/{row}` that updates `lastAttendanceAtMs` on the institute doc (same pattern as the existing `reconcileStudentOperationalSummary` triggers).
3. **No student-count history** — two options:
   - *(Phase 4, preferred)* a daily scheduled snapshot (`institutes/{id}/metrics_snapshots/{YYYY-MM-DD}`) written by a new cron function;
   - *(Phase 2, immediate)* approximate "students lost last 30 days" from `students` docs with `archivedAtMs`/`deletionState` via `collectionGroup("students")` counts.
4. **`lastActiveAt` is client-written** and can be stale for institutes whose staff never logged in — treat it as one signal among several; the owner-login callable data is the authoritative fallback (30-day retention).

---

## 4. Phase breakdown

### Phase 1 — BI Overview Dashboard (low risk, ~70% no backend work)
**Goal:** one new "Business Intelligence" tab with decision-grade KPIs built from data that already exists.

- Extend `commitPlatformAdminOperation` dashboard action with 4 light fields: `newInstitutesThisMonth`, `pendingSubscriptionAmountBdt`, `pendingSubscriptionCount`, `paymentRequestApprovalRate` (count queries — no new indexes).
- New BI tab UI (`SuperAdminScreen.kt`, client-side aggregation + existing analytics):
  - SMS KPI cards: today/week/month/lifetime + revenue, provider cost, VAT/charge, net profit (reuse `SmsPlatformAnalytics`)
  - Subscription cards: collection this month, lifetime, pending amount, active institutes, new this month, expiring 7/30, approval rate
  - Rankings (computed from existing payloads): highest SMS consumer, highest revenue institute, highest outstanding institute
- Per taste: every stat card is clickable and drills into its filtered list; money formatted with BDT consistently.

### Phase 2 — Churn Risk Engine (backend: 1 callable + 2 counter writers)
**Goal:** Low/Medium/High risk per institute with reasons, so Super Admin can act before churn.

- New callable action `get_platform_churn_risk` (root-only, `platformAdmin.js`) returning per-institute:
  ```
  instituteId, name, phone, whatsappNumber, planId, status, expiry,
  risk: low|medium|high, reasons: [],
  signals: { lastActiveAtMs, daysSinceActive, smsBalance, studentsLost30d,
             lastCollectionAtMs, lastAttendanceAtMs, pendingRequestCount }
  ```
- Scoring (transparent, weighted): days since `lastActiveAt` (>7d = medium, >21d = high), `sms_balance == 0`, subscription expired/within 3 days, students lost in 30d > 0, no fee collection in 30d, no attendance in 30d, zero payment requests in 60d.
- Counter writers:
  - `financialLedger.js` writes `lastCollectionAtMs` + `collectionCount30d` on payment commit
  - New `onDocumentWritten` trigger on attendance → `lastAttendanceAtMs`
- UI: "Churn Watch" section — risk badge per institute, sorted high→low, tap to expand reasons, and **one-tap Call / SMS / WhatsApp / Send Notice** action buttons (consistent with existing contact-action taste rules). Reuse the existing archive/block-free, non-destructive action sheet.

### Phase 3 — Forecast Engine (backend: extend BI callable; UI: forecast cards)
**Goal:** forward-looking numbers instead of only snapshots.

- Subscription collection forecast:
  - Aggregate `subscription_receipts` by month (collectionGroup) → 7-day and 30-day forecasts using a simple moving average of the last 90 days; show confidence label (based on receipt count) — honest labeling, no "AI" branding.
- SMS forecast:
  - Daily burn rate from `sms_used_this_month` / recent `sms_messages` → **days until central balance depletion** (`centralCapacitySms` ÷ run-rate) → **suggested purchase amount** extending the existing `reorderSms`/`reorderAmountBdt` signal.
- Fastest-growing institute: `collectionGroup("students").where("createdAtMs", ">=", now-30d).count()` — single callable count.
- UI: forecast cards (7d/30d collection, days-of-SMS-left, reorder suggestion) + top-growers list; everything drills into the institute detail dialog that already exists.

### Phase 4 — Historical snapshots & proactive outreach (optional follow-up)
**Goal:** deeper accuracy and outbound automation.

- Daily scheduled cron writes per-institute `metrics_snapshots` (studentCount, smsBalance, status, lastActivity) → true growth/decline trend lines and more accurate churn scoring.
- Scheduled "risk digest" to the Super Admin (in-app notification center + optional WhatsApp message) listing high-risk institutes with one-tap actions.
- Weekly summary card: churn recovered vs. lost, forecast accuracy vs. actuals.

---

## 5. Risks / notes

- **Attendance counter trigger** adds one write per attendance row — negligible; batches of attendance use bulk writes already.
- **`payment_requests` collectionGroup count** for approval rate scans all institutes — use `count()` aggregates (cheap) instead of reading docs.
- **Churn scoring must be server-side** (root-only callable) — attendance/fees are not readable by the client under current rules, and the platform's security posture keeps sensitive aggregation out of the client.
- **`securityPin` / credential material**: risk reasons must never include student-level PII; only institute-level signals.
- All new UI must follow existing taste rules: contact actions on both card and dialog, BDT everywhere (no ৳), honest labels for forecasts (no fake "AI" badges), clickable stat cards.
