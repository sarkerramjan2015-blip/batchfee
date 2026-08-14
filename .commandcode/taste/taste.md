# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# Workflow
- Use `.\\gradlew.bat` (not `./gradlew`) for Gradle commands on this Windows project. Confidence: 0.85
- The web app should replicate all (or maximum) features from the Android app — institute admins should be able to control everything via web just like the app. Confidence: 0.70

# Communication
- Respond in English. Do not automatically switch to Bengali even if the user uses Banglish or Bengali words. Confidence: 0.98

# UI/UX
See [ui/ux/taste.md](ui/ux/taste.md)
# Workflow
See [workflow/taste.md](workflow/taste.md)

# Architecture
See [architecture/taste.md](architecture/taste.md)
# UI/UX
- CRM-like entities that carry a phone number (enquiries, student records, contacts) should expose Call/SMS/WhatsApp as direct action buttons on both list cards and detail dialogs — don't just render phone numbers as static text; make them immediately actionable. When a contact action is added to one surface (card), it must also appear on the other (detail dialog) — inconsistency between the two is treated as a bug. Confidence: 0.90
- Status-tracked entities (enquiries with Active/Follow Up/Closed, or any similar workflow) should support a free-text note field where the user can record context — notes must persist across status changes and be editable from the detail dialog. Confidence: 0.80
- Destructive-action confirmation dialogs (delete, irreversible changes) should be polished beyond plain text: show a warning icon (e.g., ⚠ in a red-tinted circle), display the item's key details (ID, amount, period) inside a red-accented card so the user knows exactly what will be lost, use a red-filled confirm button with an explicit label like "Yes, Delete Permanently", and provide a neutral dismiss like "Keep Payment" — not bare "Delete"/"Cancel" text buttons. Confidence: 0.70
- Avoid Unicode box-drawing characters (e.g., ═══, ───) in plain-text receipts or sharable text — they break alignment across different fonts, encodings, and messaging apps (especially WhatsApp/SMS). Use plain ASCII characters like underscores or dashes instead for cross-platform compatibility. Confidence: 0.80
- One-time fee types (admission fees, registration fees, etc.) should get a prominent amber/gold-accented button next to the student name in the collection header, rather than appearing only as a regular due-list item. Clicking it reuses the standard payment flow (discount, partial, receipt). The button auto-hides when the fee is fully paid. Confidence: 0.65
- Avoid redundant navigation surfaces — if the same actions are already reachable through the FAB (floating action button), don't also show them as a separate "Quick Actions" grid or button row on the home screen. Remove duplicate controls to keep the interface clean and visually simpler. Confidence: 0.75
- Dashboard summary/stat cards that display numeric totals (Today Collection, This Month, Lifetime, etc.) should all be consistently clickable — each navigating to a detailed breakdown filtered to that card's scope. Don't make only one or two interactive while others remain static. Partial interactivity across a card group is treated as broken. Confidence: 0.75
- Result/report cards generated as shareable images should use JPEG (JPG) format for universal compatibility across messaging apps (WhatsApp, etc.) rather than PDF-only or plain-text sharing. Confidence: 0.85
- Shareable result/report cards should be comprehensive and professional — include all relevant fields: institute logo/branding, exam name, subject, date, student name/ID, obtained marks, total marks, grade, merit position, pass/fail status, percentage with visual progress indicator, and generation timestamp. Do not ship minimal/sparse designs that omit key data points the user would expect to see on a marksheet. Confidence: 0.80
See [ui/ux/taste.md](ui/ux/taste.md)
- Admin panel contact action buttons (WhatsApp, Call, SMS) on entity cards should be prominent, labeled, full-width OutlinedButtons — not tiny bare icon-only affordances. Contact actions are primary interaction points, not decorative annotations. Confidence: 0.70
- Admin entity detail dialogs/sheets should include per-entity payment/transaction history fetched from the relevant backend collection (Firestore), showing key fields (plan/product, amount, payment method, transaction ID, approval date) chronologically — not just aggregate dashboard-level revenue stats. Confidence: 0.95
- Payment/transaction history lists should be presented as a serialized visual timeline: descending sequence numbers (latest = 1 at top), timeline connector lines between entries, color-coded badges/chips distinguishing trial vs. paid vs. current status, full date ranges (not cryptic short formats), and the plan name, duration, payment method, and formatted amount all visible inline at a glance. Confidence: 0.80
- Admin-panel-initiated WhatsApp messages should include a clear platform/origin identifier in the message template so recipients immediately know who is contacting them (e.g., "Greetings from [Platform] Admin Panel") — don't use vague or personal-sounding openers like "Hi, this is [person]." Confidence: 0.70
- Payment receipts (both text and PDF) must show the complete amount decomposition chain when a discount was applied: Original/Base Amount → Discount (with both percentage and absolute, e.g., "Discount (20%) — - BDT 200") → Net Payable → Paid → Due. Each field must be sourced from the correct original value — the discount absolute amount must come from the original base amount, never recomputed by applying the discount rate to the already-discounted net/payable amount. Hiding any link in this chain (e.g., omitting base amount and only showing net payable) is treated as incomplete. Confidence: 0.85

- All receipt rendering formats (screen card, PDF, shareable bitmap/image) must derive their amount fields from the same source data/model fields — do not compute differently for different output formats. When a bug exists in one format's calculation, audit and fix all formats in the same pass. Confidence: 0.80
- For data/report screens with grouped time-series data, prefers hierarchical drill-down navigation: top-level summary card → time-grouped breakdown (months → days) → individual record list. Each group row is clickable to drill deeper, with a back button to navigate back up through levels. Confidence: 0.80
- Avoid misleading or inflated UI labels — don't badge a simple arithmetic calculation (e.g., `activeCount × avgPlanPrice`) as "PREDICTION · AI" or similar; use honest, descriptive labels like "MONTHLY ESTIMATE" that accurately reflect what the number represents. Confidence: 0.70
# Workflow
- Only build APK (`assembleRelease`) when the user explicitly asks for it. Do not auto-build after every code change. Confidence: 0.85
- All user-facing features (admin, staff, student, etc.) must live in a single consolidated Android app — never split into separate APKs or modules. Use role-based routing/navigation within the same app to show different UI to different user types. Confidence: 0.95
- Student (or any secondary role) login should be a button/link on the main AuthScreen, not a separate app — the main login screen serves as the universal entry point for all roles. Confidence: 0.90
- When working on the MAIN BatchFee app, do NOT touch `web_form/` directory — it is a protected scope. The same applies in reverse: web panel work should not modify Android app code, Gradle files, Firebase rules, or root project files. Confidence: 0.90
