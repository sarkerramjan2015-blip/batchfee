package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.BuildConfig
import com.example.data.dao.InstituteDao
import com.example.data.dao.SubscriptionPlanDao
import com.example.data.dao.UserDao
import com.example.data.models.InstituteEntity
import com.example.data.models.SubscriptionPlanEntity
import com.example.data.models.UserEntity
import com.example.domain.PasswordHasher
import com.example.data.firebase.FirebaseAuthApi
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Database(
    entities = [
        InstituteEntity::class,
        UserEntity::class,
        SubscriptionPlanEntity::class,
        com.example.data.models.StudentEntity::class,
        com.example.data.models.BatchEntity::class,
        com.example.data.models.BatchStudentEntity::class,
        com.example.data.models.AttendanceEntity::class,
        com.example.data.models.FeeEntity::class,
        com.example.data.models.PaymentEntity::class,
        com.example.data.models.ReceiptEntity::class,
        com.example.data.models.ReminderTemplateEntity::class,
        com.example.data.models.StaffEntity::class,
        com.example.data.models.StaffAttendanceEntity::class,
        com.example.data.models.SalaryEntity::class,
        com.example.data.models.ExpenseEntity::class,
        com.example.data.models.ExamEntity::class,
        com.example.data.models.ResultEntity::class,
        com.example.data.models.AuditLogEntity::class,
        com.example.data.models.AbsentMessageEntity::class,
        com.example.data.models.EnquiryEntity::class
    ],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun instituteDao(): InstituteDao
    abstract fun userDao(): UserDao
    abstract fun subscriptionPlanDao(): SubscriptionPlanDao
    abstract fun studentDao(): com.example.data.dao.StudentDao
    abstract fun batchDao(): com.example.data.dao.BatchDao
    abstract fun batchStudentDao(): com.example.data.dao.BatchStudentDao
    abstract fun attendanceDao(): com.example.data.dao.AttendanceDao
    abstract fun feeDao(): com.example.data.dao.FeeDao
    abstract fun paymentDao(): com.example.data.dao.PaymentDao
    abstract fun receiptDao(): com.example.data.dao.ReceiptDao
    abstract fun reminderTemplateDao(): com.example.data.dao.ReminderTemplateDao
    abstract fun staffDao(): com.example.data.dao.StaffDao
    abstract fun staffAttendanceDao(): com.example.data.dao.StaffAttendanceDao
    abstract fun salaryDao(): com.example.data.dao.SalaryDao
    abstract fun expenseDao(): com.example.data.dao.ExpenseDao
    abstract fun examDao(): com.example.data.dao.ExamDao
    abstract fun resultDao(): com.example.data.dao.ResultDao
    abstract fun auditLogDao(): com.example.data.dao.AuditLogDao
    abstract fun absentMessageDao(): com.example.data.dao.AbsentMessageDao
    abstract fun enquiryDao(): com.example.data.dao.EnquiryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes — establishing safe migration path for future versions
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_fees_institute_student ON fees(instituteId, studentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_fees_institute ON fees(instituteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_institute_fee ON payments(instituteId, feeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_batch_students_batch ON batch_students(batchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_batch_students_student ON batch_students(studentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_results_exam ON results(examId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_staff_attendance_staff_date ON staff_attendance(staffId, attendanceDateMs)")
            }
        }

        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE institutes ADD COLUMN phone TEXT")
                db.execSQL("ALTER TABLE institutes ADD COLUMN address TEXT")
                db.execSQL("ALTER TABLE institutes ADD COLUMN profilePhotoUri TEXT")
            }
        }

        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS enquiries (
                        id TEXT NOT NULL PRIMARY KEY,
                        instituteId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        address TEXT,
                        subjectName TEXT NOT NULL,
                        enquiryDateMs INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        archivedAtMs INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_enquiries_institute ON enquiries(instituteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_enquiries_date ON enquiries(enquiryDateMs)")
            }
        }

        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE institutes ADD COLUMN whatsappNumber TEXT")
            }
        }

        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE institutes ADD COLUMN ownerName TEXT")
                db.execSQL("ALTER TABLE institutes ADD COLUMN email TEXT")
            }
        }

        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE institutes ADD COLUMN instituteCode TEXT")
                db.execSQL("ALTER TABLE institutes ADD COLUMN securityPin TEXT")
            }
        }

        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN failedAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE users ADD COLUMN lockedUntilMs INTEGER")
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "batchfee_database"
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)

                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        // Cached real Firebase Auth UIDs — resolved once per process lifetime
        @Volatile
        var realAdminUid: String? = null
        @Volatile
        var realOwnerUid: String? = null

        suspend fun ensureDemoDataSeeded(db: AppDatabase) {
            withContext(Dispatchers.IO) {
                try {
                    // Always ensure Firebase Auth accounts exist and capture real UIDs
                    ensureFirebaseAuthAccounts()

                    val user = db.userDao().getUserByEmail("admin@batchfee.app")
                    if (user == null) {
                        populateInitialPlans(db.subscriptionPlanDao())
                        populateSuperAdmin(db.userDao(), db.instituteDao())
                        populateDemoData(db)
                    }
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }

        private suspend fun ensureFirebaseAuthAccounts() {
            // ── SuperAdmin ──
            try {
                realAdminUid = FirebaseAuthApi.createUser("admin@batchfee.app", "123456")
                // Always write/update Firestore doc at the real UID so isSuperAdmin() works
                try {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("institutes")
                        .document(realAdminUid!!)
                        .set(
                            mapOf(
                                "instituteName" to "BatchFee System",
                                "role" to "SuperAdmin",
                                "email" to "admin@batchfee.app",
                                "createdAt" to System.currentTimeMillis(),
                                "isActive" to true
                            ),
                            com.google.firebase.firestore.SetOptions.merge()
                        ).await()
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            } catch (e: Exception) {
                if ((e.message ?: "").contains("EMAIL_EXISTS", ignoreCase = true)) {
                    // Account already exists — resolve its real UID via sign-in
                    try {
                        realAdminUid = FirebaseAuthApi.signInWithPassword("admin@batchfee.app", "123456")
                        // Ensure Firestore doc exists at the real UID
                        try {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("institutes")
                                .document(realAdminUid!!)
                                .set(
                                    mapOf(
                                        "instituteName" to "BatchFee System",
                                        "role" to "SuperAdmin",
                                        "email" to "admin@batchfee.app",
                                        "isActive" to true
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                ).await()
                        } catch (_: Exception) { }
                    } catch (_: Exception) { }
                } else {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }

            // ── Demo Owner ──
            try {
                realOwnerUid = FirebaseAuthApi.createUser("owner@batchfee.app", "123456")
                try {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("institutes")
                        .document(realOwnerUid!!)
                        .set(
                            mapOf(
                                "instituteName" to "BatchFee Demo Institute",
                                "instituteCode" to "BGS-100",
                                "ownerName" to "Demo Owner",
                                "email" to "owner@batchfee.app",
                                "role" to "owner",
                                "createdAt" to System.currentTimeMillis(),
                                "isActive" to true,
                                "trialEndDate" to (System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000),
                                "studentCount" to 0,
                                "staffCount" to 0
                            ),
                            com.google.firebase.firestore.SetOptions.merge()
                        ).await()
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            } catch (e: Exception) {
                if ((e.message ?: "").contains("EMAIL_EXISTS", ignoreCase = true)) {
                    try {
                        realOwnerUid = FirebaseAuthApi.signInWithPassword("owner@batchfee.app", "123456")
                        try {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("institutes")
                                .document(realOwnerUid!!)
                                .set(
                                    mapOf(
                                        "instituteName" to "BatchFee Demo Institute",
                                        "instituteCode" to "BGS-100",
                                        "ownerName" to "Demo Owner",
                                        "email" to "owner@batchfee.app",
                                        "role" to "owner",
                                        "isActive" to true
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                ).await()
                        } catch (_: Exception) { }
                    } catch (_: Exception) { }
                } else {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }

        suspend fun populateInitialPlans(dao: SubscriptionPlanDao) {
             val plans = listOf(
                 SubscriptionPlanEntity(
                     id = "plan_free_trial",
                     name = "Free Trial",
                     description = "30-day full access free trial",
                     priceBdt = 0.0, priceInr = 0.0,
                     maxStudents = 50, maxBatches = 5, maxUsers = 1, maxBranches = 1,
                     tag = "Trial", tierLevel = 0
                 ),
                 SubscriptionPlanEntity(
                     id = "plan_starter",
                     name = "Starter",
                     description = "For private tutors and small batches",
                     priceBdt = 499.0, priceInr = 399.0,
                     maxStudents = 150, maxBatches = 10, maxUsers = 3, maxBranches = 1,
                     tag = "Basic", tierLevel = 1
                 ),
                 SubscriptionPlanEntity(
                     id = "plan_growth",
                     name = "Growth",
                     description = "For growing coaching centers",
                     priceBdt = 999.0, priceInr = 799.0,
                     maxStudents = 500, maxBatches = 30, maxUsers = 13, maxBranches = 1,
                     tag = "Popular", tierLevel = 2
                 ),
                 SubscriptionPlanEntity(
                     id = "plan_pro",
                     name = "Pro",
                     description = "Professional centers and schools",
                     priceBdt = 1999.0, priceInr = 1499.0,
                     maxStudents = 1500, maxBatches = 100, maxUsers = 60, maxBranches = 1,
                     tag = "Recommended", tierLevel = 3
                 ),
                 SubscriptionPlanEntity(
                     id = "plan_institute",
                     name = "Institute",
                     description = "Large institutes and branches",
                     priceBdt = 4999.0, priceInr = 3999.0,
                     maxStudents = 5000, maxBatches = 300, maxUsers = 999, maxBranches = 5,
                     tag = "Advanced", tierLevel = 4
                 )
             )
             dao.insertPlans(plans)
        }

        suspend fun populateSuperAdmin(userDao: UserDao, instituteDao: InstituteDao) {
            // Use real Firebase Auth UIDs instead of hardcoded fake IDs
            val adminUid = realAdminUid ?: "sys_super_admin_1"
            val ownerUid = realOwnerUid ?: "demo_institute_1"

            userDao.insertUser(
                UserEntity(
                    id = adminUid,
                    instituteId = null,
                    name = "System Admin",
                    email = "admin@batchfee.app",
                    passwordHash = PasswordHasher.hash("123456"),
                    role = "SuperAdmin",
                    createdAtMs = System.currentTimeMillis()
                )
            )

            // Write SuperAdmin doc to Firestore at the REAL UID so isSuperAdmin() works
            try {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("institutes")
                    .document(adminUid)
                    .set(
                        mapOf(
                            "instituteName" to "BatchFee System",
                            "role" to "SuperAdmin",
                            "email" to "admin@batchfee.app",
                            "createdAt" to System.currentTimeMillis(),
                            "isActive" to true
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            
            val demoInstituteId = ownerUid
            val now = System.currentTimeMillis()
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            
            instituteDao.insertInstitute(
                InstituteEntity(
                    id = demoInstituteId,
                    name = "BatchFee Demo Institute",
                    currentPlanId = "plan_pro",
                    subscriptionStatus = "trial",
                    trialStartDateMs = now,
                    trialEndDateMs = now + thirtyDaysMs,
                    currentPeriodEndMs = now + thirtyDaysMs,
                    createdAtMs = now,
                    phone = "+8801712345678",
                    address = "Mirpur-10, Dhaka-1216",
                    whatsappNumber = "+8801712345678",
                    profilePhotoUri = null,
                    ownerName = "Demo Owner",
                    email = "owner@batchfee.app",
                    instituteCode = "BGS-100",
                    securityPin = null
                )
            )
            
            userDao.insertUser(
                UserEntity(
                    id = ownerUid,
                    instituteId = demoInstituteId,
                    name = "Mohammad Ramjan Sarker",
                    email = "owner@batchfee.app",
                    passwordHash = PasswordHasher.hash("123456"),
                    role = "InstituteOwner",
                    createdAtMs = now
                )
            )
        }
        suspend fun populateDemoData(db: AppDatabase) {
            val demoInstituteId = realOwnerUid ?: "demo_institute_1"
            val ownerId = realOwnerUid ?: "demo_owner_1"
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60 * 60 * 1000
            val rng = java.util.Random(42)

            // ── Helper: start-of-day ──
            fun startOfDay(deltaDays: Long): Long {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now + (deltaDays * dayMs)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                return cal.timeInMillis
            }

            // ── Helper: month label ──
            fun monthLabel(deltaMonths: Int): String {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, deltaMonths)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(cal.timeInMillis))
            }

            val currentMonth = monthLabel(0)
            val lastMonth = monthLabel(-1)
            val nextMonth = monthLabel(1)

            // ════════════════════════════════════════════════════════
            // 1.  3 BATCHES
            // ════════════════════════════════════════════════════════
            val batch1 = com.example.data.models.BatchEntity(
                id = "demo_batch_1", instituteId = demoInstituteId,
                batchCode = "HSC27ICT-1", name = "HSC 2027 ICT",
                subject = "ICT", className = "HSC 2nd Year",
                teacherName = "Md. Hasan", monthlyFeeAmount = 1500.0,
                admissionFeeAmount = 500.0,
                startDateMs = now - (180 * dayMs), endDateMs = now + (180 * dayMs),
                scheduleDays = "Sun, Tue, Thu", startTime = "16:00", endTime = "18:00",
                maxStudents = 30, status = "active",
                description = "Full syllabus coverage for HSC 2027 ICT exam",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            val batch2 = com.example.data.models.BatchEntity(
                id = "demo_batch_2", instituteId = demoInstituteId,
                batchCode = "SSC26MATH-1", name = "SSC 2026 Math",
                subject = "Mathematics", className = "SSC",
                teacherName = "Md. Karim", monthlyFeeAmount = 1200.0,
                admissionFeeAmount = 400.0,
                startDateMs = now - (180 * dayMs), endDateMs = now + (180 * dayMs),
                scheduleDays = "Mon, Wed, Sat", startTime = "10:00", endTime = "12:00",
                maxStudents = 25, status = "active",
                description = "SSC 2026 Mathematics — creative & MCQ preparation",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            val batch3 = com.example.data.models.BatchEntity(
                id = "demo_batch_3", instituteId = demoInstituteId,
                batchCode = "HSC27BIO-1", name = "HSC 2027 Biology",
                subject = "Biology", className = "HSC 2nd Year",
                teacherName = "Afsana Rahman", monthlyFeeAmount = 1800.0,
                admissionFeeAmount = 600.0,
                startDateMs = now - (90 * dayMs), endDateMs = now + (270 * dayMs),
                scheduleDays = "Fri, Sat", startTime = "08:00", endTime = "10:00",
                maxStudents = 20, status = "active",
                description = "Biology 1st & 2nd paper — practical included",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            db.batchDao().insertBatch(batch1)
            db.batchDao().insertBatch(batch2)
            db.batchDao().insertBatch(batch3)

            // ════════════════════════════════════════════════════════
            // 2.  20 STUDENTS + ENROLLMENTS
            // ════════════════════════════════════════════════════════
            data class SeedStudent(
                val name: String, val phone: String, val gender: String,
                val batchIdx: Int, val bloodGroup: String, val school: String,
                val address: String, val dobOffsetMonths: Int
            )
            val seed = listOf(
                SeedStudent("Abid Mohammad Zaman",    "+8801977956650", "Male",   0, "A+",  "Uttara High School",        "Uttara, Dhaka",           -202),
                SeedStudent("Ahadul Islam Arafat",    "+8801630152980", "Male",   0, "B+",  "Birshreshtha Noor Mohammad","Mirpur, Dhaka",           -200),
                SeedStudent("Ahnaf Binte Masud",      "+8801842111666", "Female", 0, "AB+", "Holy Cross Girls' School",  "Tejgaon, Dhaka",          -198),
                SeedStudent("Alpha Shahriar Shishir", "+8801712102461", "Male",   0, "O+",  "Govt. Science College",     "Dhanmondi, Dhaka",        -205),
                SeedStudent("Asrafun Nisa Lumia",     "+8801783412299", "Female", 0, "B+",  "Viqarunnisa Noon School",   "Bashundhara, Dhaka",      -203),
                SeedStudent("Aysha Akter",            "+8801753956958", "Female", 0, "AB-", "Motijheel Model School",    "Motijheel, Dhaka",        -196),
                SeedStudent("Bushra",                 "+8801926255064", "Female", 0, "A+",  "Ideal School & College",    "Banani, Dhaka",           -201),
                SeedStudent("Bushra Jahan Sadia",     "+8801825469539", "Female", 0, "O+",  "Adamjee Cantonment College","Cantonment, Dhaka",       -199),
                SeedStudent("Emon",                   "+8801995685191", "Male",   0, "B+",  "Dhaka College",             "New Market, Dhaka",       -204),
                SeedStudent("Entiha",                 "+8801518657869", "Female", 0, "A+",  "Shaheed Bir Uttam Lt. Anwar Girls' College","Azimpur, Dhaka", -197),
                SeedStudent("Fahima Afrin Rim",       "+8801864628826", "Female", 1, "O+",  "Monipur High School",       "Mirpur-2, Dhaka",         -200),
                SeedStudent("Faiza Akter",            "+8801626849299", "Female", 1, "A+",  "Uttara High School",        "Uttara Sector 7, Dhaka",  -201),
                SeedStudent("Faria Akter",            "+8801741235993", "Female", 1, "B+",  "Viqarunnisa Noon School",   "Banasree, Dhaka",         -198),
                SeedStudent("Fariha Chowdhury",       "+8801985001330", "Female", 1, "AB+", "Holy Cross Girls' School",  "Malibagh, Dhaka",         -202),
                SeedStudent("Farin Jahan",            "+8801764753406", "Female", 1, "O+",  "Ideal School & College",    "Gulshan, Dhaka",          -205),
                SeedStudent("Israt Jahan Moni",       "+8801612288128", "Female", 1, "A+",  "Motijheel Model School",    "Khilgaon, Dhaka",         -199),
                SeedStudent("Israt Jahan Prity",      "+8801746829021", "Female", 1, "B+",  "Shaheed Bir Uttam Lt. Anwar Girls' College","Shyamoli, Dhaka", -203),
                SeedStudent("Jannatul Ferdous",       "+8801712345678", "Female", 2, "O+",  "Adamjee Cantonment College","Cantonment, Dhaka",       -196),
                SeedStudent("Md. Rakib Hasan",        "+8801812345678", "Male",   2, "A+",  "Govt. Science College",     "Mohammadpur, Dhaka",      -200),
                SeedStudent("Nusrat Jahan Tisha",     "+8801912345678", "Female", 2, "AB+", "Dhaka College",             "Lalmatia, Dhaka",         -204)
            )

            val batchIds = listOf("demo_batch_1", "demo_batch_2", "demo_batch_3")
            val classNames = listOf("HSC 2nd Year", "SSC", "HSC 2nd Year")

            seed.forEachIndexed { i, s ->
                val studentId = "demo_student_${i + 1}"
                val batchId = batchIds[s.batchIdx]
                val prefix = if (s.batchIdx == 0) "HSC" else if (s.batchIdx == 1) "SSC" else "BIO"
                val code = "$prefix${(i + 1).toString().padStart(3, '0')}"
                val status = if (i == 18) "inactive" else "active"
                val dobMs = now + (s.dobOffsetMonths * 30L * dayMs)

                db.studentDao().insertStudent(
                    com.example.data.models.StudentEntity(
                        id = studentId, instituteId = demoInstituteId,
                        studentCode = code, fullName = s.name, photoUri = null,
                        gender = s.gender, dateOfBirthMs = dobMs,
                        phone = s.phone, email = null, address = s.address,
                        schoolName = s.school, className = classNames[s.batchIdx],
                        guardianName = "Guardian of ${s.name.take(12)}",
                        guardianPhone = s.phone.replace("+880", "018"),
                        guardianEmail = null, emergencyContact = s.phone,
                        bloodGroup = s.bloodGroup,
                        admissionDateMs = now - (90 * dayMs),
                        status = status, notes = null,
                        createdAtMs = now, updatedAtMs = now,
                        archivedAtMs = if (status == "inactive") now else null
                    )
                )
                db.batchStudentDao().enrollStudent(
                    com.example.data.models.BatchStudentEntity(
                        id = "demo_enroll_${i + 1}", instituteId = demoInstituteId,
                        batchId = batchId, studentId = studentId,
                        joinedAtMs = now - (90 * dayMs),
                        status = if (status == "inactive") "removed" else "active",
                        leftAtMs = if (status == "inactive") now else null
                    )
                )
            }

            // ════════════════════════════════════════════════════════
            // 3.  STUDENT ATTENDANCE  (~120 records, last 6 school days)
            // ════════════════════════════════════════════════════════
            val scheduleDays = listOf(
                listOf(0, 2, 4),  // Sun, Tue, Thu (batch1)
                listOf(1, 3, 6),  // Mon, Wed, Sat (batch2)
                listOf(5, 6)      // Fri, Sat (batch3)
            )
            val dayOfWeekToDelta = listOf(3, 2, 1, 0, 6, 5, 4) // map Calendar.DAY_OF_WEEK to days-ago
            val nowCal = Calendar.getInstance()
            val todayDow = nowCal.get(Calendar.DAY_OF_WEEK)

            for (batchIdx in 0..2) {
                val batchId = batchIds[batchIdx]
                val dowTargets = scheduleDays[batchIdx]
                for (weekOffset in 0..2) {
                    for (targetDow in dowTargets) {
                        val daysAgo = ((weekOffset * 7) + ((todayDow - targetDow + 7) % 7)).coerceAtLeast(1)
                        if (daysAgo > 20) continue
                        val attDate = startOfDay(-daysAgo.toLong())
                        seed.filter { it.batchIdx == batchIdx }.forEachIndexed { idx, _ ->
                            val globalIdx = seed.indexOfFirst { it.batchIdx == batchIdx && seed.indexOf(it) == seed.takeWhile { it2 -> it2.batchIdx == batchIdx || seed.indexOf(it2) < seed.indexOf(it) }.size} // this is wrong, let me do differently
                        }
                    }
                }
            }

            // Simpler approach: 6 most recent days regardless of schedule
            var attIdx = 0
            for (day in 1..6) {
                val attDate = startOfDay(-day.toLong())
                seed.forEachIndexed { i, s ->
                    val batchId = batchIds[s.batchIdx]
                    val roll = rng.nextFloat()
                    val status = when {
                        i == 18 -> "absent" // inactive student always absent
                        roll < 0.75 -> "present"
                        roll < 0.90 -> "absent"
                        else -> "late"
                    }
                    attIdx++
                    db.attendanceDao().insertOrUpdateAttendance(
                        com.example.data.models.AttendanceEntity(
                            id = "demo_att_$attIdx", instituteId = demoInstituteId,
                            batchId = batchId, studentId = "demo_student_${i + 1}",
                            attendanceDateMs = attDate, status = status, note = null,
                            markedByUserId = ownerId, createdAtMs = now, updatedAtMs = now
                        )
                    )
                }
            }

            // ════════════════════════════════════════════════════════
            // 4.  FEES  (28 records with strategic distribution)
            // ════════════════════════════════════════════════════════
            data class FeePlan(
                val studentIdx: Int, val period: String, val feeType: String,
                val base: Double, val paid: Double, val lateFee: Double, val status: String
            )
            // student -> batch fee: ICT=1500, MATH=1200, BIO=1800
            val feePlans = listOf(
                // ── Fully paid current month (students 1-11) ──
                FeePlan(0, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(1, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(2, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(3, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(4, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(5, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(6, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(7, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(8, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(9, currentMonth, "Monthly", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(10, currentMonth, "Monthly", 1200.0, 1200.0, 0.0, "paid"),
                // ── Partially paid current month (students 12-16) ──
                FeePlan(11, currentMonth, "Monthly", 1500.0, 800.0, 0.0, "partially_paid"),
                FeePlan(12, currentMonth, "Monthly", 1500.0, 800.0, 0.0, "partially_paid"),
                FeePlan(13, currentMonth, "Monthly", 1200.0, 700.0, 0.0, "partially_paid"),
                FeePlan(14, currentMonth, "Monthly", 1200.0, 700.0, 0.0, "partially_paid"),
                FeePlan(15, currentMonth, "Monthly", 1200.0, 700.0, 0.0, "partially_paid"),
                // ── Last month overdue (students 12-16) ──
                FeePlan(11, lastMonth, "Overdue", 1500.0, 0.0, 200.0, "overdue"),
                FeePlan(12, lastMonth, "Overdue", 1500.0, 0.0, 200.0, "overdue"),
                FeePlan(13, lastMonth, "Overdue", 1200.0, 0.0, 150.0, "overdue"),
                FeePlan(14, lastMonth, "Overdue", 1200.0, 0.0, 150.0, "overdue"),
                FeePlan(15, lastMonth, "Overdue", 1200.0, 0.0, 150.0, "overdue"),
                // ── Advance paid next month (students 1, 10) ──
                FeePlan(0, nextMonth, "Advance", 1500.0, 1500.0, 0.0, "paid"),
                FeePlan(9, nextMonth, "Advance", 1500.0, 1500.0, 0.0, "paid"),
                // ── Student 20 (index 19) current month paid ──
                FeePlan(19, currentMonth, "Monthly", 1800.0, 1800.0, 0.0, "paid"),
                // ── Student 17-18 last month (paid with late fee) ──
                FeePlan(16, lastMonth, "Monthly", 1200.0, 1350.0, 150.0, "paid"),
                FeePlan(17, lastMonth, "Monthly", 1200.0, 1350.0, 150.0, "paid"),
                // ── Student 17-18 current month paid ──
                FeePlan(16, currentMonth, "Monthly", 1200.0, 1200.0, 0.0, "paid"),
                FeePlan(17, currentMonth, "Monthly", 1200.0, 1200.0, 0.0, "paid"),
                // ── Inactive student 19 (index 18) unpaid — close category ──
                FeePlan(18, currentMonth, "Monthly", 1800.0, 0.0, 0.0, "unpaid"),
                // ── Cancelled fee ──
                FeePlan(2, currentMonth, "Admission", 500.0, 0.0, 0.0, "cancelled"),
            )

            var paymentCounter = 0
            var receiptCounter = 1000
            feePlans.forEachIndexed { fi, fp ->
                val studentId = "demo_student_${fp.studentIdx + 1}"
                val total = fp.base + fp.lateFee
                val due = (total - fp.paid).coerceAtLeast(0.0)
                val feeId = "demo_fee_${fi + 1}"
                val status = if (fp.status == "cancelled") "cancelled" else
                    if (due < 0.01) "paid" else if (fp.paid > 0) "partially_paid" else "unpaid"
                val dueDateMs = startOfDay(7)

                db.feeDao().insertFee(
                    com.example.data.models.FeeEntity(
                        id = feeId, instituteId = demoInstituteId,
                        studentId = studentId,
                        batchId = batchIds[seed.getOrNull(fp.studentIdx)?.batchIdx ?: 0],
                        feePeriod = fp.period, feeType = fp.feeType,
                        dueDateMs = dueDateMs, baseAmount = fp.base,
                        discountAmount = 0.0, lateFeeAmount = fp.lateFee,
                        totalAmount = total, paidAmount = fp.paid,
                        dueAmount = due, status = status,
                        note = if (status == "cancelled") "Cancelled — transferred to another batch" else null,
                        createdAtMs = now, updatedAtMs = now,
                        cancelledAtMs = if (status == "cancelled") now else null
                    )
                )

                if (fp.paid > 0) {
                    paymentCounter++
                    val rcpt = "REC-${receiptCounter + paymentCounter}"
                    val methods = listOf("cash", "bkash", "nagad", "bank_transfer")
                    val method = methods[fi % methods.size]
                    db.paymentDao().insertPayment(
                        com.example.data.models.PaymentEntity(
                            id = "demo_payment_$paymentCounter", instituteId = demoInstituteId,
                            feeId = feeId, studentId = studentId,
                            amount = fp.paid, paymentMethod = method,
                            transactionId = if (method == "cash") null else "TXN${10000 + paymentCounter}",
                            receiptNumber = rcpt,
                            paymentDateMs = now - (rng.nextInt(7) * dayMs),
                            collectedByUserId = ownerId,
                            status = "completed", note = if (fp.feeType == "Advance") "Advance payment" else null,
                            createdAtMs = now, updatedAtMs = now
                        )
                    )
                    db.receiptDao().insertReceipt(
                        com.example.data.models.ReceiptEntity(
                            id = "demo_receipt_$paymentCounter", instituteId = demoInstituteId,
                            paymentId = "demo_payment_$paymentCounter", feeId = feeId,
                            studentId = studentId, receiptNumber = rcpt,
                            receiptDateMs = now, totalAmount = total,
                            paidAmount = fp.paid, dueAmount = due,
                            paymentMethod = method,
                            receiptText = "Payment received — ${fp.period}",
                            createdAtMs = now
                        )
                    )
                }
            }

            // ════════════════════════════════════════════════════════
            // 5.  EXPENSES  (8 records across last 2 months)
            // ════════════════════════════════════════════════════════
            val expenses = listOf(
                Triple("Utilities", "Electricity Bill — May", 2200.0),
                Triple("Utilities", "Internet Bill — May", 1500.0),
                Triple("Rent", "Office Rent — May", 12000.0),
                Triple("Salary", "Staff Advance — May", 5000.0),
                Triple("Supplies", "Stationery & Books", 1200.0),
                Triple("Supplies", "Whiteboard Markers (pack of 12)", 600.0),
                Triple("Maintenance", "AC Repair — Room 2", 3500.0),
                Triple("Marketing", "Facebook Ads — Organic Reach", 2000.0)
            )
            expenses.forEachIndexed { i, (cat, title, amt) ->
                db.expenseDao().insertExpense(
                    com.example.data.models.ExpenseEntity(
                        id = "demo_expense_${i + 1}", instituteId = demoInstituteId,
                        category = cat, title = title, amount = amt,
                        expenseDateMs = now - ((i + 1) * 3L * dayMs),
                        paymentMethod = if (i % 2 == 0) "cash" else "bank_transfer",
                        description = title, attachmentUri = null,
                        createdByUserId = ownerId,
                        createdAtMs = now, updatedAtMs = now, archivedAtMs = null
                    )
                )
            }

            // ════════════════════════════════════════════════════════
            // 6.  EXAMS (3) + RESULTS (~20)
            // ════════════════════════════════════════════════════════
            val exam1 = com.example.data.models.ExamEntity(
                id = "demo_exam_1", instituteId = demoInstituteId,
                batchId = "demo_batch_1", examName = "Monthly Test 1 — ICT",
                subject = "ICT", examDateMs = startOfDay(5),
                totalMarks = 100.0, passingMarks = 40.0,
                teacherName = "Md. Hasan", note = "Chapters 1-3",
                status = "scheduled",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            val exam2 = com.example.data.models.ExamEntity(
                id = "demo_exam_2", instituteId = demoInstituteId,
                batchId = "demo_batch_1", examName = "Chapter 1-3 Review — ICT",
                subject = "ICT", examDateMs = startOfDay(-10),
                totalMarks = 50.0, passingMarks = 20.0,
                teacherName = "Md. Hasan", note = null,
                status = "completed",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            val exam3 = com.example.data.models.ExamEntity(
                id = "demo_exam_3", instituteId = demoInstituteId,
                batchId = "demo_batch_2", examName = "Mid-Term — Mathematics",
                subject = "Mathematics", examDateMs = startOfDay(-15),
                totalMarks = 100.0, passingMarks = 40.0,
                teacherName = "Md. Karim", note = "Full syllabus so far",
                status = "completed",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            db.examDao().insertExam(exam1)
            db.examDao().insertExam(exam2)
            db.examDao().insertExam(exam3)

            // Results for Exam 2 (ICT, 10 students)
            val ictResults = listOf(
                Triple("demo_student_1", 46.0, "Excellent performance!"),
                Triple("demo_student_2", 42.0, "Good progress"),
                Triple("demo_student_3", 38.0, "Keep it up!"),
                Triple("demo_student_4", 44.0, null),
                Triple("demo_student_5", 35.0, null),
                Triple("demo_student_6", 31.0, "Needs improvement"),
                Triple("demo_student_7", 40.0, null),
                Triple("demo_student_8", 28.0, null),
                Triple("demo_student_9", 33.0, null),
                Triple("demo_student_10", 48.0, "Top scorer — well done!")
            )
            ictResults.forEachIndexed { i, (sid, marks, remark) ->
                val grade = when { marks >= 40 -> "A+"; marks >= 35 -> "A"; marks >= 30 -> "B"; marks >= 25 -> "C"; else -> "D" }
                db.resultDao().insertOrUpdateResult(
                    com.example.data.models.ResultEntity(
                        id = "demo_result_ict_${i + 1}", instituteId = demoInstituteId,
                        examId = "demo_exam_2", batchId = "demo_batch_1",
                        studentId = sid, marksObtained = marks,
                        grade = grade, position = i + 1, remarks = remark,
                        published = true, createdAtMs = now, updatedAtMs = now
                    )
                )
            }

            // Results for Exam 3 (Math, 7 students)
            val mathResults = listOf(
                Triple("demo_student_11", 89.0, "Outstanding!"),
                Triple("demo_student_12", 76.0, null),
                Triple("demo_student_13", 82.0, "Strong performance"),
                Triple("demo_student_14", 65.0, null),
                Triple("demo_student_15", 71.0, null),
                Triple("demo_student_16", 58.0, "Needs practice"),
                Triple("demo_student_17", 93.0, "Top of the class!")
            )
            mathResults.forEachIndexed { i, (sid, marks, remark) ->
                val grade = when { marks >= 80 -> "A+"; marks >= 70 -> "A"; marks >= 60 -> "B"; marks >= 50 -> "C"; else -> "D" }
                db.resultDao().insertOrUpdateResult(
                    com.example.data.models.ResultEntity(
                        id = "demo_result_math_${i + 1}", instituteId = demoInstituteId,
                        examId = "demo_exam_3", batchId = "demo_batch_2",
                        studentId = sid, marksObtained = marks,
                        grade = grade, position = i + 1, remarks = remark,
                        published = true, createdAtMs = now, updatedAtMs = now
                    )
                )
            }

            // ════════════════════════════════════════════════════════
            // 7.  STAFF (3) + STAFF ATTENDANCE + SALARIES + USER ENTITIES
            // ════════════════════════════════════════════════════════
            data class SeedStaff(
                val id: String, val code: String, val name: String, val roleTitle: String,
                val phone: String, val salary: Double, val batchId: String?,
                val permissions: List<String>, val email: String
            )
            val staffSeed = listOf(
                SeedStaff("demo_staff_1", "STF001", "Md. Hasan", "Teacher (ICT)",
                    "01710000001", 15000.0, "demo_batch_1",
                    listOf("view_student", "view_batch", "collect_fee", "take_attendance", "view_attendance_reports", "manage_exams"),
                    "STF001"),
                SeedStaff("demo_staff_2", "STF002", "Md. Karim", "Teacher (Math)",
                    "01710000002", 12000.0, "demo_batch_2",
                    listOf("view_student", "view_batch", "take_attendance", "view_attendance_reports"),
                    "STF002"),
                SeedStaff("demo_staff_3", "STF003", "Fatema Begum", "Accountant",
                    "01710000003", 10000.0, null,
                    listOf("collect_fee", "view_reports", "manage_expenses", "view_student"),
                    "STF003")
            )
            staffSeed.forEach { s ->
                db.staffDao().insertStaff(
                    com.example.data.models.StaffEntity(
                        id = s.id, instituteId = demoInstituteId,
                        staffCode = s.code, fullName = s.name,
                        photoUri = null, roleTitle = s.roleTitle,
                        phone = s.phone, email = s.email + "@demo.com",
                        address = "Dhaka, Bangladesh",
                        joiningDateMs = now - (365 * dayMs),
                        monthlySalary = s.salary,
                        assignedBatchIds = s.batchId,
                        status = "active", notes = null,
                        permissions = s.permissions.joinToString(","),
                        createdAtMs = now, updatedAtMs = now, archivedAtMs = null
                    )
                )
                db.userDao().insertUser(
                    UserEntity(
                        id = s.id, instituteId = demoInstituteId,
                        name = s.name, email = s.email,
                        passwordHash = PasswordHasher.hash("123456"),
                        role = "Staff", createdAtMs = now
                    )
                )
            }

            // Staff attendance (last 4 working days)
            var staffAttIdx = 0
            for (day in 1..4) {
                val attDate = startOfDay(-day.toLong())
                staffSeed.forEach { s ->
                    staffAttIdx++
                    val status = if (staffAttIdx == 7) "absent" else "present"
                    db.staffAttendanceDao().insertOrUpdateAttendance(
                        com.example.data.models.StaffAttendanceEntity(
                            id = "demo_staff_att_$staffAttIdx", instituteId = demoInstituteId,
                            staffId = s.id, attendanceDateMs = attDate,
                            status = status, note = if (status == "absent") "Sick leave" else null,
                            markedByUserId = ownerId, createdAtMs = now, updatedAtMs = now
                        )
                    )
                }
            }

            // Salaries (current + last month for all 3 staff)
            staffSeed.forEachIndexed { si, s ->
                listOf(currentMonth, lastMonth).forEachIndexed { mi, month ->
                    val status = if (si == 0 && mi == 0) "pending" else "paid"
                    val slipNo = "SLP-2026-${(si * 2 + mi + 1).toString().padStart(3, '0')}"
                    db.salaryDao().insertSalary(
                        com.example.data.models.SalaryEntity(
                            id = "demo_salary_${si}_${mi}", instituteId = demoInstituteId,
                            staffId = s.id, salaryMonth = month,
                            basicSalary = s.salary, bonusAmount = if (mi == 0) 0.0 else 1000.0,
                            deductionAmount = 0.0, advanceAmount = 0.0,
                            netSalary = if (mi == 0) s.salary else s.salary + 1000.0,
                            paymentMethod = "bank_transfer",
                            paymentDateMs = if (status == "paid") now - (mi * 30L * dayMs) else null,
                            status = status, salarySlipNumber = slipNo,
                            note = if (status == "pending") "Processing" else null,
                            createdAtMs = now, updatedAtMs = now, cancelledAtMs = null
                        )
                    )
                }
            }

            // ════════════════════════════════════════════════════════
            // 8.  ENQUIRIES (6)
            // ════════════════════════════════════════════════════════
            val enquiries = listOf(
                listOf("Rafiq Hasan", "+8801712345001", "ICT", "active", "2 days ago"),
                listOf("Sumaiya Akter", "+8801812345002", "Mathematics", "active", "4 days ago"),
                listOf("Tariqul Islam", "+8801912345003", "Biology", "active", "6 days ago"),
                listOf("Nusrat Jahan", "+8801512345004", "ICT", "follow_up", "10 days ago"),
                listOf("Kamal Uddin", "+8801612345005", "Mathematics", "closed", "20 days ago"),
                listOf("Rina Begum", "+8801312345006", "Biology", "closed", "25 days ago")
            )
            enquiries.forEachIndexed { i, e ->
                val daysAgo = when (i) { 0 -> 2L; 1 -> 4L; 2 -> 6L; 3 -> 10L; 4 -> 20L; 5 -> 25L; else -> 1L }
                db.enquiryDao().insertEnquiry(
                    com.example.data.models.EnquiryEntity(
                        id = "demo_enquiry_${i + 1}", instituteId = demoInstituteId,
                        name = e[0], phone = e[1], address = "Dhaka, Bangladesh",
                        subjectName = e[2],
                        enquiryDateMs = now - (daysAgo * dayMs),
                        status = e[3],
                        createdAtMs = now, updatedAtMs = now, archivedAtMs = null
                    )
                )
            }

            // ════════════════════════════════════════════════════════
            // 9.  REMINDER TEMPLATES (3)
            // ════════════════════════════════════════════════════════
            db.reminderTemplateDao().insertTemplate(
                com.example.data.models.ReminderTemplateEntity(
                    id = "demo_reminder_1", instituteId = demoInstituteId,
                    title = "Monthly Fee Reminder",
                    type = "Fee",
                    messageTemplate = "Dear Guardian, kindly pay the monthly fee of {amount} BDT for {studentName} by {dueDate}. — BatchFee",
                    isDefault = true, createdAtMs = now, updatedAtMs = now
                )
            )
            db.reminderTemplateDao().insertTemplate(
                com.example.data.models.ReminderTemplateEntity(
                    id = "demo_reminder_2", instituteId = demoInstituteId,
                    title = "Birthday Wish",
                    type = "Birthday",
                    messageTemplate = "Happy Birthday, {studentName}! Wishing you a fantastic day from all of us at {instituteName}. 🎂",
                    isDefault = true, createdAtMs = now, updatedAtMs = now
                )
            )
            db.reminderTemplateDao().insertTemplate(
                com.example.data.models.ReminderTemplateEntity(
                    id = "demo_reminder_3", instituteId = demoInstituteId,
                    title = "Exam Schedule Alert",
                    type = "Exam",
                    messageTemplate = "Dear Guardian, {studentName}'s exam ({examName}) is on {examDate}. Please ensure attendance. — {instituteName}",
                    isDefault = false, createdAtMs = now, updatedAtMs = now
                )
            )

            try {
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val studentCount = db.studentDao().getStudentsByInstituteOnce(demoInstituteId).size
                val staffCount = db.staffDao().getStaffByInstituteAsList(demoInstituteId).size
                firestore.collection("institutes").document(demoInstituteId).set(
                    mapOf(
                        "instituteName" to "BatchFee Demo Institute",
                        "instituteCode" to "BGS-100",
                        "ownerName" to "Demo Owner",
                        "email" to "owner@batchfee.app",
                        "phone" to "+8801712345678",
                        "whatsappNumber" to "+8801712345678",
                        "role" to "owner",
                        "createdAt" to now,
                        "isActive" to true,
                        "trialEndDate" to (now + 30L * 24 * 60 * 60 * 1000),
                        "studentCount" to studentCount,
                        "staffCount" to staffCount,
                        "lastActiveAt" to now
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }
}
