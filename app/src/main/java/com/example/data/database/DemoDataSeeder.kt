package com.example.data.database

import com.example.data.models.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

object DemoDataSeeder {

    private val batch1Id = "demo_seed_batch_hsc27ict"
    private val batch2Id = "demo_seed_batch_ssc26math"

    private data class SeedStudent(val name: String, val phone: String, val gender: String)

    private val students = listOf(
        SeedStudent("Abid Mohammad Zaman", "+8801977956650", "Male"),
        SeedStudent("Ahadul Islam Arafat", "+8801630152980", "Male"),
        SeedStudent("Ahnaf Binte Masud", "+8801842111666", "Female"),
        SeedStudent("Alpha Shahriar Shishir", "+8801712102461", "Male"),
        SeedStudent("Asrafun Nisa Lumia", "+8801783412299", "Female"),
        SeedStudent("Aysha Akter", "+8801753956958", "Female"),
        SeedStudent("Bushra", "+8801926255064", "Female"),
        SeedStudent("Bushra Jahan Sadia", "+8801825469539", "Female"),
        SeedStudent("Emon", "+8801995685191", "Male"),
        SeedStudent("Entiha", "+8801518657869", "Female"),
        SeedStudent("Fahima Afrin Rim", "+8801864628826", "Female"),
        SeedStudent("Faiza Akter", "+8801626849299", "Female"),
        SeedStudent("Faria Akter", "+8801741235993", "Female"),
        SeedStudent("Fariha Chowdhury", "+8801985001330", "Female"),
        SeedStudent("Farin Jahan", "+8801764753406", "Female"),
        SeedStudent("Israt Jahan Moni", "+8801612288128", "Female"),
        SeedStudent("Israt Jahan Prity", "+8801746829021", "Female"),
        SeedStudent("Jannatul Ferdous", "+8801712345678", "Female"),
        SeedStudent("Md. Rakib Hasan", "+8801812345678", "Male"),
        SeedStudent("Nusrat Jahan Tisha", "+8801912345678", "Female")
    )

    suspend fun seed(db: AppDatabase, instituteId: String, userId: String) = withContext(Dispatchers.IO) {
        val firestore = FirebaseFirestore.getInstance()
        val now = System.currentTimeMillis()

        // ── 1. Batches ──
        val batch1 = BatchEntity(
            id = batch1Id, instituteId = instituteId, batchCode = "HSC27ICT-1",
            name = "HSC 27 ICT", subject = "ICT", className = "HSC 2nd Year",
            teacherName = "Md. Hasan", monthlyFeeAmount = 1500.0, admissionFeeAmount = 500.0,
            startDateMs = now - (180L * 24 * 60 * 60 * 1000), endDateMs = now + (180L * 24 * 60 * 60 * 1000),
            scheduleDays = "Sun, Tue, Thu", startTime = "16:00", endTime = "18:00",
            maxStudents = 30, status = "active", description = "HSC 2027 ICT batch",
            createdAtMs = now, updatedAtMs = now, archivedAtMs = null
        )
        val batch2 = BatchEntity(
            id = batch2Id, instituteId = instituteId, batchCode = "SSC26MATH-1",
            name = "SSC 26 Math", subject = "Mathematics", className = "SSC",
            teacherName = "Md. Karim", monthlyFeeAmount = 1200.0, admissionFeeAmount = 400.0,
            startDateMs = now - (180L * 24 * 60 * 60 * 1000), endDateMs = now + (180L * 24 * 60 * 60 * 1000),
            scheduleDays = "Mon, Wed, Sat", startTime = "10:00", endTime = "12:00",
            maxStudents = 25, status = "active", description = "SSC 2026 Mathematics batch",
            createdAtMs = now, updatedAtMs = now, archivedAtMs = null
        )

        // ── 2. Students + Enrollments ──
        val studentEntities = students.mapIndexed { i, s ->
            val studentId = "demo_seed_student_${i + 1}"
            val batchId = if (i < 10) batch1Id else batch2Id
            val code = if (i < 10) "HSC${(i + 1).toString().padStart(3, '0')}" else "SSC${(i - 10 + 1).toString().padStart(3, '0')}"
            Triple(
                StudentEntity(
                    id = studentId, instituteId = instituteId, studentCode = code,
                    fullName = s.name, photoUri = null, gender = s.gender,
                    dateOfBirthMs = now - (16L * 365 * 24 * 60 * 60 * 1000),
                    phone = s.phone, email = null,
                    address = "Dhaka, Bangladesh", schoolName = "Demo College",
                    className = if (i < 10) "HSC 2nd Year" else "SSC", guardianName = "Guardian of ${s.name}",
                    guardianPhone = s.phone, guardianEmail = null,
                    emergencyContact = s.phone, bloodGroup = null,
                    admissionDateMs = now, status = "active", notes = null,
                    createdAtMs = now, updatedAtMs = now, archivedAtMs = null
                ),
                batchId,
                BatchStudentEntity(
                    id = "demo_seed_enroll_${i + 1}", instituteId = instituteId,
                    batchId = batchId, studentId = studentId,
                    joinedAtMs = now, status = "active", leftAtMs = null
                )
            )
        }

        // ── 3. Attendance (last 5 dates) ──
        val attendanceRecords = mutableListOf<AttendanceEntity>()
        val random = Random(42)
        for (dayOffset in 1..5) {
            val dateMs = now - (dayOffset * 24L * 60 * 60 * 1000)
            val startOfDay = Calendar.getInstance().apply { timeInMillis = dateMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            studentEntities.forEachIndexed { i, triple ->
                val attendanceStatus = if (random.nextFloat() < 0.8f) "present" else "absent"
                attendanceRecords.add(
                    AttendanceEntity(
                        id = "demo_seed_att_${dayOffset}_${i + 1}",
                        instituteId = instituteId, batchId = triple.second,
                        studentId = triple.first.id,
                        attendanceDateMs = startOfDay,
                        status = attendanceStatus, note = null,
                        markedByUserId = userId, createdAtMs = now, updatedAtMs = now
                    )
                )
            }
        }

        // ── 4. Fees & Payments ──
        val feeEntities = mutableListOf<FeeEntity>()
        val paymentEntities = mutableListOf<PaymentEntity>()
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val currentMonthStart = cal.timeInMillis
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(currentMonthStart))
        val dueDateMs = cal.apply { add(Calendar.DAY_OF_MONTH, 7) }.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, 1)
        val nextMonthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(cal.timeInMillis))
        var receiptCounter = 0

        studentEntities.forEachIndexed { i, triple ->
            val (stu, batchId, _) = triple
            val monthlyFee = if (batchId == batch1Id) 1500.0 else 1200.0

            val feeId = "demo_seed_fee_${i + 1}"
            val (paidAmount, dueAmount, status) = when {
                i < 10 -> Triple(monthlyFee, 0.0, "paid")
                i < 15 -> Triple(1000.0, monthlyFee - 1000.0, "partially_paid")
                else -> Triple(monthlyFee + monthlyFee, 0.0, "paid")
            }

            feeEntities.add(
                FeeEntity(
                    id = feeId, instituteId = instituteId, studentId = stu.id,
                    batchId = batchId, feePeriod = monthLabel, feeType = "Monthly",
                    dueDateMs = dueDateMs, baseAmount = monthlyFee, discountAmount = 0.0,
                    lateFeeAmount = 0.0, totalAmount = monthlyFee,
                    paidAmount = paidAmount.coerceAtMost(monthlyFee + monthlyFee),
                    dueAmount = dueAmount, status = status,
                    note = null, createdAtMs = now, updatedAtMs = now, cancelledAtMs = null
                )
            )

            receiptCounter++
            paymentEntities.add(
                PaymentEntity(
                    id = "demo_seed_payment_${i + 1}", instituteId = instituteId,
                    feeId = feeId, studentId = stu.id,
                    amount = if (i < 10) monthlyFee else if (i < 15) 1000.0 else monthlyFee,
                    paymentMethod = if (i % 2 == 0) "cash" else "bkash",
                    transactionId = if (i % 2 == 0) null else "TXN${1000 + i}",
                    receiptNumber = "REC-DEMO-${1000 + receiptCounter}",
                    paymentDateMs = now, collectedByUserId = userId,
                    status = "completed", note = null,
                    createdAtMs = now, updatedAtMs = now
                )
            )

            if (i >= 15) {
                val nextFeeId = "demo_seed_fee_adv_${i + 1}"
                feeEntities.add(
                    FeeEntity(
                        id = nextFeeId, instituteId = instituteId, studentId = stu.id,
                        batchId = batchId, feePeriod = nextMonthLabel, feeType = "Advance",
                        dueDateMs = cal.timeInMillis, baseAmount = monthlyFee, discountAmount = 0.0,
                        lateFeeAmount = 0.0, totalAmount = monthlyFee,
                        paidAmount = monthlyFee, dueAmount = 0.0, status = "paid",
                        note = null, createdAtMs = now, updatedAtMs = now, cancelledAtMs = null
                    )
                )
                receiptCounter++
                paymentEntities.add(
                    PaymentEntity(
                        id = "demo_seed_payment_adv_${i + 1}", instituteId = instituteId,
                        feeId = nextFeeId, studentId = stu.id,
                        amount = monthlyFee,
                        paymentMethod = if (i % 2 == 0) "bkash" else "nagad",
                        transactionId = "TXN${2000 + i}",
                        receiptNumber = "REC-DEMO-${1000 + receiptCounter}",
                        paymentDateMs = now, collectedByUserId = userId,
                        status = "completed", note = "Advance payment",
                        createdAtMs = now, updatedAtMs = now
                    )
                )
            }
        }

        // ── 5. Write everything ──
        db.batchDao().insertBatch(batch1)
        db.batchDao().insertBatch(batch2)
        studentEntities.forEach { (stu, _, enroll) ->
            db.studentDao().insertStudent(stu)
            db.batchStudentDao().enrollStudent(enroll)
        }
        attendanceRecords.forEach { db.attendanceDao().insertOrUpdateAttendance(it) }
        feeEntities.forEach { db.feeDao().insertFee(it) }
        paymentEntities.forEach { db.paymentDao().insertPayment(it) }
    }
}
