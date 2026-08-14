package com.batchfee.edu.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object AuthRoute

@Serializable
object PrivacyPolicyRoute

@Serializable
object TermsConditionsRoute

@Serializable
object DashboardRoute

@Serializable
object SuperAdminRoute

@Serializable
object SubscriptionExpiredRoute

@Serializable
object PricingRoute

@Serializable
object BillingRoute

@Serializable object StudentsRoute
@Serializable object ArchivedStudentsRoute
@Serializable object AddStudentRoute
@Serializable data class EditStudentRoute(val studentId: String)
@Serializable data class StudentProfileRoute(val studentId: String)

@Serializable object BatchesRoute
@Serializable object AddBatchRoute
@Serializable data class EditBatchRoute(val batchId: String)
@Serializable data class BatchDetailRoute(val batchId: String)
@Serializable data class EnrollStudentsRoute(val batchId: String)

@Serializable object AttendanceRoute
@Serializable data class TakeAttendanceRoute(val batchId: String)
@Serializable object AttendanceReportRoute

@Serializable object FeeDashboardRoute
@Serializable object CreateFeeRoute
@Serializable data class CollectPaymentRoute(val feeId: String)
@Serializable object DueFeesRoute
@Serializable data class ReceiptDetailRoute(val paymentId: String)
@Serializable object UnifiedCollectRoute

@Serializable data class ReportsRoute(val period: String = "today")
@Serializable object ReminderTemplatesRoute

// Part 3 Routes

@Serializable object StaffRoute
@Serializable object AddStaffRoute
@Serializable data class EditStaffRoute(val staffId: String)
@Serializable data class StaffProfileRoute(val staffId: String)
@Serializable object StaffAttendanceRoute
@Serializable object StaffAttendanceReportRoute
@Serializable object SalaryRoute
@Serializable object GenerateSalaryRoute
@Serializable data class SalaryDetailRoute(val salaryId: String)
@Serializable object ExpensesRoute
@Serializable object AddExpenseRoute
@Serializable object ExpenseReportRoute
@Serializable object ProfitLossRoute
@Serializable object ExamsRoute
@Serializable object CreateExamRoute
@Serializable data class EditExamRoute(val examId: String)
@Serializable data class ExamDetailRoute(val examId: String)
@Serializable data class AddResultRoute(val examId: String)
@Serializable object ResultReportRoute
@Serializable object IdCardGeneratorRoute
@Serializable data class IdCardPreviewRoute(val type: String, val id: String)
@Serializable object BirthdayReminderRoute
@Serializable object BackupRestoreRoute
@Serializable object AuditLogRoute
@Serializable object EnquiryListRoute

@Serializable object SettingsRoute

@Serializable object StudentRegistrationRoute

@Serializable object StudentLoginRoute

@Serializable object StudentDashboardRoute

@Serializable object WorksListRoute

@Serializable object AddWorkRoute

@Serializable object HomeworkListRoute
@Serializable object AddHomeworkRoute
@Serializable object AssignmentListRoute
@Serializable object AddAssignmentRoute

