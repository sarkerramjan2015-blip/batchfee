# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# Workflow
- Use `.\\gradlew.bat` (not `./gradlew`) for Gradle commands on this Windows project. Confidence: 0.85
- The web app should replicate all (or maximum) features from the Android app — institute admins should be able to control everything via web just like the app. Confidence: 0.70

# Communication
- Respond in English. Do not automatically switch to Bengali even if the user uses Banglish or Bengali words. Confidence: 0.98

# UI/UX
- When generating PDF receipts/documents that include an institute logo, use the actual logo image (if available) as a large faded center watermark — prefer the real image over text-based or initials-based fallbacks for decorative/watermark elements. Confidence: 0.85
- PDF receipts must include a footer with the institute's contact phone number and a "For any query contact: [phone]" line so recipients know how to reach the institute. Confidence: 0.85
- When displaying remaining/outstanding due amounts in financial contexts (receipts, invoices, fee summaries), use red (e.g., #EF4444) for non-zero dues to signal urgency, and green for fully settled (zero) states — do not use amber/orange for due amounts. Confidence: 0.85
- Display institute logos in a circular crop (using clipPath/circle mask) rather than rounded-rectangle containers — the round shape looks cleaner and more polished. Confidence: 0.75

# Workflow
See [workflow/taste.md](workflow/taste.md)

# Architecture
See [architecture/taste.md](architecture/taste.md)
# UI/UX
- CRM-like entities that carry a phone number (enquiries, student records, contacts) should expose Call/SMS/WhatsApp as direct action buttons on both list cards and detail dialogs — don't just render phone numbers as static text; make them immediately actionable. Confidence: 0.80
- Status-tracked entities (enquiries with Active/Follow Up/Closed, or any similar workflow) should support a free-text note field where the user can record context — notes must persist across status changes and be editable from the detail dialog. Confidence: 0.80
- Destructive-action confirmation dialogs (delete, irreversible changes) should be polished beyond plain text: show a warning icon (e.g., ⚠ in a red-tinted circle), display the item's key details (ID, amount, period) inside a red-accented card so the user knows exactly what will be lost, use a red-filled confirm button with an explicit label like "Yes, Delete Permanently", and provide a neutral dismiss like "Keep Payment" — not bare "Delete"/"Cancel" text buttons. Confidence: 0.70
- Avoid Unicode box-drawing characters (e.g., ═══, ───) in plain-text receipts or sharable text — they break alignment across different fonts, encodings, and messaging apps (especially WhatsApp/SMS). Use plain ASCII characters like underscores or dashes instead for cross-platform compatibility. Confidence: 0.80
- One-time fee types (admission fees, registration fees, etc.) should get a prominent amber/gold-accented button next to the student name in the collection header, rather than appearing only as a regular due-list item. Clicking it reuses the standard payment flow (discount, partial, receipt). The button auto-hides when the fee is fully paid. Confidence: 0.65
- Avoid redundant navigation surfaces — if the same actions are already reachable through the FAB (floating action button), don't also show them as a separate "Quick Actions" grid or button row on the home screen. Remove duplicate controls to keep the interface clean and visually simpler. Confidence: 0.75
- Dashboard summary/stat cards that display numeric totals (Today Collection, This Month, Lifetime, etc.) should all be consistently clickable — each navigating to a detailed breakdown filtered to that card's scope. Don't make only one or two interactive while others remain static. Partial interactivity across a card group is treated as broken. Confidence: 0.75
- Result/report cards generated as shareable images should use JPEG (JPG) format for universal compatibility across messaging apps (WhatsApp, etc.) rather than PDF-only or plain-text sharing. Confidence: 0.85
- Shareable result/report cards should be comprehensive and professional — include all relevant fields: institute logo/branding, exam name, subject, date, student name/ID, obtained marks, total marks, grade, merit position, pass/fail status, percentage with visual progress indicator, and generation timestamp. Do not ship minimal/sparse designs that omit key data points the user would expect to see on a marksheet. Confidence: 0.80
See [ui/ux/taste.md](ui/ux/taste.md)
- Payment receipts (both text and PDF) must show both the discount percentage and the discount amount (e.g., "Discount: 10% — BDT 150") when a discount was applied — not just one or the other. The percentage is important for the user to understand the rate; the amount shows the actual impact. Confidence: 0.80
- For data/report screens with grouped time-series data, prefers hierarchical drill-down navigation: top-level summary card → time-grouped breakdown (months → days) → individual record list. Each group row is clickable to drill deeper, with a back button to navigate back up through levels. Confidence: 0.80
# Workflow
- Only build APK (`assembleRelease`) when the user explicitly asks for it. Do not auto-build after every code change. Confidence: 0.85
- Do NOT modify the existing BatchFee admin app — it's stable and should not be touched. All new student features go in the separate student app. Confidence: 0.80
- When working on the MAIN BatchFee app, do NOT touch `student_app/` or `web_form/` directories — these are protected scopes that should never be modified during MAIN app work. The same applies in reverse: MAIN app fixes should not spill into student/web code. Confidence: 0.95
- Student app should work fully in demo mode (no auth/login required). Only add authentication when the user explicitly asks for it. Confidence: 0.85
- For web admin panel work, only create or edit files inside web_form/ directory. Do not modify Android app code, student_app, Gradle files, Firebase rules, or root project files unless explicitly approved. Confidence: 0.90

