# BatchFee Student App — সম্পূর্ণ প্ল্যান ডকুমেন্ট

**তারিখ:** ২১ জুন, ২০২৬  
**প্রস্তুতকারী:** BatchFee Dev Team  
**স্ট্যাটাস:** প্ল্যানিং ফেজ (কোনো কোড রাইট করা হয়নি)

---

## ১. প্রজেক্ট ওভারভিউ

### সমস্যা
বর্তমান BatchFee অ্যাপটি **Institute Owner**-দের জন্য ডিজাইন করা। কিন্তু **ছাত্ররা** তাদের নিজস্ব তথ্য (ফি, রেজাল্ট, অ্যাটেন্ডেন্স, হোমওয়ার্ক) দেখতে পারে না — সবকিছুOwner-কে গিয়ে দেখাতে হয়, যা অদক্ষ এবং সময়সাপেক্ষ।

### সমাধান
একটি আলাদা **BatchFee Student** অ্যাপ তৈরি করা হবে, যেখানে Owner ছাত্রদের একটি **Student ID + Password** দেবে। সেই credentials ব্যবহার করে ছাত্ররা লগইন করে তাদের ব্যক্তিগত ড্যাশবোর্ড দেখতে পারবে।

### টার্গেট প্ল্যাটফর্ম
- **এখন:** Android Native (Kotlin + Jetpack Compose) — Admin অ্যাপের মতোই
- **ভবিষ্যৎ:** iOS ভার্সন (Flutter বা Kotlin Multiplatform)

---

## ২. টেক স্ট্যাক (প্রস্তাবিত)

| লেয়ার | টেকনোলজি | কারণ |
|--------|-----------|------|
| **ল্যাঙ্গুয়েজ** | Kotlin | Admin অ্যাপের সাথে consistency |
| **UI** | Jetpack Compose + Material3 | Admin অ্যাপের মতোই, কম্পোনেন্ট reuse করা যাবে |
| **লোকাল ডাটাবেস** | Room | Offline support |
| **ব্যাকএন্ড** | Firebase Firestore (existing) | নতুন ব্যাকএন্ড লাগবে না, same DB |
| **অথেনটিকেশন** | Firebase Auth (custom token) | Owner তৈরি করে দেবে credentials |
| **ইমেজ লোডিং** | Coil Compose | Admin অ্যাপের মতো |
| **নেভিগেশন** | Jetpack Navigation Compose | Type-safe routes |
| **মিন SDK** | 24 (Android 7.0) | Admin অ্যাপের সাথে match |
| **টার্গেট SDK** | 36 (Android 14) | Latest |

### 🔑 কী পয়েন্ট: Admin App-এর সাথে Relation
- **একই Firestore Database** ব্যবহার করবে (same data)
- **Student অ্যাপ শুধু READ-only** হবে core ডাটাতে (payment করা যাবে না)
- Student অ্যাপে **Write permission** শুধু student-এর নিজের profile update-এর জন্য
- Admin অ্যাপ থেকে Student credentials generate করা হবে

---

## ৩. অথেনটিকেশন সিস্টেম

### ৩.১ ফ্লো
```
Admin App: Owner "Add Student" → Auto-generate Student ID + Password
                ↓
Owner ছাত্রকে ID + Password দেয় (WhatsApp/print)
                ↓
Student App: Student লগইন করে (Student ID + Password)
                ↓
Firebase Auth: Custom authentication (email = studentId@student.batchfee.app)
                ↓
Student Dashboard Load হয় (শুধু ওই student-এর ডাটা)
```

### ৩.২ Student Credentials Management
- Admin অ্যাপের **Student Add/Edit** screen-এ নতুন ফিল্ড যোগ হবে: `isAppAccessEnabled`, `studentPassword`
- Password হবে initial-এ auto-generated, পরে Owner change করতে পারবে
- **Password reset** — Owner করতে পারে, Student নিজে করতে পারবে না (নিরাপত্তার জন্য)
- Firebase Auth-এ student accounts হবে **email-based**: `{studentCode}.{instituteCode}@student.batchfee.app`

### ৩.৩ Role-Based Access
- **Firestore Security Rules** আপডেট করতে হবে:
  - Students শুধু তাদের নিজের document read করতে পারবে
  - Students শুধু read-only access পাবে (fees, attendance, results, exams)
  - Students তাদের নিজের profile photo/phone update করতে পারবে (limited write)

### ৩.৪ Session Management
- Student অ্যাপে **no auto-logout** (ক্লাস চলাকালীন বারবার লগইন না করানোর জন্য)
- তবে **biometric lock** দেওয়া যাবে (app lock)
- Multiple device থেকে একই account ব্যবহার করা যাবে

---

## ৪. স্টুডেন্ট ড্যাশবোর্ড (হোম স্ক্রিন)

নিচের কম্পোনেন্টগুলো ড্যাশবোর্ডে থাকবে:

```
┌─────────────────────────────────────┐
│          Good Morning, রাহাত!        │
│             Batch: SSC 2026         │
├─────────────────────────────────────┤
│  ┌──────────┐  ┌──────────┐         │
│  │ ফি স্ট্যাটাস │  │ অ্যাটেন্ডেন্স │         │
│  │ Due: 0 টাকা │  │ ৯৫% উপস্থিত  │         │
│  └──────────┘  └──────────┘         │
│  ┌──────────┐  ┌──────────┐         │
│  │ পরীক্ষার ফল │  │  মেরিট লিস্ট │         │
│  │ GPA: ৫.০০  │  │ Rank #৩    │         │
│  └──────────┘  └──────────┘         │
│  ┌──────────┐  ┌──────────┐         │
│  │  হোমওয়ার্ক │  │  NOTICE   │         │
│  │ ৩ pending │  │ ২ নতুন    │         │
│  └──────────┘  └──────────┘         │
├─────────────────────────────────────┤
│  Bottom Nav: ড্যাশবোর্ড | ফি |       │
│  ক্লাসরুম | প্রোফাইল | মোর          │
└─────────────────────────────────────┘
```

---

## ৫. ডিটেইলড ফিচার লিস্ট

### ৫.১ ফি ম্যানেজমেন্ট
| ফিচার | ডিটেলস |
|--------|---------|
| **ফি স্ট্যাটাস** | মোট ফি, পেইড, Due — সহজ visualization |
| **মনথলি ব্রেকডাউন** | মাস-ভিত্তিক ফি তালিকা (কোন মাস পেইড, কোনটা বাকি) |
| **পেমেন্ট রিসিট দেখুন** | সমস্ত পেমেন্টের রিসিট PDF/Image হিসেবে |
| **রিসিট ডাউনলোড** | প্রিন্ট বা ডাউনলোড অপশন (PDF) |
| **ডিউ নোটিফিকেশন** | ফি বকেয়া থাকলে red alert |

### ৫.২ অ্যাটেন্ডেন্স
| ফিচার | ডিটেলস |
|--------|---------|
| **মাসিক অ্যাটেন্ডেন্স ক্যালেন্ডার** | Present/Absent/Late — color coded |
| **পরিসংখ্যান** | মোট ক্লাস, উপস্থিত, অনুপস্থিত, উপস্থিতির % |
| **মাসভিত্তিক রিপোর্ট** | প্রতি মাসের সারসংক্ষেপ |
| **কোর্সওয়াইজ অ্যাটেন্ডেন্স** | আলাদা ব্যাচ/বিষয় অনুযায়ী |

### ৫.৩ পরীক্ষা ও রেজাল্ট
| ফিচার | ডিটেলস |
|--------|---------|
| **পরীক্ষার তালিকা** | আসন্ন + সম্পন্ন পরীক্ষা |
| **পূর্ণাঙ্গ রেজাল্ট** | মার্কশিট (প্রাপ্ত নম্বর, মোট নম্বর, গ্রেড) |
| **সাবজেক্টওয়াইজ ব্রেকডাউন** | বিষয়ভিত্তিক প্রাপ্ত নম্বর |
| **জিপিএ ও গ্রেড** | Overall GPA with grade |
| **রেজাল্ট কার্ড ডাউনলোড** | PDF/Image |

### ৫.৪ মেরিট লিস্ট
| ফিচার | ডিটেলস |
|--------|---------|
| **ব্যাচ মেরিট লিস্ট** | নিজ ব্যাচের সকল ছাত্রের র ranking |
| **পজিশন হাইলাইট** | নিজ পজিশন বিশেষভাবে দেখানো |
| **সাবজেক্টওয়াইজ মেরিট** | বিষয়ভিত্তিক অবস্থান |
| **ফিল্টার** | টার্ম/পরীক্ষা অনুযায়ী ফিল্টার |

### ৫.৫ হোমওয়ার্ক
| ফিচার | ডিটেলস |
|--------|---------|
| **হোমওয়ার্ক লিস্ট** | তারিখ, বিষয়, ডেডলাইন সহ |
| **স্ট্যাটাস** | Pending/Submitted/Overdue |
| **আটাচমেন্ট** | Teacher দেওয়া ফাইল/ইমেজ দেখা |
| **সাবমিট** | (Future) উত্তর জমা দেওয়ার অপশন |
| **ডিউ ডেট রিমাইন্ডার** | জরুরি homework-এর notification |

### ৫.৬ নোটিশ বোর্ড
| ফিচার | ডিটেলস |
|--------|---------|
| **ইনস্টিটিউট নোটিশ** | Owner/Teacher পোস্ট করা notice |
| **ব্যাচ নোটিশ** | শুধু নির্দিষ্ট ব্যাচের ছাত্রদের জন্য |
| **ইমার্জেন্সি নোটিশ** | Popup আকারে জরুরি notice |

### ৫.৭ প্রোফাইল সেকশন
| ফিচার | ডিটেলস |
|--------|---------|
| **ব্যক্তিগত তথ্য** | নাম, ছবি, রক্তগ্রুপ, জন্মতারিখ |
| **গার্ডিয়ান ইনফো** | বাবা/মার নাম, ফোন, ইমেইল |
| **ইনস্টিটিউট ইনফো** | কোচিংয়ের নাম, ঠিকানা, লোগো |
| **ব্যাচ ডিটেলস** | কোন ব্যাচে, রুটিন, টিচারের নাম |
| **প্রোফাইল এডিট** | শুধু ফোন নাম্বার ও ছবি change (নিজে) |
| **লগআউট** | অ্যাকাউন্ট থেকে বের হওয়া |

### ৫.৮ রুটিন
| ফিচার | ডিটেলস |
|--------|---------|
| **ক্লাস রুটিন** | দিন/সময়ভিত্তিক routine |
| **টিচার ইনফো** | কোন subject কে পড়ান |
| **নেক্সট ক্লাস** | Dashboard-এ upcoming class দেখানো |

---

## ৬. স্ক্রিন লিস্ট (Navigation Structure)

```
NavGraph
├── SplashScreen
├── LoginScreen
│   ├── Student ID field
│   ├── Password field
│   ├── Login button
│   └── Biometric option (optional)
│
├── MainScaffold (Bottom Navigation)
│   ├── DashboardScreen (Home)
│   │   ├── Profile summary card
│   │   ├── Quick stats cards (Fee, Attendance, Rank)
│   │   ├── Upcoming (exam, homework deadline)
│   │   └── Recent notices
│   │
│   ├── FeesScreen
│   │   ├── FeeSummaryScreen (overall status)
│   │   ├── FeeDetailScreen (month-wise breakdown)
│   │   └── ReceiptViewerScreen (PDF/print)
│   │
│   ├── ClassroomScreen
│   │   ├── AttendanceScreen (calendar view + stats)
│   │   ├── ExamResultScreen (list + detail)
│   │   ├── MeritListScreen
│   │   ├── HomeworkScreen (list + detail)
│   │   └── RoutineScreen
│   │
│   ├── ProfileScreen
│   │   ├── Personal info view
│   │   ├── Institute info view
│   │   ├── Edit profile (limited fields)
│   │   └── Logout
│   │
│   └── MoreScreen (overflow)
│       ├── NoticesScreen
│       ├── About / App Info
│       ├── Privacy Policy
│       └── Contact / Support
│
└── WebView screens (for external links)
```

---

## ৭. ফায়ারবেস সিকিউরিটি রুলস (Student Access)

Admin অ্যাপের পাশাপাশি Student অ্যাপের জন্য Firestore rules আপডেট করতে হবে:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Student access: শুধু নিজের ইনস্টিটিউটের ডাটা
    match /institutes/{instituteId} {

      // Students collection - নিজের ডকুমেন্ট read + limited write
      match /students/{studentId} {
        allow read: if request.auth != null
                    && request.auth.uid == studentId;
        allow write: if request.auth != null
                    && request.auth.uid == studentId
                    && request.resource.data.diff(resource.data).affectedKeys()
                       .hasOnly(['phone', 'photoUri', 'address', 'updatedAtMs']);
      }

      // Fees - শুধু নিজের fee read
      match /fees/{feeId} {
        allow read: if request.auth != null
                    && resource.data.studentId == request.auth.uid;
      }

      // Payments - শুধু নিজের payment read
      match /payments/{paymentId} {
        allow read: if request.auth != null
                    && resource.data.studentId == request.auth.uid;
      }

      // Attendance - শুধু নিজের attendance read
      match /attendance/{attendanceId} {
        allow read: if request.auth != null
                    && resource.data.studentId == request.auth.uid;
      }

      // Results - শুধু নিজের result read
      match /results/{resultId} {
        allow read: if request.auth != null
                    && resource.data.studentId == request.auth.uid;
      }

      // Exams - পড়তে পারবে (batch filter করে শুধু relevant)
      match /exams/{examId} {
        allow read: if request.auth != null;
      }

      // Homework - (new collection) read access for own batch
      match /homework/{homeworkId} {
        allow read: if request.auth != null;
      }

      // Notices - (new collection) read access
      match /notices/{noticeId} {
        allow read: if request.auth != null;
      }

      // Batch_students - নিজের enrollment check
      match /batch_students/{batchStudentId} {
        allow read: if request.auth != null
                    && resource.data.studentId == request.auth.uid;
      }
    }
  }
}
```

---

## ৮. নিউ ফায়ারবেস ডাটা মডেল (যোগ করতে হবে)

### ৮.১ homeworks (নতুন collection)
```
institutes/{instituteId}/homeworks/{homeworkId}
{
  id: String,
  batchId: String,
  subject: String,
  title: String,
  description: String,
  attachmentUrl: String?,
  assignedDateMs: Long,
  deadlineDateMs: Long,
  createdAtMs: Long,
  createdByUserId: String
}
```

### ৮.২ notices (নতুন collection)
```
institutes/{instituteId}/notices/{noticeId}
{
  id: String,
  title: String,
  body: String,
  targetBatchIds: Array<String>?, // null = all batches
  priority: String, // "normal" | "emergency"
  attachmentUrl: String?,
  createdAtMs: Long,
  createdByUserId: String
}
```

### ৮.৩ students-এ নতুন ফিল্ড (existing collection-এ add)
```
students/{studentId} (existing document-এ add)
{
  // ... existing fields ...
  "isAppAccessEnabled": Boolean,     // false = can't login
  "lastLoginAtMs": Long?,
  "fcmToken": String?                // for push notification
}
```

### ৮.৪ ফায়ারবেস অথে student accounts
- Admin অ্যাপ student add করার সময় `FirebaseAuthApi.kt`-এর মতো REST API কল করে Firebase Auth-এ student account তৈরি করবে
- Student ID হবে: `{instituteCode}_{studentCode}@s.batchfee.app`
- Password encrypt করে Firestore-এ student doc-এ store (owner দেখতে পারে)

---

## ৯. অ্যাডমিন অ্যাপে যেসব চেঞ্জ লাগবে

| চেঞ্জ | কোথায় | বিস্তারিত |
|-------|--------|-----------|
| **Student Add/Edit-এ "App Access" টগল** | `ui/students/AddEditStudentScreen.kt` | চেকবক্স: "অ্যাপ অ্যাক্সেস দিন" + auto-generate password |
| **Student Profile-এ "App Credentials" সেকশন** | `ui/students/StudentProfileScreen.kt` | Student ID + Password দেখাবে, reset button |
| **Firebase Auth student account তৈরি** | `data/repository/StudentRepository.kt` | REST API call করে student Firebase Auth user create |
| **Homework Management UI** | নতুন screen | Admin অ্যাবে homework create/edit/delete করার UI |
| **Notice Management UI** | নতুন screen | Admin অ্যাবে notice create করার UI |
| **Student Login History** | Student Profile-এ | Last login time, device info |

---

## ১০. ইমপ্লিমেন্টেশন ফেজ

### ফেজ ১: ফাউন্ডেশন (Week 1-2)
1. নতুন Android 프로젝্ট তৈরি `BatchFeeStudent`
2. Gradle setup (dependencies, minSdk, etc.)
3. Firebase project connection (existing project)
4. Authentication flow (Student login with Firebase Auth)
5. Navigation framework setup
6. Theme & Design System (Admin app-এর সাথে consistent)

### ফেজ ২: কোর ফিচার (Week 3-4)
1. **Dashboard Screen** — cards with quick stats
2. **Profile Screen** — student info display + edit
3. **Fee Section** — summary + month-wise + receipt view/download
4. **Attendance Section** — calendar view + statistics
5. Offline-first with Room caching

### ফেজ ৩: একাডেমিক ফিচার (Week 5-6)
1. **Exam Results** — marksheet view + download
2. **Merit List** — ranking with position highlight
3. **Homework** — list + detail + attachment view
4. **Class Routine** — weekly schedule view

### ফেজ ৪: কমিউনিকেশন ও পলিশ (Week 7-8)
1. **Notice Board** — institute + batch notices
2. **Push Notifications** — FCM integration
3. **Biometric App Lock** — extra security
4. **PDF Generation** — receipts, results, ID card
5. **UI Polish** — animations, loading states, error handling
6. **Testing** — unit tests, UI tests, security audit
7. **শেষে:** Admin app-এ Student credentials management + Homework/Notice CRUD implement

### ফেজ ৫: অ্যাডমিন অ্যাপ আপডেট (সমান্তরাল)
1. Student Add/Edit-এ "App Access" ফিচার
2. Homework Management screens
3. Notice Management screens
4. Firestore rules update
5. Admin app-এ "Student App Access" রিপোর্ট

---

## ১১. Student App APK Size Estimate

| কম্পোনেন্ট | আনুমানিক সাইজ |
|-----------|---------------|
| Base Kotlin + Compose | ~3 MB |
| Firebase SDKs | ~2.5 MB |
| Room + SQLite | ~0.5 MB |
| Coil + OkHttp | ~0.5 MB |
| PDF generator | ~0.5 MB |
| Resources (icons, fonts) | ~1 MB |
| **Total Estimated** | **~8-10 MB** |

Admin app-এর চেয়ে ছোট হবে কারণ অনেক কম ফিচার এবং কোড।

---

## ১২. পসিবল চ্যালেঞ্জেস ও সমাধান

| চ্যালেঞ্জ | সমাধান |
|-----------|---------|
| **Student ID + Password সুরক্ষা** | Firebase Auth-এ custom claims ব্যবহার করে student role enforce করা |
| **একাধিক student একই device use করলে session conflict** | প্রতিবার লগইনে session clear |
| **Offline mode-এ data দেখা** | Room cache — last sync-এর data দেখাবে |
| **Admin app-এ student credential manage করা** | Dedicated section in student profile |
| **Receipt PDF generation** | Android Print API বা iText library |
| **Firestore read quota** | Room cache + optimization (শুধু needed fields query) |
| **Student account misuse** | Owner deactivate করতে পারবে "App Access" toggle দিয়ে |

---

## ১৩. Recommend করা ফ্লো (Admin App → Student App)

```
Admin App-এ:
1. Owner "Add Student" screen-এ যায়
2. Student information fill করে
3. "App Access দিন" চেকবক্স on করে
4. System auto-generate করে:
   - Student ID: {instituteCode}_{studentCode}
   - Password: random 6-digit
5. Firebase Auth-এ account তৈরি (REST API)
6. Student doc-এ credential store
7. Owner print/share করে student কে

Student App-এ:
1. Student app install করে
2. Login screen-এ Student ID + Password দেয়
3. Firebase Auth verify করে
4. Firestore থেকে নিজের data load করে
5. Dashboard দেখায়
```

---

## ১৪. কোড শেয়ারিং স্ট্র্যাটেজি (Admin ↔ Student)

Admin app আর Student app-এ কিছু কোড কমন থাকবে:

**Share করা যাবে:**
- Data models (entities) — copy করে নিতে হবে (বা common module)
- Firestore helpers (sync logic) — similar structure
- Theme (color, typography) — consistent look
- Utility functions (date format, number format)

**Separate রাখতে হবে:**
- ViewModels (logic আলাদা)
- Screens (UI completely different)
- Navigation (different nav graph)
- Repositories (different access patterns)

**Rec:** আলাদা project রাখা (monorepo-তে), common code এর জন্য `:shared` module তৈরি করা।

---

## ১৫. উপসংহার

BatchFee Student App **সম্ভব এবং বাস্তবসম্মত** — কারণ:

1. ✅ **একই Firebase backend** use করবে — নতুন সার্ভার লাগবে না
2. ✅ **Admin app-এর ডাটা** directly use করবে — data duplication নেই
3. ✅ **Firebase Security Rules** দিয়ে secure access possible
4. ✅ **Kotlin + Jetpack Compose** — একই টেক স্ট্যাক, টিমের existing skill use
5. ✅ **Read-only architecture** — data corruption chance কম
6. ✅ **Offline-first** — Room cache দিয়ে slow internet-এও কাজ করবে
7. ✅ **Admin app-এ ন্যূনতম চেঞ্জ** — শুধু student credential management + homework/notice

**মেইন ইমপ্যাক্ট:**
- Owner-এর কাজ কমবে (ছাত্ররা নিজেই দেখবে)
- ছাত্রদের transparency বাড়বে
- কম্পিটিটিভ এজ বাড়বে (merit list, ranking)
- পেমেন্ট কালেকশন স্পিড বাড়বে (receipt随时随地 দেখা)
