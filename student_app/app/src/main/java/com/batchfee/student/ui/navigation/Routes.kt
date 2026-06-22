package com.batchfee.student.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object LoginRoute

@Serializable
object DashboardRoute

@Serializable
object ProfileRoute

@Serializable
object FeesRoute

@Serializable
data class FeeDetailRoute(val feeId: String)

@Serializable
data class ReceiptViewRoute(val receiptId: String)

@Serializable
object AttendanceRoute

@Serializable
object ExamsRoute

@Serializable
data class ExamDetailRoute(val examId: String)

@Serializable
data class MeritListRoute(val examId: String)

@Serializable
object HomeworkRoute

@Serializable
data class HomeworkDetailRoute(val homeworkId: String)

@Serializable
object NoticeRoute

@Serializable
object NoticeDetailRoute
