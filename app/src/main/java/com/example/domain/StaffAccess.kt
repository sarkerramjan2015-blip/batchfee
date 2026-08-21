package com.batchfee.edu.domain

data class StaffPermissionOption(
    val key: String,
    val label: String,
    val description: String
)

object StaffPermissions {
    const val VIEW_STUDENTS = "view_student"
    const val MANAGE_STUDENTS = "manage_student"
    const val VIEW_BATCHES = "view_batch"
    const val MANAGE_BATCHES = "manage_batch"
    const val VIEW_FEE_SUMMARY = "view_fee_summary"
    const val COLLECT_FEE = "collect_fee"
    const val SEND_DUE_MESSAGE = "send_due_message"
    const val TAKE_ATTENDANCE = "take_attendance"
    const val VIEW_ATTENDANCE_REPORTS = "view_attendance_reports"
    const val MANAGE_STAFF = "manage_staff"
    const val MANAGE_STAFF_ATTENDANCE = "manage_staff_attendance"
    const val MANAGE_SALARY = "manage_salary"
    const val MANAGE_EXPENSES = "manage_expenses"
    const val VIEW_REPORTS = "view_reports"
    const val MANAGE_EXAMS = "manage_exams"
    const val GENERATE_ID_CARDS = "generate_id_cards"
    const val BIRTHDAY_REMINDERS = "birthday_reminders"
    const val MANAGE_REMINDERS = "manage_reminders"

    val options = listOf(
        StaffPermissionOption(VIEW_STUDENTS, "View Students", "Open student lists and profiles"),
        StaffPermissionOption(MANAGE_STUDENTS, "Manage Students", "Add and edit student records"),
        StaffPermissionOption(VIEW_BATCHES, "View Batches", "Open batch details"),
        StaffPermissionOption(MANAGE_BATCHES, "Manage Batches", "Create and edit batches"),
        StaffPermissionOption(VIEW_FEE_SUMMARY, "View Fees", "See due fees and fee dashboard"),
        StaffPermissionOption(COLLECT_FEE, "Collect Fees", "Collect payments and create receipts"),
        StaffPermissionOption(SEND_DUE_MESSAGE, "Due Messages", "Send due-fee and absent messages"),
        StaffPermissionOption(TAKE_ATTENDANCE, "Take Attendance", "Mark student attendance"),
        StaffPermissionOption(VIEW_ATTENDANCE_REPORTS, "Attendance Reports", "View attendance reports"),
        StaffPermissionOption(MANAGE_STAFF, "Manage Staff", "Add, edit, archive staff accounts"),
        StaffPermissionOption(MANAGE_STAFF_ATTENDANCE, "Staff Attendance", "Mark staff attendance"),
        StaffPermissionOption(MANAGE_SALARY, "Manage Salary", "Generate and pay salaries"),
        StaffPermissionOption(MANAGE_EXPENSES, "Manage Expenses", "Record institute expenses"),
        StaffPermissionOption(VIEW_REPORTS, "View Reports", "Open reports and profit/loss"),
        StaffPermissionOption(MANAGE_EXAMS, "Manage Exams", "Create exams and results"),
        StaffPermissionOption(GENERATE_ID_CARDS, "ID Cards", "Generate student ID cards"),
        StaffPermissionOption(BIRTHDAY_REMINDERS, "Birthdays", "Open birthday reminders"),
        StaffPermissionOption(MANAGE_REMINDERS, "Reminders", "Edit reminder templates")
    )

    fun toCsv(permissions: Set<String>): String? =
        permissions.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",").takeIf { it.isNotEmpty() }

    fun parse(raw: String?): Set<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun labelFor(key: String): String =
        options.firstOrNull { it.key == key }?.label
            ?: key.replace("_", " ").replaceFirstChar { it.uppercase() }
}

object AccessControl {
    private val alwaysAllowedRoutes = setOf("DashboardRoute", "More", "WorksListRoute", "AddWorkRoute", "HomeworkListRoute", "AddHomeworkRoute", "AssignmentListRoute", "AddAssignmentRoute")

    private val adminOnlyRoutes = setOf(
        "SettingsRoute",
        "BillingRoute",
        "PricingRoute",
        "BackupRestoreRoute",
        "StaffActivityRoute",
        "StudentActivityRoute",
        "RoutineRoute",
        "AllArchivesRoute"
    )

    private val routePermissions = mapOf(
        "StudentsRoute" to setOf(StaffPermissions.VIEW_STUDENTS, StaffPermissions.MANAGE_STUDENTS),
        "ArchivedStudentsRoute" to setOf(StaffPermissions.MANAGE_STUDENTS),
        "StudentProfileRoute" to setOf(StaffPermissions.VIEW_STUDENTS, StaffPermissions.MANAGE_STUDENTS),
        "AddStudentRoute" to setOf(StaffPermissions.MANAGE_STUDENTS),
        "EditStudentRoute" to setOf(StaffPermissions.MANAGE_STUDENTS),
        "BatchesRoute" to setOf(StaffPermissions.VIEW_BATCHES, StaffPermissions.MANAGE_BATCHES),
        "BatchDetailRoute" to setOf(StaffPermissions.VIEW_BATCHES, StaffPermissions.MANAGE_BATCHES),
        "AddBatchRoute" to setOf(StaffPermissions.MANAGE_BATCHES),
        "EditBatchRoute" to setOf(StaffPermissions.MANAGE_BATCHES),
        "EnrollStudentsRoute" to setOf(StaffPermissions.MANAGE_BATCHES),
        "FeeDashboardRoute" to setOf(StaffPermissions.VIEW_FEE_SUMMARY, StaffPermissions.COLLECT_FEE),
        "DueFeesRoute" to setOf(StaffPermissions.VIEW_FEE_SUMMARY, StaffPermissions.COLLECT_FEE, StaffPermissions.SEND_DUE_MESSAGE),
        "CreateFeeRoute" to setOf(StaffPermissions.COLLECT_FEE),
        "UnifiedCollectRoute" to setOf(StaffPermissions.COLLECT_FEE),
        "CollectPaymentRoute" to setOf(StaffPermissions.COLLECT_FEE),
        "ReceiptDetailRoute" to setOf(StaffPermissions.COLLECT_FEE),
        "AttendanceRoute" to setOf(StaffPermissions.TAKE_ATTENDANCE),
        "TakeAttendanceRoute" to setOf(StaffPermissions.TAKE_ATTENDANCE),
        "AttendanceReportRoute" to setOf(StaffPermissions.VIEW_ATTENDANCE_REPORTS, StaffPermissions.VIEW_REPORTS),
        "ReportsRoute" to setOf(StaffPermissions.VIEW_REPORTS),
        "ProfitLossRoute" to setOf(StaffPermissions.VIEW_REPORTS),
        "ReminderTemplatesRoute" to setOf(StaffPermissions.MANAGE_REMINDERS),
        "StaffRoute" to setOf(StaffPermissions.MANAGE_STAFF),
        "StaffProfileRoute" to setOf(StaffPermissions.MANAGE_STAFF),
        "AddStaffRoute" to setOf(StaffPermissions.MANAGE_STAFF),
        "EditStaffRoute" to setOf(StaffPermissions.MANAGE_STAFF),
        "StaffAttendanceRoute" to setOf(StaffPermissions.MANAGE_STAFF_ATTENDANCE, StaffPermissions.MANAGE_STAFF),
        "StaffAttendanceReportRoute" to setOf(StaffPermissions.MANAGE_STAFF_ATTENDANCE, StaffPermissions.MANAGE_STAFF),
        "SalaryRoute" to setOf(StaffPermissions.MANAGE_SALARY),
        "GenerateSalaryRoute" to setOf(StaffPermissions.MANAGE_SALARY),
        "SalaryDetailRoute" to setOf(StaffPermissions.MANAGE_SALARY),
        "ExpensesRoute" to setOf(StaffPermissions.MANAGE_EXPENSES),
        "AddExpenseRoute" to setOf(StaffPermissions.MANAGE_EXPENSES),
        "ExpenseReportRoute" to setOf(StaffPermissions.MANAGE_EXPENSES, StaffPermissions.VIEW_REPORTS),
        "ExamsRoute" to setOf(StaffPermissions.MANAGE_EXAMS),
        "CreateExamRoute" to setOf(StaffPermissions.MANAGE_EXAMS),
        "ExamDetailRoute" to setOf(StaffPermissions.MANAGE_EXAMS),
        "AddResultRoute" to setOf(StaffPermissions.MANAGE_EXAMS),
        "ResultReportRoute" to setOf(StaffPermissions.MANAGE_EXAMS, StaffPermissions.VIEW_REPORTS),
        "IdCardGeneratorRoute" to setOf(StaffPermissions.GENERATE_ID_CARDS),
        "IdCardPreviewRoute" to setOf(StaffPermissions.GENERATE_ID_CARDS),
        "BirthdayReminderRoute" to setOf(StaffPermissions.BIRTHDAY_REMINDERS),
        "EnquiryListRoute" to setOf(StaffPermissions.VIEW_REPORTS),
        "HomeworkListRoute" to setOf("VIEW_STUDENTS"),
        "AssignmentListRoute" to setOf("VIEW_STUDENTS")
    )

    fun isKnownRoute(route: String): Boolean {
        val baseRoute = route.substringBefore(":").substringBefore("?")
        return baseRoute in alwaysAllowedRoutes || baseRoute in adminOnlyRoutes || baseRoute in routePermissions
    }

    fun canAccessRoute(route: String): Boolean {
        val baseRoute = route.substringBefore(":").substringBefore("?")
        if (baseRoute in alwaysAllowedRoutes) return true
        if (SessionManager.isAdmin()) return true
        if (!SessionManager.isStaff()) return false
        if (baseRoute in adminOnlyRoutes) return false
        val required = routePermissions[baseRoute] ?: return false
        return required.any { SessionManager.hasPermission(it) }
    }
}

