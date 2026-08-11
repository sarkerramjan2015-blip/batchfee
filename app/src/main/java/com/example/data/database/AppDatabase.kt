package com.batchfee.edu.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.batchfee.edu.BuildConfig
import com.batchfee.edu.data.dao.InstituteDao
import com.batchfee.edu.data.dao.SubscriptionPlanDao
import com.batchfee.edu.data.dao.UserDao
import com.batchfee.edu.data.firestore.AppUserSyncHelper
import com.batchfee.edu.data.firestore.ManagedUserRecord
import com.batchfee.edu.data.firestore.ReminderTemplateSyncHelper
import com.batchfee.edu.data.firestore.SubscriptionPlanSyncHelper
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.SubscriptionPlanEntity
import com.batchfee.edu.data.models.UserEntity
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.SubscriptionPolicy
import com.batchfee.edu.data.firebase.FirebaseAuthApi
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
        com.batchfee.edu.data.models.StudentEntity::class,
        com.batchfee.edu.data.models.BatchEntity::class,
        com.batchfee.edu.data.models.BatchStudentEntity::class,
        com.batchfee.edu.data.models.AttendanceEntity::class,
        com.batchfee.edu.data.models.FeeEntity::class,
        com.batchfee.edu.data.models.PaymentEntity::class,
        com.batchfee.edu.data.models.ReceiptEntity::class,
        com.batchfee.edu.data.models.PaymentReversalEntity::class,
        com.batchfee.edu.data.models.FinancialOutboxEntity::class,
        com.batchfee.edu.data.models.DeletionOutboxEntity::class,
        com.batchfee.edu.data.models.ReminderTemplateEntity::class,
        com.batchfee.edu.data.models.StaffEntity::class,
        com.batchfee.edu.data.models.StaffAttendanceEntity::class,
        com.batchfee.edu.data.models.SalaryEntity::class,
        com.batchfee.edu.data.models.ExpenseEntity::class,
        com.batchfee.edu.data.models.ExamEntity::class,
        com.batchfee.edu.data.models.ResultEntity::class,
        com.batchfee.edu.data.models.AuditLogEntity::class,
        com.batchfee.edu.data.models.AbsentMessageEntity::class,
        com.batchfee.edu.data.models.EnquiryEntity::class,
        com.batchfee.edu.data.models.WorkEntity::class,
        com.batchfee.edu.data.models.HomeworkEntity::class,
        com.batchfee.edu.data.models.AssignmentEntity::class,
        com.batchfee.edu.data.models.HomeworkSubmissionEntity::class,
        com.batchfee.edu.data.models.AssignmentSubmissionEntity::class
    ],
    version = 23,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun instituteDao(): InstituteDao
    abstract fun userDao(): UserDao
    abstract fun subscriptionPlanDao(): SubscriptionPlanDao
    abstract fun studentDao(): com.batchfee.edu.data.dao.StudentDao
    abstract fun batchDao(): com.batchfee.edu.data.dao.BatchDao
    abstract fun batchStudentDao(): com.batchfee.edu.data.dao.BatchStudentDao
    abstract fun attendanceDao(): com.batchfee.edu.data.dao.AttendanceDao
    abstract fun feeDao(): com.batchfee.edu.data.dao.FeeDao
    abstract fun paymentDao(): com.batchfee.edu.data.dao.PaymentDao
    abstract fun receiptDao(): com.batchfee.edu.data.dao.ReceiptDao
    abstract fun financialLedgerDao(): com.batchfee.edu.data.dao.FinancialLedgerDao
    abstract fun safeDeletionDao(): com.batchfee.edu.data.dao.SafeDeletionDao
    abstract fun reminderTemplateDao(): com.batchfee.edu.data.dao.ReminderTemplateDao
    abstract fun staffDao(): com.batchfee.edu.data.dao.StaffDao
    abstract fun staffAttendanceDao(): com.batchfee.edu.data.dao.StaffAttendanceDao
    abstract fun salaryDao(): com.batchfee.edu.data.dao.SalaryDao
    abstract fun expenseDao(): com.batchfee.edu.data.dao.ExpenseDao
    abstract fun examDao(): com.batchfee.edu.data.dao.ExamDao
    abstract fun resultDao(): com.batchfee.edu.data.dao.ResultDao
    abstract fun auditLogDao(): com.batchfee.edu.data.dao.AuditLogDao
    abstract fun absentMessageDao(): com.batchfee.edu.data.dao.AbsentMessageDao
    abstract fun enquiryDao(): com.batchfee.edu.data.dao.EnquiryDao
    abstract fun workDao(): com.batchfee.edu.data.dao.WorkDao
    abstract fun homeworkDao(): com.batchfee.edu.data.dao.HomeworkDao
    abstract fun assignmentDao(): com.batchfee.edu.data.dao.AssignmentDao

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

        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enquiries ADD COLUMN note TEXT")
            }
        }

        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE students ADD COLUMN isAppAccessEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE students ADD COLUMN studentPasswordHash TEXT")
            }
        }

        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE students ADD COLUMN appAccessEmail TEXT")
            }
        }

        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS works (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, batchId TEXT, type TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, dueDateMs INTEGER, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, archivedAtMs INTEGER)")
            }
        }

        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS homework (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, batchId TEXT, title TEXT NOT NULL, subject TEXT, className TEXT, instructions TEXT NOT NULL, bookPage TEXT, startDateMs INTEGER NOT NULL, dueDateMs INTEGER, attachmentUri TEXT, requiresSubmission INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'active', createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, archivedAtMs INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS assignments (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, batchId TEXT, title TEXT NOT NULL, subject TEXT, className TEXT, assignmentType TEXT NOT NULL DEFAULT 'individual', instructions TEXT NOT NULL, learningObjective TEXT, totalMarks REAL, passingMarks REAL, gradingMethod TEXT NOT NULL DEFAULT 'marks', rubricJson TEXT, startDateMs INTEGER NOT NULL, dueDateMs INTEGER, allowLateSubmission INTEGER NOT NULL DEFAULT 0, latePenalty TEXT, submissionFormat TEXT NOT NULL DEFAULT 'any', maxFileSizeKb INTEGER, referenceMaterials TEXT, status TEXT NOT NULL DEFAULT 'draft', publishDateMs INTEGER, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, archivedAtMs INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS homework_submissions (id TEXT NOT NULL PRIMARY KEY, homeworkId TEXT NOT NULL, studentId TEXT NOT NULL, instituteId TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', submittedAtMs INTEGER, attachmentUri TEXT, studentNote TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS assignment_submissions (id TEXT NOT NULL PRIMARY KEY, assignmentId TEXT NOT NULL, studentId TEXT NOT NULL, instituteId TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', submittedAtMs INTEGER, attachmentUri TEXT, studentNote TEXT, marksObtained REAL, grade TEXT, percentage REAL, teacherFeedback TEXT, feedbackAttachmentUri TEXT, gradedAtMs INTEGER, resubmitRequested INTEGER NOT NULL DEFAULT 0)")
            }
        }

        // Purge the two legacy student credential columns instead of merely
        // nulling them, so old hashes/emails cannot survive in an upgraded local DB.
        internal val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete = ON").close()
                db.execSQL(
                    """
                    CREATE TABLE students_without_legacy_credentials (
                        id TEXT NOT NULL PRIMARY KEY,
                        instituteId TEXT NOT NULL,
                        studentCode TEXT NOT NULL,
                        fullName TEXT NOT NULL,
                        photoUri TEXT,
                        gender TEXT,
                        dateOfBirthMs INTEGER,
                        phone TEXT,
                        email TEXT,
                        address TEXT,
                        schoolName TEXT,
                        className TEXT,
                        guardianName TEXT,
                        guardianPhone TEXT,
                        guardianEmail TEXT,
                        emergencyContact TEXT,
                        bloodGroup TEXT,
                        admissionDateMs INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        notes TEXT,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        archivedAtMs INTEGER,
                        isAppAccessEnabled INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO students_without_legacy_credentials (
                        id, instituteId, studentCode, fullName, photoUri, gender,
                        dateOfBirthMs, phone, email, address, schoolName, className,
                        guardianName, guardianPhone, guardianEmail, emergencyContact,
                        bloodGroup, admissionDateMs, status, notes, createdAtMs,
                        updatedAtMs, archivedAtMs, isAppAccessEnabled
                    )
                    SELECT
                        id, instituteId, studentCode, fullName, photoUri, gender,
                        dateOfBirthMs, phone, email, address, schoolName, className,
                        guardianName, guardianPhone, guardianEmail, emergencyContact,
                        bloodGroup, admissionDateMs, status, notes, createdAtMs,
                        updatedAtMs, archivedAtMs, isAppAccessEnabled
                    FROM students
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE students")
                db.execSQL("ALTER TABLE students_without_legacy_credentials RENAME TO students")
            }
        }

        internal val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fees ADD COLUMN businessKey TEXT")
                db.execSQL("ALTER TABLE fees ADD COLUMN ledgerVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE payments ADD COLUMN operationId TEXT")
                db.execSQL("ALTER TABLE payments ADD COLUMN ledgerVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE receipts ADD COLUMN operationId TEXT")
                db.execSQL("ALTER TABLE receipts ADD COLUMN ledgerVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fees_instituteId_businessKey ON fees(instituteId, businessKey)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payments_instituteId_operationId ON payments(instituteId, operationId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_receipts_instituteId_operationId ON receipts(instituteId, operationId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS payment_reversals (
                        id TEXT NOT NULL PRIMARY KEY,
                        instituteId TEXT NOT NULL,
                        paymentId TEXT NOT NULL,
                        feeId TEXT NOT NULL,
                        studentId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        receiptNumber TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        reversedByUserId TEXT NOT NULL,
                        reversedAtMs INTEGER NOT NULL,
                        operationId TEXT NOT NULL,
                        ledgerVersion INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payment_reversals_instituteId_paymentId ON payment_reversals(instituteId, paymentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_reversals_instituteId_feeId ON payment_reversals(instituteId, feeId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS financial_outbox (
                        operationId TEXT NOT NULL,
                        instituteId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        requestJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        lastError TEXT,
                        PRIMARY KEY(instituteId, operationId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_financial_outbox_instituteId_status ON financial_outbox(instituteId, status)")
            }
        }

        internal val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS deletion_outbox (
                        operationId TEXT NOT NULL,
                        instituteId TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        requestJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        lastError TEXT,
                        PRIMARY KEY(instituteId, operationId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_outbox_status ON deletion_outbox(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_outbox_instituteId_entityType_entityId_action_status ON deletion_outbox(instituteId, entityType, entityId, action, status)")
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "batchfee_database"
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)

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

                    val user = db.userDao().getUserByEmail("superadmin@batchfee.app")
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

        suspend fun seedDemoForRealUid(db: AppDatabase, ownerUid: String, instituteId: String) {
            withContext(Dispatchers.IO) {
                try {
                    realOwnerUid = ownerUid
                    val existingPlan = db.subscriptionPlanDao().getPlanById("plan_free_trial")
                    if (existingPlan == null) {
                        populateInitialPlans(db.subscriptionPlanDao())
                    }
                    populateSuperAdmin(db.userDao(), db.instituteDao())
                    populateDemoData(db)
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }

        private suspend fun ensureFirebaseAuthAccounts() {
            // ── SuperAdmin ──
            try {
                realAdminUid = FirebaseAuthApi.createUser("superadmin@batchfee.app", "11223344")
                seedFirestoreAndAppUser(realAdminUid!!, "superadmin@batchfee.app", "SuperAdmin", "BatchFee System")
            } catch (e: Exception) {
                if ((e.message ?: "").contains("EMAIL_EXISTS", ignoreCase = true)) {
                    try {
                        realAdminUid = FirebaseAuthApi.signInWithPassword("superadmin@batchfee.app", "11223344")
                        seedFirestoreAndAppUser(realAdminUid!!, "superadmin@batchfee.app", "SuperAdmin", "BatchFee System")
                    } catch (e2: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e2)
                    }
                } else {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }

            // ── Demo Owner (fresh account: demo@batchfee.app) ──
            try {
                realOwnerUid = FirebaseAuthApi.createUser("demo@batchfee.app", "123456")
                seedFirestoreAndAppUser(
                    realOwnerUid!!, "demo@batchfee.app", "InstituteOwner", "BatchFee Demo Institute",
                    extraFields = mapOf(
                        "instituteCode" to "BGS-100",
                        "ownerName" to "Demo Owner",
                        "phone" to "+8801712345678",
                        "whatsappNumber" to "+8801712345678",
                        "trialEndDate" to (System.currentTimeMillis() + SubscriptionPolicy.FREE_TRIAL_DURATION_MS),
                        "studentCount" to 0,
                        "staffCount" to 0,
                        "currentPlanId" to "plan_pro",
                        "subscriptionStatus" to "trial"
                    )
                )
            } catch (e: Exception) {
                if ((e.message ?: "").contains("EMAIL_EXISTS", ignoreCase = true)) {
                    try {
                        realOwnerUid = FirebaseAuthApi.signInWithPassword("demo@batchfee.app", "123456")
                        seedFirestoreAndAppUser(
                            realOwnerUid!!, "demo@batchfee.app", "InstituteOwner", "BatchFee Demo Institute",
                            extraFields = mapOf(
                                "instituteName" to "BatchFee Demo Institute",
                                "instituteCode" to "BGS-100",
                                "ownerName" to "Demo Owner",
                                "email" to "demo@batchfee.app",
                                "phone" to "+8801712345678",
                                "whatsappNumber" to "+8801712345678",
                                "role" to "owner",
                                "isActive" to true
                            )
                        )
                    } catch (e2: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e2)
                    }
                } else {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }

        private suspend fun seedFirestoreAndAppUser(
            uid: String,
            email: String,
            role: String,
            instituteName: String,
            extraFields: Map<String, Any> = emptyMap()
        ) {
            val now = System.currentTimeMillis()
            val trialDurationMs = SubscriptionPolicy.FREE_TRIAL_DURATION_MS
            val baseFields = mutableMapOf<String, Any>(
                "instituteName" to instituteName,
                "role" to role,
                "email" to email,
                "createdAt" to now,
                "isActive" to true,
                "trialEndDate" to (now + trialDurationMs),
                "currentPeriodEndMs" to (now + trialDurationMs),
                "currentPlanId" to "plan_free_trial",
                "subscriptionStatus" to "trial",
                "studentLimit" to 50,
                "staffLimit" to 1,
                "studentCount" to 0,
                "staffCount" to 0,
                "batchCount" to 0
            )
            baseFields.putAll(extraFields)

            try {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("institutes").document(uid)
                    .set(baseFields, com.google.firebase.firestore.SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }

            try {
                AppUserSyncHelper.upsertManagedUser(
                    ManagedUserRecord(
                        id = uid,
                        name = instituteName,
                        email = email,
                        role = role,
                        instituteId = uid,
                        createdAtMs = now
                    )
                )
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
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
                 ),
                 // The public pricing catalogue. Legacy plan IDs above remain
                 // supported for already-subscribed institutes, but are not
                 // presented as new purchase options.
                 SubscriptionPlanEntity("basic", "Basic", "Basic plan for small institutes", 199.0, 0.0, 50, 5, 1, 1, "", 1),
                 SubscriptionPlanEntity("standard", "Standard", "Standard plan for growing institutes", 299.0, 0.0, 100, 10, 2, 1, "", 2),
                 SubscriptionPlanEntity("spark", "Spark", "Spark plan for growing institutes", 399.0, 0.0, 150, 15, 3, 1, "", 3),
                 SubscriptionPlanEntity("grow", "Grow", "Grow plan for coaching centres", 499.0, 0.0, 200, 20, 5, 1, "", 4),
                 SubscriptionPlanEntity("pro", "Pro", "Popular professional plan", 599.0, 0.0, 250, 25, 8, 1, "Popular", 5),
                 SubscriptionPlanEntity("elite", "Elite", "Elite institute plan", 699.0, 0.0, 300, 30, 10, 1, "", 6),
                 SubscriptionPlanEntity("prime", "Prime", "Prime institute plan", 799.0, 0.0, 350, 35, 12, 1, "", 7),
                 SubscriptionPlanEntity("max", "Max", "Max institute plan", 899.0, 0.0, 400, 40, 15, 1, "", 8),
                 SubscriptionPlanEntity("ultra", "Ultra", "Ultra institute plan", 999.0, 0.0, 450, 45, 18, 1, "", 9),
                 SubscriptionPlanEntity("scale", "Scale", "Recommended plan for larger institutes", 1099.0, 0.0, 500, 50, 20, 1, "Recommended", 10)
                 )
             dao.insertPlans(plans)
             try {
                 SubscriptionPlanSyncHelper.upsertPlans(plans)
             } catch (_: Exception) { }
        }

        suspend fun populateSuperAdmin(userDao: UserDao, instituteDao: InstituteDao) {
            // Use real Firebase Auth UIDs instead of hardcoded fake IDs
            val adminUid = realAdminUid ?: "sys_super_admin_1"
            val ownerUid = realOwnerUid ?: "demo_institute_1"
            val now = System.currentTimeMillis()

            userDao.insertUser(
                UserEntity(
                    id = adminUid,
                    instituteId = null,
                    name = "System Admin",
                    email = "superadmin@batchfee.app",
                    passwordHash = PasswordHasher.hash("11223344"),
                    role = "SuperAdmin",
                    createdAtMs = now
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
                            "instituteId" to adminUid,
                            "role" to "SuperAdmin",
                            "email" to "superadmin@batchfee.app",
                            "createdAt" to now,
                            "isActive" to true
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            
            val demoInstituteId = ownerUid
            val thirtyDaysMs = SubscriptionPolicy.FREE_TRIAL_DURATION_MS
            
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
                    email = "demo@batchfee.app",
                    instituteCode = "BGS-100",
                    securityPin = null
                )
            )
            
            userDao.insertUser(
                UserEntity(
                    id = ownerUid,
                    instituteId = demoInstituteId,
                    name = "Mohammad Ramjan Sarker",
                    email = "demo@batchfee.app",
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
            val batch1 = com.batchfee.edu.data.models.BatchEntity(
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
            val batch2 = com.batchfee.edu.data.models.BatchEntity(
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
            val batch3 = com.batchfee.edu.data.models.BatchEntity(
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
                    com.batchfee.edu.data.models.StudentEntity(
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
                    com.batchfee.edu.data.models.BatchStudentEntity(
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
                        com.batchfee.edu.data.models.AttendanceEntity(
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
                    com.batchfee.edu.data.models.FeeEntity(
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
                        com.batchfee.edu.data.models.PaymentEntity(
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
                        com.batchfee.edu.data.models.ReceiptEntity(
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
                    com.batchfee.edu.data.models.ExpenseEntity(
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
            val exam1 = com.batchfee.edu.data.models.ExamEntity(
                id = "demo_exam_1", instituteId = demoInstituteId,
                batchId = "demo_batch_1", examName = "Monthly Test 1 — ICT",
                subject = "ICT", examDateMs = startOfDay(5),
                totalMarks = 100.0, passingMarks = 40.0,
                teacherName = "Md. Hasan", note = "Chapters 1-3",
                status = "scheduled",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            val exam2 = com.batchfee.edu.data.models.ExamEntity(
                id = "demo_exam_2", instituteId = demoInstituteId,
                batchId = "demo_batch_1", examName = "Chapter 1-3 Review — ICT",
                subject = "ICT", examDateMs = startOfDay(-10),
                totalMarks = 50.0, passingMarks = 20.0,
                teacherName = "Md. Hasan", note = null,
                status = "completed",
                createdAtMs = now, updatedAtMs = now, archivedAtMs = null
            )
            val exam3 = com.batchfee.edu.data.models.ExamEntity(
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
                    com.batchfee.edu.data.models.ResultEntity(
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
                    com.batchfee.edu.data.models.ResultEntity(
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
                    com.batchfee.edu.data.models.StaffEntity(
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
                        com.batchfee.edu.data.models.StaffAttendanceEntity(
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
                        com.batchfee.edu.data.models.SalaryEntity(
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
                    com.batchfee.edu.data.models.EnquiryEntity(
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
            listOf(
                com.batchfee.edu.data.models.ReminderTemplateEntity(
                    id = "demo_reminder_1", instituteId = demoInstituteId,
                    title = "Monthly Fee Reminder",
                    type = "Fee",
                    messageTemplate = "Dear Guardian, kindly pay the monthly fee of {amount} BDT for {studentName} by {dueDate}. — BatchFee",
                    isDefault = true, createdAtMs = now, updatedAtMs = now
                ),
                com.batchfee.edu.data.models.ReminderTemplateEntity(
                    id = "demo_reminder_2", instituteId = demoInstituteId,
                    title = "Birthday Wish",
                    type = "Birthday",
                    messageTemplate = "Happy Birthday, {studentName}! Wishing you a fantastic day from all of us at {instituteName}. 🎂",
                    isDefault = true, createdAtMs = now, updatedAtMs = now
                ),
                com.batchfee.edu.data.models.ReminderTemplateEntity(
                    id = "demo_reminder_3", instituteId = demoInstituteId,
                    title = "Exam Schedule Alert",
                    type = "Exam",
                    messageTemplate = "Dear Guardian, {studentName}'s exam ({examName}) is on {examDate}. Please ensure attendance. — {instituteName}",
                    isDefault = false, createdAtMs = now, updatedAtMs = now
                )
            ).forEach { template ->
                db.reminderTemplateDao().insertTemplate(template)
                try {
                    ReminderTemplateSyncHelper.upsertTemplate(template)
                } catch (_: Exception) { }
            }

            try {
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val studentCount = db.studentDao().getStudentsByInstituteOnce(demoInstituteId).size
                val staffCount = db.staffDao().getStaffByInstituteAsList(demoInstituteId).size
                firestore.collection("institutes").document(demoInstituteId).set(
                    mapOf(
                        "instituteName" to "BatchFee Demo Institute",
                        "instituteCode" to "BGS-100",
                        "ownerName" to "Demo Owner",
                        "email" to "demo@batchfee.app",
                        "phone" to "+8801712345678",
                        "whatsappNumber" to "+8801712345678",
                        "role" to "owner",
                        "createdAt" to now,
                        "isActive" to true,
                        "trialEndDate" to (now + SubscriptionPolicy.FREE_TRIAL_DURATION_MS),
                        "currentPeriodEndMs" to (now + SubscriptionPolicy.FREE_TRIAL_DURATION_MS),
                        "currentPlanId" to "plan_free_trial",
                        "subscriptionStatus" to "trial",
                        "studentLimit" to 50,
                        "staffLimit" to 1,
                        "studentCount" to studentCount,
                        "staffCount" to staffCount,
                        "batchCount" to 0,
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

