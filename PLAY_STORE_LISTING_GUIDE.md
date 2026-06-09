# 📱 BatchFee — Google Play Store Publishing Guide

> **Version:** 1.3 (versionCode 4)
> **Updated:** June 9, 2026
> **For:** Senior Developer (Oni Bhai) — Play Store Console Publisher

---

## 📦 App Bundle / APK

### Option 1: APK (Already Built)
```
app/build/outputs/apk/release/app-release.apk
```
Keystore: `app/batchfee-release.jks`
- Alias: `batchfee`
- Password: `batchfee123`

### Option 2: Android App Bundle (.aab) — RECOMMENDED by Google
Run this command:
```
.\gradlew.bat bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`

> ⚠️ **AAB is preferred over APK.** Google Play uses AAB to generate optimized APKs for different devices. Smaller download = more installs.

---

## 🔗 Required URLs (MUST enter in Play Console)

| Field | URL |
|-------|-----|
| **Privacy Policy** | `https://batchfee-477b8.web.app/privacy-policy.html` |
| **Terms & Conditions** | `https://batchfee-477b8.web.app/terms.html` |
| **Website** | `https://github.com/sarkerramjan2015-blip/batchfee` |

> ⚠️ **Privacy Policy URL is MANDATORY.** Google won't let you publish without it. These pages are already created in `web_form/` — deploy to Firebase Hosting before submitting.

### Deploy to Firebase Hosting
```
firebase deploy --only hosting
```
Or if firebase CLI not installed:
```
npm install -g firebase-tools
firebase login
firebase deploy --only hosting
```

---

## 🏪 Play Store Listing

### App Title (30 chars max)
```
BatchFee - Institute Manager
```

### Short Description (80 chars max)
```
Complete institute management: students, fees, attendance, exams & staff payroll.
```

### Full Description (4000 chars max — paste the following)

```
BatchFee is the all-in-one SaaS platform for coaching centers, private tutors, academies, and schools. Manage your entire institute from a single app — no spreadsheets, no paper registers, no hassle.

══ FEATURES ══

📚 STUDENT MANAGEMENT
• Complete student profiles with photos, guardian info, and emergency contacts
• Student ID card generator with QR-style display
• Automatic birthday reminders with WhatsApp/SMS integration
• Web registration form — students can submit inquiries online
• Archive and restore student records

📊 BATCH & CLASS MANAGEMENT
• Create unlimited batches with custom subjects, schedules, and fees
• Enroll/unenroll students with one tap
• Track batch-wise attendance and fee collection
• Set maximum student limits per batch

💰 SMART FEE COLLECTION
• Create individual or bulk fees with discount and late fee support
• Track paid, due, and overdue amounts per student
• Multiple payment methods: Cash, bKash, Nagad, Bank Transfer
• Print and share professional receipts (PDF)
• Due fee reminders via WhatsApp and SMS templates
• Today's collection dashboard with real-time totals

✅ ATTENDANCE TRACKING
• Mark student attendance: Present / Absent / Late
• Staff attendance tracking with detailed reports
• Send automated absent notifications to guardians
• Monthly attendance percentage reports

👥 STAFF MANAGEMENT
• Add teachers, accountants, and other staff roles
• Granular permission control (18 individual permissions)
• Monthly salary generation with bonus, deduction, and advance
• Staff attendance and salary slip reports

📝 EXAMS & RESULTS
• Schedule exams by batch and subject
• Record marks, calculate grades, and assign positions
• Publish results with one tap
• Performance reports by student and batch

💸 EXPENSE TRACKING
• Log expenses by category with receipt photo attachments
• Track today/month/lifetime expense summaries
• Profit & Loss report: Income vs Expenses

📢 COMMUNICATION
• SMS and WhatsApp templates with student name/amount placeholders
• Send fee reminders, birthday wishes, and exam alerts
• Global notification board for announcements

🔒 SECURITY & ACCESS
• Email/password login with Firebase Authentication
• Biometric (fingerprint/face) unlock support
• Role-based access: SuperAdmin, Owner, Admin, Staff
• 60-second inactivity auto-logout
• Firebase App Check (Play Integrity) ready

☁️ CLOUD & BACKUP
• Institute and staff data synced to Firebase Cloud
• Export all data to CSV (students, fees, expenses, staff, enquiries)
• Firestore security rules for data protection
• Offline support — works without internet

🎯 DEMO MODE
Try before you commit! Demo mode includes 20 sample students, 5 batches, and realistic data across all modules.

══ PERFECT FOR ══
• Coaching centers and tuition academies
• Private tutors managing multiple batches
• Madrasas and religious schools
• Music, art, and skill development institutes
• Any institute that collects monthly fees from students

══ SUBSCRIPTION PLANS ══
Free trial available for new institutes. Upgrade to Starter, Growth, Pro, or Enterprise plans for more students, staff, and advanced features.

Download BatchFee today and take your institute management to the next level!
```

---

## 🖼️ Screenshots & Graphics

### Minimum requirements for Play Store:

| Graphic | Size | Format | Currently Have |
|---------|------|--------|---------------|
| **Feature Graphic** | 1024 × 500 px | PNG/JPG (no alpha) | ❌ Need to create |
| **App Icon** | 512 × 512 px | PNG (32-bit) | ❌ Need to create |
| **Screenshots** (2-8) | Min 320px, Max 3840px on longest side | PNG/JPG (no alpha) | ⚠️ `promotional content/` folder has some |

### Required Screenshot Types (take on a clean device):
1. **Dashboard** — main home screen with stats and cards
2. **Student List** — showing student management
3. **Fee Collection** — payment screen with amounts
4. **Attendance** — marking attendance screen
5. **Batch Management** — batch list with student counts
6. **Reports** — profit/loss or today's collection
7. **Staff Management** — staff list or salary screen

### How to take screenshots:
1. Install the release APK on a phone
2. Go to each screen listed above
3. Take screenshots (1080×1920 or higher)
4. Place in `promotional content/` folder

---

## 🔞 Content Rating Questionnaire

Open Play Console → Select App → **Policy → App content → Content rating → Start questionnaire**

Answers:
- **Violence:** No
- **Sexuality:** No
- **Language:** No
- **Controlled Substances:** No (alcohol, tobacco, drugs)
- **Hate Speech:** No
- **Gambling:** No
- **User Interaction:** Yes (user-generated content — institutes enter student data)
- **Shares Location:** No
- **Digital Purchases:** No (subscriptions handled outside Play Store)

This will likely result in: **Everyone** or **Teen** rating.

---

## 🎯 Target Audience & Content

Play Console: **Policy → App content → Target audience and content**

Check these:
- ✅ "Not designed for children" (app used by institute owners/admins, not kids directly)
- Age: **18 and over** (or **13-17 with parental consent** — your choice, but 18+ is safer)

---

## 🛡️ Data Safety Section (IMPORTANT)

Play Console: **Policy → App content → Data safety**

Based on our analysis, here's what to declare:

### Data Collected (check these):
| Data Type | Collected? | Shared? | Encrypted? | Deletable? |
|-----------|-----------|---------|------------|------------|
| Name | ✅ Yes | ❌ No | ✅ Yes (in transit) | ✅ Yes |
| Email address | ✅ Yes | ❌ No | ✅ Yes (in transit) | ✅ Yes |
| Phone number | ✅ Yes | ❌ No | ✅ Yes (in transit) | ✅ Yes |
| Address | ✅ Yes | ❌ No | ✅ Yes (in transit) | ✅ Yes |
| Photos | ✅ Yes | ❌ No | ✅ Yes (in transit) | ✅ Yes |
| App performance (Crashlytics) | ✅ Yes | ❌ No | ✅ Yes | N/A |
| Device ID (App Check) | ✅ Yes | ❌ No | ✅ Yes | N/A |

### Data NOT Collected:
- Location ❌ (no permission requested)
- Files and docs ❌ (photos stay on-device)
- Contacts ❌
- SMS ❌
- Financial info ❌ (payments handled externally)

### Data Purposes:
- Account management
- App functionality
- Analytics (Crashlytics)

---

## 🎨 App Category

- **Category:** Education
- **Tags:** Education Management, Institute Management

---

## 📋 Pre-Publish Checklist

- [ ] **Deploy Privacy Policy & Terms to Firebase Hosting** (`firebase deploy --only hosting`)
- [ ] **Verify URLs work:**
  - https://batchfee-477b8.web.app/privacy-policy.html
  - https://batchfee-477b8.web.app/terms.html
- [ ] **Build AAB:** `.\gradlew.bat bundleRelease`
- [ ] **Take 5-7 screenshots** on a real device (1080×1920 minimum)
- [ ] **Create Feature Graphic** (1024×500) — batchfee logo + tagline
- [ ] **Fill Content Rating Questionnaire**
- [ ] **Fill Data Safety Section**
- [ ] **Set target audience to 18+**
- [ ] **Upload AAB and submit for review**
- [ ] **Enable Firebase App Check** (currently disabled — search `AppCheck` in code)

---

## ⚠️ Important Notes

1. **Keystore is critical.** If you lose `batchfee-release.jks`, you cannot update the app on Play Store. Back it up securely.

2. **Don't upload APK directly.** Use `.aab` (Android App Bundle) — Google requires it for new apps.

3. **Firebase App Check** is currently commented out in code (`// TODO: RE-ENABLE BEFORE PLAY STORE UPLOAD`). Enable it before publishing for better security.

4. **Demo accounts** are hardcoded. Remove or disable them before production release if you want a clean experience.

5. **The web registration form** at `web_form/register.html` uses the same Firebase API key — ensure Firebase Security Rules are properly deployed.

---

## 📞 Support Contact

- Developer: sarkerramjan2015@gmail.com
- GitHub: https://github.com/sarkerramjan2015-blip/batchfee
