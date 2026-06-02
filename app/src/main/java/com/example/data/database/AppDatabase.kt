package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.InstituteDao
import com.example.data.dao.SubscriptionPlanDao
import com.example.data.dao.UserDao
import com.example.data.models.InstituteEntity
import com.example.data.models.SubscriptionPlanEntity
import com.example.data.models.UserEntity
import com.example.domain.PasswordHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    version = 11,
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

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "batchfee_database"
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .build()
                INSTANCE = instance
                instance
            }
        }

        suspend fun ensureDemoDataSeeded(db: AppDatabase) {
            withContext(Dispatchers.IO) {
                try {
                    val user = db.userDao().getUserByEmail("admin@batchfee.app")
                    if (user == null) {
                        populateInitialPlans(db.subscriptionPlanDao())
                        populateSuperAdmin(db.userDao(), db.instituteDao())
                        populateDemoData(db)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
                     maxStudents = 100, maxBatches = 5, maxUsers = 1, maxBranches = 1,
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
            userDao.insertUser(
                UserEntity(
                    id = "sys_super_admin_1",
                    instituteId = null,
                    name = "System Admin",
                    email = "admin@batchfee.app",
                    passwordHash = PasswordHasher.hash("123456"),
                    role = "SuperAdmin",
                    createdAtMs = System.currentTimeMillis()
                )
            )
            
            val demoInstituteId = "demo_institute_1"
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
                    createdAtMs = now
                )
            )
            
            userDao.insertUser(
                UserEntity(
                    id = "demo_owner_1",
                    instituteId = demoInstituteId,
                    name = "Demo Owner",
                    email = "owner@batchfee.app",
                    passwordHash = PasswordHasher.hash("123456"),
                    role = "InstituteOwner",
                    createdAtMs = now
                )
            )
        }
        suspend fun populateDemoData(db: AppDatabase) {
            val demoInstituteId = "demo_institute_1"
            val now = System.currentTimeMillis()

            // Insert 5 Demo Students
            val students = (1..5).map {
                com.example.data.models.StudentEntity(
                    id = "demo_student_$it",
                    instituteId = demoInstituteId,
                    studentCode = "STU00$it",
                    fullName = "Student $it",
                    photoUri = null,
                    gender = "Male",
                    dateOfBirthMs = now - (it * 365 * 24 * 60 * 60 * 1000L),
                    phone = "0170000000$it",
                    email = if (it % 2 == 0) "student$it@demo.com" else null,
                    address = "123 Demo St",
                    schoolName = "Demo High School",
                    className = "Class ${it + 5}",
                    guardianName = "Guardian $it",
                    guardianPhone = "0180000000$it",
                    guardianEmail = null,
                    emergencyContact = "0190000000$it",
                    bloodGroup = "O+",
                    admissionDateMs = now,
                    status = "active",
                    notes = null,
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
            }
            students.forEach { db.studentDao().insertStudent(it) }

            // Insert 2 Demo Batches
            val batches = (1..2).map {
                com.example.data.models.BatchEntity(
                    id = "demo_batch_$it",
                    instituteId = demoInstituteId,
                    batchCode = "BAT00$it",
                    name = "Batch $it",
                    subject = if (it == 1) "Physics" else "Chemistry",
                    className = "Class ${it + 9}",
                    teacherName = "Teacher 1",
                    monthlyFeeAmount = if (it == 1) 1500.0 else 2000.0,
                    admissionFeeAmount = 500.0,
                    startDateMs = now,
                    endDateMs = now + (30L * 24 * 60 * 60 * 1000),
                    scheduleDays = "Mon, Wed, Fri",
                    startTime = "16:00",
                    endTime = "18:00",
                    maxStudents = 30,
                    status = "active",
                    description = "Demo batch description",
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
            }
            db.batchDao().insertBatch(batches[0])
            db.batchDao().insertBatch(batches[1])

            // Enroll Student 1 to Batch 1
            db.batchStudentDao().enrollStudent(
                com.example.data.models.BatchStudentEntity(
                    id = "demo_enrollment_1",
                    instituteId = demoInstituteId,
                    batchId = "demo_batch_1",
                    studentId = "demo_student_1",
                    joinedAtMs = now,
                    status = "active",
                    leftAtMs = null
                )
            )

            // Insert 1 Fee Due for Student 1 in Batch 1
            db.feeDao().insertFee(
                com.example.data.models.FeeEntity(
                    id = "demo_fee_1",
                    instituteId = demoInstituteId,
                    studentId = "demo_student_1",
                    batchId = "demo_batch_1",
                    feePeriod = "May 2026",
                    feeType = "Monthly",
                    dueDateMs = now + (5L * 24 * 60 * 60 * 1000),
                    baseAmount = 1500.0,
                    discountAmount = 0.0,
                    lateFeeAmount = 0.0,
                    totalAmount = 1500.0,
                    paidAmount = 500.0,
                    dueAmount = 1000.0,
                    status = "partially_paid",
                    note = "Demo fee",
                    createdAtMs = now,
                    updatedAtMs = now,
                    cancelledAtMs = null
                )
            )

            // Insert 1 Payment and Receipt (Mocking paid fee)
            db.paymentDao().insertPayment(
                com.example.data.models.PaymentEntity(
                    id = "demo_payment_1",
                    instituteId = demoInstituteId,
                    feeId = "demo_fee_1",
                    studentId = "demo_student_1",
                    amount = 500.0,
                    paymentDateMs = now,
                    paymentMethod = "cash",
                    transactionId = "TXN001",
                    receiptNumber = "REC-001",
                    status = "completed",
                    note = "Partial payment",
                    collectedByUserId = "demo_owner_1",
                    createdAtMs = now,
                    updatedAtMs = now
                )
            )
            
            db.receiptDao().insertReceipt(
                com.example.data.models.ReceiptEntity(
                    id = "demo_receipt_1",
                    instituteId = demoInstituteId,
                    feeId = "demo_fee_1",
                    studentId = "demo_student_1",
                    paymentId = "demo_payment_1",
                    receiptNumber = "REC-001",
                    receiptDateMs = now,
                    totalAmount = 1500.0,
                    paidAmount = 500.0,
                    dueAmount = 1000.0,
                    paymentMethod = "cash",
                    receiptText = "Received partial payment",
                    createdAtMs = now
                )
            )

            // Insert 1 Staff Member
            db.staffDao().insertStaff(
                com.example.data.models.StaffEntity(
                    id = "demo_staff_1",
                    instituteId = demoInstituteId,
                    staffCode = "STF001",
                    fullName = "Staff Member 1",
                    photoUri = null,
                    phone = "01800000001",
                    email = "staff1@demo.com",
                    address = "Demo Street",
                    roleTitle = "Teacher",
                    joiningDateMs = now - (30L * 24 * 60 * 60 * 1000),
                    monthlySalary = 15000.0,
                    assignedBatchIds = "demo_batch_1",
                    status = "active",
                    notes = null,
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
            )

            // Insert 1 Expense
            db.expenseDao().insertExpense(
                com.example.data.models.ExpenseEntity(
                    id = "demo_expense_1",
                    instituteId = demoInstituteId,
                    category = "Utilities",
                    title = "Electricity Bill",
                    expenseDateMs = now - (2L * 24 * 60 * 60 * 1000),
                    amount = 1200.0,
                    paymentMethod = "cash",
                    description = "Monthly electricity bill",
                    attachmentUri = null,
                    createdByUserId = "demo_owner_1",
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
            )

            // Insert 1 Exam
            db.examDao().insertExam(
                com.example.data.models.ExamEntity(
                    id = "demo_exam_1",
                    instituteId = demoInstituteId,
                    batchId = "demo_batch_1",
                    examName = "Monthly Test 1",
                    subject = "Physics",
                    examDateMs = now + (10L * 24 * 60 * 60 * 1000),
                    totalMarks = 100.0,
                    passingMarks = 40.0,
                    teacherName = "Teacher 1",
                    note = null,
                    status = "scheduled",
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
            )
        }
    }
}
