package com.batchfee.student.demo

import com.batchfee.student.data.models.*
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DemoDataProvider {

    const val INSTITUTE_ID = "demo_institute_topguide"
    const val STUDENT_ID = "demo_student_rahat"
    const val BATCH_SCIENCE_ID = "demo_batch_science"
    const val BATCH_ENGLISH_ID = "demo_batch_english"

    private val now = System.currentTimeMillis()
    private val dayMs = 86_400_000L

    val mockStudent = Student(
        id = STUDENT_ID,
        instituteId = INSTITUTE_ID,
        studentCode = "STU-2026-042",
        fullName = "Rahat Hossain",
        photoUri = null,
        gender = "Male",
        dateOfBirthMs = now - (16L * 365 * dayMs),
        phone = "01712-345678",
        email = "rahat.hossain@example.com",
        address = "12/3, Mirpur Road, Dhaka - 1205",
        schoolName = "Govt. Science College, Dhaka",
        className = "SSC 2026",
        guardianName = "Md. Kamal Hossain",
        guardianPhone = "01711-987654",
        guardianEmail = "kamal.hossain@example.com",
        emergencyContact = "01912-345678",
        bloodGroup = "B+",
        admissionDateMs = now - (18L * 30 * dayMs),
        status = "active"
    )

    val mockInstitute = Institute(
        id = INSTITUTE_ID,
        name = "Top Guide Coaching Center",
        phone = "01711-111111",
        address = "45/2, Elephant Road, Dhaka",
        whatsappNumber = "01711-111112",
        ownerName = "Md. Shahidul Islam",
        email = "topguide@example.com",
        instituteCode = "TGC",
        profilePhotoUri = null
    )

    val mockBatches = listOf(
        Batch(
            id = BATCH_SCIENCE_ID,
            instituteId = INSTITUTE_ID,
            batchCode = "SSC26-SCI",
            name = "SSC 2026 (Science)",
            subject = "Physics, Chemistry, Mathematics",
            className = "SSC 2026",
            teacherName = "Mohammad Ali",
            monthlyFeeAmount = 2000.0,
            scheduleDays = "Sat, Mon, Wed",
            startTime = "4:00 PM",
            endTime = "6:00 PM",
            status = "active"
        ),
        Batch(
            id = BATCH_ENGLISH_ID,
            instituteId = INSTITUTE_ID,
            batchCode = "SSC26-ENG",
            name = "SSC 2026 (English)",
            subject = "English 1st & 2nd Paper",
            className = "SSC 2026",
            teacherName = "Ms. Fatima Begum",
            monthlyFeeAmount = 1500.0,
            scheduleDays = "Sun, Tue, Thu",
            startTime = "3:00 PM",
            endTime = "4:00 PM",
            status = "active"
        )
    )

    val mockFees = listOf(
        Fee(
            id = "fee_jan",
            instituteId = INSTITUTE_ID,
            studentId = STUDENT_ID,
            batchId = BATCH_SCIENCE_ID,
            feePeriod = "January 2026",
            feeType = "Monthly Tuition",
            dueDateMs = now - (45 * dayMs),
            baseAmount = 2000.0,
            discountAmount = 0.0,
            lateFeeAmount = 0.0,
            totalAmount = 2000.0,
            paidAmount = 2000.0,
            dueAmount = 0.0,
            status = "paid",
            note = null,
            createdAtMs = now - (75 * dayMs),
            updatedAtMs = now - (40 * dayMs)
        ),
        Fee(
            id = "fee_feb",
            instituteId = INSTITUTE_ID,
            studentId = STUDENT_ID,
            batchId = BATCH_SCIENCE_ID,
            feePeriod = "February 2026",
            feeType = "Monthly Tuition",
            dueDateMs = now - (15 * dayMs),
            baseAmount = 2000.0,
            discountAmount = 0.0,
            lateFeeAmount = 0.0,
            totalAmount = 2000.0,
            paidAmount = 2000.0,
            dueAmount = 0.0,
            status = "paid",
            note = null,
            createdAtMs = now - (45 * dayMs),
            updatedAtMs = now - (10 * dayMs)
        ),
        Fee(
            id = "fee_mar",
            instituteId = INSTITUTE_ID,
            studentId = STUDENT_ID,
            batchId = BATCH_SCIENCE_ID,
            feePeriod = "March 2026",
            feeType = "Monthly Tuition",
            dueDateMs = now + (15 * dayMs),
            baseAmount = 2000.0,
            discountAmount = 0.0,
            lateFeeAmount = 0.0,
            totalAmount = 2000.0,
            paidAmount = 1000.0,
            dueAmount = 1000.0,
            status = "partially_paid",
            note = "Half paid, remaining due",
            createdAtMs = now - (15 * dayMs),
            updatedAtMs = now - (2 * dayMs)
        ),
        Fee(
            id = "fee_apr",
            instituteId = INSTITUTE_ID,
            studentId = STUDENT_ID,
            batchId = BATCH_ENGLISH_ID,
            feePeriod = "April 2026",
            feeType = "English Course Fee",
            dueDateMs = now + (45 * dayMs),
            baseAmount = 1500.0,
            discountAmount = 0.0,
            lateFeeAmount = 0.0,
            totalAmount = 1500.0,
            paidAmount = 0.0,
            dueAmount = 1500.0,
            status = "unpaid",
            note = null,
            createdAtMs = now - (5 * dayMs),
            updatedAtMs = now - (5 * dayMs)
        )
    )

    val mockPayments = listOf(
        Payment(
            id = "pay_jan",
            instituteId = INSTITUTE_ID,
            feeId = "fee_jan",
            studentId = STUDENT_ID,
            amount = 2000.0,
            paymentMethod = "cash",
            transactionId = null,
            receiptNumber = "RCP-2026-0001",
            paymentDateMs = now - (40 * dayMs),
            status = "completed",
            note = null
        ),
        Payment(
            id = "pay_feb",
            instituteId = INSTITUTE_ID,
            feeId = "fee_feb",
            studentId = STUDENT_ID,
            amount = 2000.0,
            paymentMethod = "bkash",
            transactionId = "BKASH-9A8B7C",
            receiptNumber = "RCP-2026-0002",
            paymentDateMs = now - (10 * dayMs),
            status = "completed",
            note = null
        ),
        Payment(
            id = "pay_mar_partial",
            instituteId = INSTITUTE_ID,
            feeId = "fee_mar",
            studentId = STUDENT_ID,
            amount = 1000.0,
            paymentMethod = "cash",
            transactionId = null,
            receiptNumber = "RCP-2026-0003",
            paymentDateMs = now - (2 * dayMs),
            status = "completed",
            note = "Partial payment"
        )
    )

    val mockReceipts = listOf(
        Receipt(
            id = "rcpt_jan",
            instituteId = INSTITUTE_ID,
            paymentId = "pay_jan",
            feeId = "fee_jan",
            studentId = STUDENT_ID,
            receiptNumber = "RCP-2026-0001",
            receiptDateMs = now - (40 * dayMs),
            totalAmount = 2000.0,
            paidAmount = 2000.0,
            dueAmount = 0.0,
            paymentMethod = "Cash",
            receiptText = "Received full payment for January 2026 tuition.",
            createdAtMs = now - (40 * dayMs)
        ),
        Receipt(
            id = "rcpt_feb",
            instituteId = INSTITUTE_ID,
            paymentId = "pay_feb",
            feeId = "fee_feb",
            studentId = STUDENT_ID,
            receiptNumber = "RCP-2026-0002",
            receiptDateMs = now - (10 * dayMs),
            totalAmount = 2000.0,
            paidAmount = 2000.0,
            dueAmount = 0.0,
            paymentMethod = "bKash",
            receiptText = "Received via bKash. Thank you.",
            createdAtMs = now - (10 * dayMs)
        ),
        Receipt(
            id = "rcpt_mar_partial",
            instituteId = INSTITUTE_ID,
            paymentId = "pay_mar_partial",
            feeId = "fee_mar",
            studentId = STUDENT_ID,
            receiptNumber = "RCP-2026-0003",
            receiptDateMs = now - (2 * dayMs),
            totalAmount = 2000.0,
            paidAmount = 1000.0,
            dueAmount = 1000.0,
            paymentMethod = "Cash",
            receiptText = "Partial payment received. Due: 1,000 Taka.",
            createdAtMs = now - (2 * dayMs)
        )
    )

    // ── 90 days of attendance (3 months) ──
    private val totalDays = 90
    val mockAttendance = (0 until totalDays).flatMap { dayOffset ->
        val dateMs = now - (dayOffset.toLong() * dayMs)
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = dateMs }.get(Calendar.DAY_OF_WEEK)
        // Skip weekends (Sat=7, Sun=1 in Bangladesh where Fri=6 is weekend)
        val isWeekend = dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY
        if (isWeekend) return@flatMap emptyList()

        val status = when {
            dayOffset == 2 || dayOffset == 3 -> "absent"      // This month
            dayOffset == 8 || dayOffset == 11 -> "late"        // This month
            dayOffset == 35 || dayOffset == 40 -> "absent"     // Last month
            dayOffset == 38 -> "late"                           // Last month
            dayOffset == 65 || dayOffset == 72 -> "absent"      // Two months ago
            dayOffset == 68 -> "late"                           // Two months ago
            dayOffset == 80 || dayOffset == 85 -> "absent"      // Older
            else -> "present"
        }
        listOf(
            Attendance(
                id = "att_sci_${dayOffset}",
                instituteId = INSTITUTE_ID,
                batchId = BATCH_SCIENCE_ID,
                studentId = STUDENT_ID,
                attendanceDateMs = dateMs,
                status = status,
                note = if (status == "absent") "Medical reason" else null
            ),
            Attendance(
                id = "att_eng_${dayOffset}",
                instituteId = INSTITUTE_ID,
                batchId = BATCH_ENGLISH_ID,
                studentId = STUDENT_ID,
                attendanceDateMs = dateMs - (dayMs / 2),
                status = if (status == "absent") "absent" else "present",
                note = null
            )
        )
    }

    fun getAttendanceForBatch(batchId: String): List<Attendance> {
        return mockAttendance.filter { it.batchId == batchId }
    }

    fun getAttendanceForPeriod(batchId: String, periodStartMs: Long, periodEndMs: Long): List<Attendance> {
        return mockAttendance.filter {
            it.batchId == batchId && it.attendanceDateMs in periodStartMs..periodEndMs
        }
    }

    fun getAttendanceSummaryForPeriod(batchId: String, periodStartMs: Long, periodEndMs: Long): AttendanceSummary {
        val records = getAttendanceForPeriod(batchId, periodStartMs, periodEndMs)
        return AttendanceSummary(
            totalClasses = records.size,
            present = records.count { it.status == "present" },
            absent = records.count { it.status == "absent" },
            late = records.count { it.status == "late" }
        )
    }

    fun getAttendanceSummaryForBatch(batchId: String): AttendanceSummary {
        val records = getAttendanceForBatch(batchId)
        return AttendanceSummary(
            totalClasses = records.size,
            present = records.count { it.status == "present" },
            absent = records.count { it.status == "absent" },
            late = records.count { it.status == "late" }
        )
    }

    fun getWeekBounds(weekOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset)
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_WEEK, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        return start to cal.timeInMillis
    }

    fun getMonthBounds(monthOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        return start to cal.timeInMillis
    }

    fun getTodayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        return start to cal.timeInMillis
    }

    val mockExams = listOf(
        Exam(
            id = "exam_midterm_physics",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_SCIENCE_ID,
            examName = "Midterm Examination",
            subject = "Physics",
            examDateMs = now - (30 * dayMs),
            totalMarks = 100.0,
            passingMarks = 33.0,
            teacherName = "Mohammad Ali",
            note = "Chapter 1-5 covered",
            status = "completed"
        ),
        Exam(
            id = "exam_midterm_chem",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_SCIENCE_ID,
            examName = "Midterm Examination",
            subject = "Chemistry",
            examDateMs = now - (28 * dayMs),
            totalMarks = 100.0,
            passingMarks = 33.0,
            teacherName = "Mohammad Ali",
            note = "Full syllabus",
            status = "completed"
        ),
        Exam(
            id = "exam_final_physics",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_SCIENCE_ID,
            examName = "Final Examination",
            subject = "Physics",
            examDateMs = now + (60 * dayMs),
            totalMarks = 100.0,
            passingMarks = 33.0,
            teacherName = "Mohammad Ali",
            note = "Full syllabus",
            status = "upcoming"
        ),
        Exam(
            id = "exam_english_weekly",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_ENGLISH_ID,
            examName = "Weekly Test",
            subject = "English Grammar",
            examDateMs = now - (7 * dayMs),
            totalMarks = 30.0,
            passingMarks = 10.0,
            teacherName = "Ms. Fatima Begum",
            note = "Tenses & Vocabulary",
            status = "completed"
        )
    )

    val mockResults = listOf(
        Result(
            id = "res_physics_midterm",
            instituteId = INSTITUTE_ID,
            examId = "exam_midterm_physics",
            batchId = BATCH_SCIENCE_ID,
            studentId = STUDENT_ID,
            marksObtained = 85.0,
            grade = "A+",
            position = 3,
            remarks = "Excellent work! Keep it up.",
            published = true
        ),
        Result(
            id = "res_chem_midterm",
            instituteId = INSTITUTE_ID,
            examId = "exam_midterm_chem",
            batchId = BATCH_SCIENCE_ID,
            studentId = STUDENT_ID,
            marksObtained = 72.0,
            grade = "A",
            position = 7,
            remarks = "Good, but needs more practice in organic chemistry.",
            published = true
        ),
        Result(
            id = "res_eng_weekly",
            instituteId = INSTITUTE_ID,
            examId = "exam_english_weekly",
            batchId = BATCH_ENGLISH_ID,
            studentId = STUDENT_ID,
            marksObtained = 28.0,
            grade = "A+",
            position = 1,
            remarks = "Outstanding performance!",
            published = true
        )
    )

    val mockHomework = listOf(
        Homework(
            id = "hw_physics_01",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_SCIENCE_ID,
            subject = "Physics",
            title = "Chapter 6: Gravitation Exercise",
            description = "Solve problems 6.1 to 6.15 from the textbook. Write all steps clearly. Submit in the next class.",
            attachmentUrl = null,
            assignedDateMs = now - (7 * dayMs),
            deadlineDateMs = now + (1 * dayMs),
            createdAtMs = now - (7 * dayMs)
        ),
        Homework(
            id = "hw_chem_01",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_SCIENCE_ID,
            subject = "Chemistry",
            title = "Periodic Table Worksheet",
            description = "Complete the periodic table worksheet with element properties, electronic configurations, and group trends.",
            attachmentUrl = null,
            assignedDateMs = now - (5 * dayMs),
            deadlineDateMs = now + (3 * dayMs),
            createdAtMs = now - (5 * dayMs)
        ),
        Homework(
            id = "hw_english_01",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_ENGLISH_ID,
            subject = "English",
            title = "Paragraph Writing: My Future Plan",
            description = "Write a 250-word paragraph about your future career plans. Use correct grammar and punctuation.",
            attachmentUrl = null,
            assignedDateMs = now - (14 * dayMs),
            deadlineDateMs = now - (2 * dayMs),
            createdAtMs = now - (14 * dayMs)
        ),
        Homework(
            id = "hw_math_01",
            instituteId = INSTITUTE_ID,
            batchId = BATCH_SCIENCE_ID,
            subject = "Mathematics",
            title = "Algebra: Quadratic Equations",
            description = "Solve 20 quadratic equations using factorization and quadratic formula methods. Show all working steps.",
            attachmentUrl = null,
            assignedDateMs = now - (1 * dayMs),
            deadlineDateMs = now + (6 * dayMs),
            createdAtMs = now - (1 * dayMs)
        )
    )

    val mockNotices = listOf(
        Notice(
            id = "notice_emergency",
            instituteId = INSTITUTE_ID,
            title = "⚡ Important: Schedule Change for This Week",
            body = "Due to the upcoming national holiday, all classes for Thursday and Friday are rescheduled to Saturday and Sunday. Regular class schedule will resume from Monday. Please check the updated routine on the notice board.\n\n- Saturday: Science batch at 4 PM (instead of Thursday)\n- Sunday: English batch at 3 PM (instead of Friday)\n\nContact the office if you have any questions.",
            targetBatchIds = null,
            priority = "emergency",
            attachmentUrl = null,
            createdAtMs = now - (2 * dayMs),
            createdByUserId = "admin_owner"
        ),
        Notice(
            id = "notice_exam",
            instituteId = INSTITUTE_ID,
            title = "📝 Midterm Result Published",
            body = "Midterm examination results for Science batch have been published. Students can view their marksheets and merit positions in the Exams section of the app. Parent-teacher meeting will be held next Saturday at 4 PM. All parents are requested to attend.",
            targetBatchIds = listOf(BATCH_SCIENCE_ID),
            priority = "normal",
            attachmentUrl = null,
            createdAtMs = now - (6 * dayMs),
            createdByUserId = "admin_owner"
        ),
        Notice(
            id = "notice_fee",
            instituteId = INSTITUTE_ID,
            title = "💰 March Fee Reminder",
            body = "This is a gentle reminder that March tuition fees are due by the 15th of this month. Late payment will incur a late fee of 50 Taka per day. Please clear all dues at the earliest to avoid inconvenience.\n\nPayment methods: Cash, bKash (01711-111111), or Bank transfer.",
            targetBatchIds = null,
            priority = "normal",
            attachmentUrl = null,
            createdAtMs = now - (3 * dayMs),
            createdByUserId = "admin_owner"
        ),
        Notice(
            id = "notice_holiday",
            instituteId = INSTITUTE_ID,
            title = "🎉 Holiday Notice: Independence Day",
            body = "The institute will remain closed on 26th March (Wednesday) on the occasion of Independence Day. All classes will resume as usual from 27th March (Thursday).\n\nWishing everyone a Happy Independence Day!",
            targetBatchIds = null,
            priority = "normal",
            attachmentUrl = null,
            createdAtMs = now - (20 * dayMs),
            createdByUserId = "admin_owner"
        )
    )

}
