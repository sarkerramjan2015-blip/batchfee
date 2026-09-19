package com.batchfee.edu.ui.attendance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.AttendanceSyncHelper
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.ReminderTemplateSyncHelper
import com.batchfee.edu.data.models.AbsentMessageEntity
import com.batchfee.edu.data.models.AttendanceEntity
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.ReminderTemplateEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import com.example.domain.BulkMessageController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val ATTENDANCE_ABSENT_TEMPLATE_TYPE = "AttendanceAbsent"
private const val ATTENDANCE_UPDATE_TEMPLATE_TYPE = "AttendanceUpdate"
private val DEFAULT_ATTENDANCE_ABSENT_TEMPLATE = """
    Dear Guardian,

    {studentName} ({studentCode}) was absent from {batchName} on {date}.

    Please let us know the reason at your earliest convenience.

    - {instituteName}
    Contact: {instituteContact}
""".trimIndent()

data class BatchAttendanceSummary(
    val batchId: String = "",
    val batchName: String = "",
    val totalStudents: Int = 0,
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val leaveCount: Int = 0,
    val lateCount: Int = 0,
    val lateMinutesTotal: Int = 0,
    /** Retained only so historic production `holiday` rows remain readable. */
    val holidayCount: Int = 0,
    val expectedStudentDays: Int = 0,
    val attendanceDays: Int = 0
) {
    val markedCount get() = presentCount + absentCount + leaveCount + lateCount + holidayCount
    // Daily dashboards must include students who are not marked yet. Otherwise
    // 2 present students from a class of 3 incorrectly looks like 100% present.
    val chartTotal get() = maxOf(totalStudents, markedCount)
    val pendingCount get() = (chartTotal - markedCount).coerceAtLeast(0)
    private val statusDenominator get() = chartTotal
    private val performanceDenominator get() = presentCount + lateCount + absentCount
    private val attendedCount get() = presentCount + lateCount
    val presentPct get() = if (statusDenominator > 0) presentCount * 100f / statusDenominator else 0f
    val absentPct get() = if (statusDenominator > 0) absentCount * 100f / statusDenominator else 0f
    val leavePct get() = if (statusDenominator > 0) leaveCount * 100f / statusDenominator else 0f
    val latePct get() = if (statusDenominator > 0) lateCount * 100f / statusDenominator else 0f
    val holidayPct get() = if (statusDenominator > 0) holidayCount * 100f / statusDenominator else 0f
    val pendingPct get() = if (statusDenominator > 0) pendingCount * 100f / statusDenominator else 0f
    val coveragePct get() = if (expectedStudentDays > 0) markedCount * 100f / expectedStudentDays else 0f
    val attendanceRatePct get() = if (performanceDenominator > 0) attendedCount * 100f / performanceDenominator else 0f
    val punctualityRatePct get() = if (attendedCount > 0) presentCount * 100f / attendedCount else 0f
    val averageLateMinutes get() = if (lateCount > 0) lateMinutesTotal.toFloat() / lateCount else 0f
    val presentPerformancePct get() = attendanceRatePct
    val absentPerformancePct get() = if (performanceDenominator > 0) absentCount * 100f / performanceDenominator else 0f
}

data class StaffAttendanceSummary(
    val totalStaff: Int = 0,
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val leaveCount: Int = 0,
    val holidayCount: Int = 0,
    val expectedStaffDays: Int = 0,
    val attendanceDays: Int = 0
) {
    val markedCount get() = presentCount + absentCount + leaveCount + holidayCount
    val chartTotal get() = maxOf(totalStaff, markedCount)
    val pendingCount get() = (chartTotal - markedCount).coerceAtLeast(0)
    private val statusDenominator get() = chartTotal
    val presentPct get() = if (statusDenominator > 0) presentCount * 100f / statusDenominator else 0f
    val absentPct get() = if (statusDenominator > 0) absentCount * 100f / statusDenominator else 0f
    val leavePct get() = if (statusDenominator > 0) leaveCount * 100f / statusDenominator else 0f
    val holidayPct get() = if (statusDenominator > 0) holidayCount * 100f / statusDenominator else 0f
    val pendingPct get() = if (statusDenominator > 0) pendingCount * 100f / statusDenominator else 0f
    val coveragePct get() = if (expectedStaffDays > 0) markedCount * 100f / expectedStaffDays else 0f
}

fun startOfDay(ms: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return cal.timeInMillis
}

fun getCurrentMonthRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    cal.add(Calendar.MONTH, 1)
    val end = cal.timeInMillis - 1
    return Pair(start, end)
}

class AttendanceViewModel(private val db: AppDatabase) : ViewModel() {
    private val _batches = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batches = _batches.asStateFlow()

    private val _students = MutableStateFlow<List<StudentEntity>>(emptyList())
    val students = _students.asStateFlow()
    private val _attendanceRecords = MutableStateFlow<Map<String, AttendanceEntity>>(emptyMap())
    val attendanceRecords = _attendanceRecords.asStateFlow()
    private val _sendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val sendingMessageIds = _sendingMessageIds.asStateFlow()

    private fun addSendingId(id: String) { synchronized(_sendingMessageIds) { _sendingMessageIds.value = _sendingMessageIds.value + id } }
    private fun removeSendingId(id: String) { synchronized(_sendingMessageIds) { _sendingMessageIds.value = _sendingMessageIds.value - id } }

    // This keeps track of any attendance notification opened for a student today.
    // The underlying table keeps its legacy name for migration compatibility.
    private val _attendanceMessageMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val attendanceMessageMap = _attendanceMessageMap.asStateFlow()

    private val _absentMessageTemplate = MutableStateFlow(DEFAULT_ATTENDANCE_ABSENT_TEMPLATE)
    val absentMessageTemplate = _absentMessageTemplate.asStateFlow()
    private val _attendanceUpdateTemplate = MutableStateFlow(
        com.example.domain.MessageTemplateStore.defaultFor(ATTENDANCE_UPDATE_TEMPLATE_TYPE).orEmpty()
    )
    private val _instituteName = MutableStateFlow("BatchFee")
    private val _instituteContact = MutableStateFlow("")

    private val _currentBatch = MutableStateFlow<BatchEntity?>(null)
    val currentBatch = _currentBatch.asStateFlow()

    val bulkSender = BulkMessageController(
        scope = viewModelScope,
        db = db,
        instituteId = SessionManager.currentInstituteId.value
    )

    private val _batchSummaries = MutableStateFlow<List<BatchAttendanceSummary>>(emptyList())
    val batchSummaries = _batchSummaries.asStateFlow()
    private val _dailyBatchSummaries = MutableStateFlow<List<BatchAttendanceSummary>>(emptyList())
    val dailyBatchSummaries = _dailyBatchSummaries.asStateFlow()
    private val _selectedBatchSummary = MutableStateFlow<BatchAttendanceSummary?>(null)
    val selectedBatchSummary = _selectedBatchSummary.asStateFlow()
    private val _studentHistory = MutableStateFlow<List<AttendanceEntity>>(emptyList())
    val studentHistory = _studentHistory.asStateFlow()

    private val _staffAttendanceSummary = MutableStateFlow(StaffAttendanceSummary())
    val staffAttendanceSummary = _staffAttendanceSummary.asStateFlow()
    private val _dailyStaffAttendanceSummary = MutableStateFlow(StaffAttendanceSummary())
    val dailyStaffAttendanceSummary = _dailyStaffAttendanceSummary.asStateFlow()

    private val _staffName = MutableStateFlow("")
    val staffName = _staffName.asStateFlow()

    private val _selectedDateMs = MutableStateFlow(startOfDay(System.currentTimeMillis()))
    val selectedDateMs = _selectedDateMs.asStateFlow()

    init {
        loadBatches()
        loadAttendanceMessageTemplate()
    }

    fun selectDate(dateMs: Long) {
        _selectedDateMs.value = startOfDay(dateMs)
    }

    fun isToday(dateMs: Long): Boolean = startOfDay(dateMs) == startOfDay(System.currentTimeMillis())

    private fun isAdmin() = SessionManager.isAdmin()
    private fun isStaff() = SessionManager.isStaff()

    private suspend fun getStaffAssignedBatchIds(): Set<String> {
        if (isAdmin()) return emptySet()
        val instId = SessionManager.currentInstituteId.value ?: return emptySet()
        val userId = SessionManager.currentUserId.value ?: return emptySet()
        db.staffDao().getStaffByIdOnce(userId, instId)?.let { staff ->
            return staff.assignedBatchIds?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }
        val userName = db.userDao().getUserFlow(userId).firstOrNull()?.name ?: return emptySet()
        val allStaff = db.staffDao().getStaffByInstitute(instId).firstOrNull() ?: return emptySet()
        val matched = allStaff.find { it.fullName.equals(userName, ignoreCase = true) }
        return matched?.assignedBatchIds?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    private fun loadBatches() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.batchDao().getBatchesByInstitute(instId).collect { allBatches ->
                if (isAdmin()) _batches.value = allBatches
                else {
                    val assigned = getStaffAssignedBatchIds()
                    _batches.value = allBatches.filter { it.id in assigned }
                }
            }
        }
        viewModelScope.launch {
            SessionManager.currentUserId.value?.let { uid ->
                db.userDao().getUserFlow(uid).collect { user -> _staffName.value = user?.name ?: "" }
            }
        }
    }

    /** Creates the editable attendance template once per institute, then keeps it in sync. */
    fun loadAttendanceMessageTemplate() {
        viewModelScope.launch {
            val instituteId = SessionManager.currentInstituteId.value ?: return@launch
            _instituteName.value = db.instituteDao().getInstitute(instituteId)?.name
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "BatchFee"
            _instituteContact.value = com.example.domain.MessageTemplateStore.loadInstituteContact(db, instituteId)
            val existing = db.reminderTemplateDao()
                .getTemplateByTypeOnce(instituteId, ATTENDANCE_ABSENT_TEMPLATE_TYPE)
            if (existing != null) {
                _absentMessageTemplate.value = existing.messageTemplate
            } else {
                val defaultTemplate = ReminderTemplateEntity(
                    id = "attendance_absent_$instituteId",
                    instituteId = instituteId,
                    title = "Attendance: Student Absent",
                    type = ATTENDANCE_ABSENT_TEMPLATE_TYPE,
                    messageTemplate = DEFAULT_ATTENDANCE_ABSENT_TEMPLATE,
                    isDefault = true,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis()
                )
                db.reminderTemplateDao().insertTemplate(defaultTemplate)
                _absentMessageTemplate.value = defaultTemplate.messageTemplate
                try {
                    ReminderTemplateSyncHelper.upsertTemplate(defaultTemplate)
                } catch (_: Exception) {
                    // The local template is still usable and will be refreshed when cloud sync succeeds.
                }
            }
            _attendanceUpdateTemplate.value = db.reminderTemplateDao()
                .getTemplateByTypeOnce(instituteId, ATTENDANCE_UPDATE_TEMPLATE_TYPE)
                ?.messageTemplate
                ?.takeIf { it.isNotBlank() }
                ?: com.example.domain.MessageTemplateStore.defaultFor(ATTENDANCE_UPDATE_TEMPLATE_TYPE).orEmpty()
        }
    }

    fun saveAttendanceMessageTemplate(template: String, onError: (String) -> Unit = {}) {
        val cleanTemplate = template.trim()
        if (cleanTemplate.isBlank()) {
            onError("Message template cannot be empty.")
            return
        }
        viewModelScope.launch {
            val instituteId = SessionManager.currentInstituteId.value ?: return@launch
            val current = db.reminderTemplateDao()
                .getTemplateByTypeOnce(instituteId, ATTENDANCE_ABSENT_TEMPLATE_TYPE)
            val updated = ReminderTemplateEntity(
                id = current?.id ?: "attendance_absent_$instituteId",
                instituteId = instituteId,
                title = "Attendance: Student Absent",
                type = ATTENDANCE_ABSENT_TEMPLATE_TYPE,
                messageTemplate = cleanTemplate,
                isDefault = true,
                createdAtMs = current?.createdAtMs ?: System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            db.reminderTemplateDao().insertTemplate(updated)
            _absentMessageTemplate.value = cleanTemplate
            try {
                ReminderTemplateSyncHelper.upsertTemplate(updated)
            } catch (_: Exception) {
                // Keep the saved local template; cloud sync can be retried later.
            }
        }
    }

    fun buildAttendanceMessage(
        student: StudentEntity,
        batchName: String,
        dateMs: Long,
        status: String
    ): String {
        val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(startOfDay(dateMs)))
        if (status != "absent") {
            val statusText = when (status) {
                "present" -> "was present"
                "late" -> "arrived late"
                "leave" -> "was on leave"
                "holiday" -> "had a holiday"
                else -> "has an attendance update"
            }
            val values = mapOf(
                "guardianName" to "Guardian",
                "studentName" to student.fullName,
                "studentCode" to student.studentCode,
                "batchName" to batchName,
                "date" to date,
                "instituteName" to _instituteName.value,
                "instituteContact" to _instituteContact.value
            )
            return com.example.domain.MessageTemplateStore.apply(
                _attendanceUpdateTemplate.value,
                values + ("attendanceStatus" to statusText)
            )
        }
        val replacements = mapOf(
            "guardianName" to "Guardian",
            "studentName" to student.fullName,
            "studentCode" to student.studentCode,
            "batchName" to batchName,
            "date" to date,
            "instituteName" to _instituteName.value,
            "instituteContact" to _instituteContact.value
        )
        return replacements.entries.fold(_absentMessageTemplate.value.trim()) { message, (key, value) ->
            message.replace("{$key}", value)
        }
    }

    fun loadBatchStudentsAndAttendance(batchId: String, dateMs: Long) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val startDay = startOfDay(dateMs)
        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
        viewModelScope.launch { db.batchDao().getBatchById(batchId, instId).collect { _currentBatch.value = it } }
        viewModelScope.launch { db.batchStudentDao().getStudentsForBatch(batchId, instId).collect { _students.value = it } }
        viewModelScope.launch {
            db.attendanceDao().getAttendanceForBatchByDate(instId, batchId, startDay).collect { records ->
                _attendanceRecords.value = records.associateBy { it.studentId }
            }
        }
        viewModelScope.launch {
            db.absentMessageDao().getMessagesForBatchDate(instId, batchId, startDay).collect { msgs ->
                _attendanceMessageMap.value = msgs.associate { it.studentId to true }
            }
        }
    }

    fun markAttendance(studentId: String, batchId: String, dateMs: Long, status: String) {
        if (status !in setOf("present", "absent", "leave")) {
            _bulkSaveError.value = "Unsupported attendance status. Refresh and try again."
            return
        }
        bulkMark(batchId, dateMs, listOf(studentId), status)
    }

    fun markLateAttendance(
        studentId: String,
        batchId: String,
        dateMs: Long,
        scheduledStartTimeMs: Long,
        arrivalTimeMs: Long
    ) {
        if (arrivalTimeMs <= scheduledStartTimeMs) {
            _bulkSaveError.value = "Arrival time is not later than the scheduled class time. Mark the student Present instead."
            return
        }
        val lateByMinutes = ((arrivalTimeMs - scheduledStartTimeMs) / 60_000L).toInt()
        if (lateByMinutes <= 0) {
            _bulkSaveError.value = "Arrival time must be at least one minute after the scheduled class time."
            return
        }
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentUserId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        viewModelScope.launch {
            if (!bulkAttendanceMutex.tryLock()) return@launch
            try {
                val now = System.currentTimeMillis()
                val existing = _attendanceRecords.value[studentId]?.takeIf {
                    it.instituteId == instId && it.batchId == batchId && it.attendanceDateMs == startDay
                }
                val record = existing?.copy(
                    status = "late",
                    note = null,
                    arrivalTimeMs = arrivalTimeMs,
                    scheduledStartTimeMs = scheduledStartTimeMs,
                    lateByMinutes = lateByMinutes,
                    markedByUserId = currentUserId,
                    updatedAtMs = now
                ) ?: AttendanceEntity(
                    id = UUID.nameUUIDFromBytes("$instId|$batchId|$studentId|$startDay".toByteArray()).toString(),
                    instituteId = instId,
                    batchId = batchId,
                    studentId = studentId,
                    attendanceDateMs = startDay,
                    status = "late",
                    note = null,
                    arrivalTimeMs = arrivalTimeMs,
                    scheduledStartTimeMs = scheduledStartTimeMs,
                    lateByMinutes = lateByMinutes,
                    markedByUserId = currentUserId,
                    createdAtMs = now,
                    updatedAtMs = now
                )
                com.batchfee.edu.data.firestore.AtomicBulkSync.attendance(db, listOf(record))
                StaffActivityLogger.logCompletedAction(
                    db, "student_attendance_marked", "attendance", "Marked one student late by $lateByMinutes minutes"
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _bulkSaveError.value = error.message ?: "Late attendance was not confirmed. Refresh and retry."
            } finally {
                bulkAttendanceMutex.unlock()
            }
        }
    }

    fun markAll(batchId: String, dateMs: Long, status: String) {
        bulkMark(batchId, dateMs, _students.value.map { it.id }, status)
    }

    private val bulkAttendanceMutex = kotlinx.coroutines.sync.Mutex()

    fun bulkMark(batchId: String, dateMs: Long, studentIds: List<String>, status: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentUserId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        val selectedIds = studentIds.distinct()
        viewModelScope.launch {
            if (!bulkAttendanceMutex.tryLock()) return@launch
            try {
                val now = System.currentTimeMillis()
                val records = selectedIds.map { sid ->
                    val existing = _attendanceRecords.value[sid]?.takeIf {
                        it.instituteId == instId && it.batchId == batchId && it.attendanceDateMs == startDay
                    }
                    existing?.copy(
                        status = status,
                        note = null,
                        arrivalTimeMs = null,
                        scheduledStartTimeMs = null,
                        lateByMinutes = null,
                        markedByUserId = currentUserId,
                        updatedAtMs = now
                    )
                        ?: AttendanceEntity(
                            id = UUID.nameUUIDFromBytes("$instId|$batchId|$sid|$startDay".toByteArray()).toString(),
                            instituteId = instId, batchId = batchId, studentId = sid,
                            attendanceDateMs = startDay, status = status, note = null,
                            markedByUserId = currentUserId, createdAtMs = now, updatedAtMs = now
                        )
                }
                com.batchfee.edu.data.firestore.AtomicBulkSync.attendance(db, records)
                StaffActivityLogger.logCompletedAction(
                    db, "student_attendance_marked", "attendance", "Marked ${records.size} students $status"
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _bulkSaveError.value = error.message ?: "Attendance was not confirmed. Refresh and retry."
            } finally {
                bulkAttendanceMutex.unlock()
            }
        }
    }

    private val _bulkSaveError = MutableStateFlow<String?>(null)
    val bulkSaveError = _bulkSaveError.asStateFlow()
    fun clearBulkSaveError() { _bulkSaveError.value = null }

    fun undoAttendance(studentId: String, dateMs: Long, batchId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            try {
                val day = startOfDay(dateMs)
                AttendanceSyncHelper.deleteAttendance(instId, studentId, batchId, day)
                db.attendanceDao().deleteAttendance(instId, studentId, batchId, day)
                StaffActivityLogger.logCompletedAction(
                    db, "student_attendance_removed", "attendance", "Removed one student attendance mark"
                )
            } catch (_: Exception) {
                // Cloud-first removal: a failed delete must never crash the screen.
            }
        }
    }

    /** Fire-and-forget tracking for carrier SMS hand-offs; never blocks the send. */
    fun recordCarrierSms(recipient: String, purpose: String, messageBody: String = "") {
        viewModelScope.launch {
            runCatching {
                com.batchfee.edu.data.firestore.SmsWalletSyncHelper.recordCarrierSmsBatch(
                    listOf(
                        com.batchfee.edu.data.firestore.SmsOutboundRecord(
                            recipient = recipient,
                            purpose = purpose,
                            messageBody = messageBody
                        )
                    )
                )
            }
        }
    }

    fun sendAttendanceMessage(
        context: Context,
        student: StudentEntity,
        batchId: String,
        dateMs: Long,
        channel: String,
        messageText: String,
        onSent: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        val recipientPhone = student.guardianPhone
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: student.phone?.trim()?.takeIf { it.isNotBlank() }
        if (recipientPhone == null) {
            onError("No guardian or student phone number is saved.")
            return
        }
        val recipientDigits = recipientPhone.replace(Regex("[^0-9]"), "")
        if (recipientDigits.isBlank()) {
            onError("The saved phone number is not valid.")
            return
        }
        val cleanMessage = messageText.trim()
        if (cleanMessage.isBlank()) {
            onError("Message cannot be empty.")
            return
        }
        addSendingId(student.id)

        viewModelScope.launch {
            try {
                when (channel) {
                    "whatsapp" -> {
                        val encoded = java.net.URLEncoder.encode(cleanMessage, "UTF-8")
                        val url = "https://wa.me/$recipientDigits?text=$encoded"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    else -> {
                        // Single SMS now goes through the shared automatic/manual
                        // chooser (SingleSmsDeliveryDialog), never a direct composer.
                        removeSendingId(student.id)
                        onError("Unsupported message channel.")
                        return@launch
                    }
                }

                // Android lets us open the external compose screen but not confirm delivery.
                val message = AbsentMessageEntity(
                    id = UUID.randomUUID().toString(), instituteId = instId,
                    batchId = batchId, studentId = student.id, attendanceDateMs = startDay,
                    messageType = channel, messageText = cleanMessage, sentByUserId = userId,
                    status = "opened", createdAtMs = System.currentTimeMillis()
                )
                db.absentMessageDao().insertMessage(message)
                try {
                    AttendanceSyncHelper.upsertAbsentMessage(message)
                } catch (_: Exception) {
                    // Message history remains safely available on this device while offline.
                }
                removeSendingId(student.id)
                onSent()
            } catch (e: Exception) {
                removeSendingId(student.id)
                onError("Could not open $channel. Please check that the app is installed.")
            }
        }
    }

    private fun buildBatchSummary(
        batchId: String,
        batchName: String,
        totalStudents: Int,
        records: List<AttendanceEntity>,
        expectedStudentDays: Int
    ): BatchAttendanceSummary {
        return BatchAttendanceSummary(
            batchId = batchId,
            batchName = batchName,
            totalStudents = totalStudents,
            presentCount = records.count { it.status == "present" },
            absentCount = records.count { it.status == "absent" },
            leaveCount = records.count { it.status == "leave" },
            lateCount = records.count { it.status == "late" },
            lateMinutesTotal = records.filter { it.status == "late" }.sumOf { it.lateByMinutes ?: 0 },
            holidayCount = records.count { it.status == "holiday" },
            expectedStudentDays = expectedStudentDays,
            attendanceDays = records.map { it.attendanceDateMs }.distinct().size
        )
    }

    /**
     * Attendance rows are historic records and may still exist after an owner
     * closes a student. Dashboard totals, however, describe today's active
     * class list. Keep historic rows in Room/cloud but exclude them from the
     * current operational summary.
     */
    private fun recordsForActiveStudents(
        records: List<AttendanceEntity>,
        students: List<StudentEntity>
    ): List<AttendanceEntity> {
        if (records.isEmpty() || students.isEmpty()) return emptyList()
        val activeStudentIds = students.asSequence().map { it.id }.toHashSet()
        return records.filter { it.studentId in activeStudentIds }
    }

    private fun buildStaffSummary(
        totalStaff: Int,
        records: List<com.batchfee.edu.data.models.StaffAttendanceEntity>,
        expectedStaffDays: Int
    ): StaffAttendanceSummary {
        return StaffAttendanceSummary(
            totalStaff = totalStaff,
            presentCount = records.count { it.status == "present" },
            absentCount = records.count { it.status == "absent" },
            leaveCount = records.count { it.status == "leave" },
            holidayCount = records.count { it.status == "holiday" },
            expectedStaffDays = expectedStaffDays,
            attendanceDays = records.map { it.attendanceDateMs }.distinct().size
        )
    }

    fun loadDailySummaries(dateMs: Long = System.currentTimeMillis()) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val selectedDay = startOfDay(dateMs)
        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
        viewModelScope.launch {
            db.batchDao().getBatchesByInstitute(instId).collect { allBatches ->
                val assignedIds = if (isAdmin()) allBatches.map { it.id }.toSet() else getStaffAssignedBatchIds()
                val summaries = mutableListOf<BatchAttendanceSummary>()
                allBatches.filter { it.id in assignedIds }.forEach { batch ->
                    val students = db.batchStudentDao().getStudentsForBatch(batch.id, instId).firstOrNull().orEmpty()
                    val records = db.attendanceDao().getAttendanceForBatchByDate(instId, batch.id, selectedDay).firstOrNull().orEmpty()
                    summaries.add(
                        buildBatchSummary(
                            batchId = batch.id,
                            batchName = batch.name,
                            totalStudents = students.size,
                            records = recordsForActiveStudents(records, students),
                            expectedStudentDays = students.size
                        )
                    )
                }
                _dailyBatchSummaries.value = summaries
            }
        }
        viewModelScope.launch {
            db.staffAttendanceDao().getAttendanceByDate(instId, selectedDay, selectedDay + 24L * 60L * 60L * 1000L).collect { records ->
                db.staffDao().countStaff(instId).collect { totalCount ->
                    _dailyStaffAttendanceSummary.value = buildStaffSummary(
                        totalStaff = totalCount,
                        records = records,
                        expectedStaffDays = totalCount
                    )
                }
            }
        }
    }

    fun loadMonthlySummaries() {
        val instId = SessionManager.currentInstituteId.value ?: return
        val (start, end) = getCurrentMonthRange()
        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
        viewModelScope.launch {
            db.batchDao().getBatchesByInstitute(instId).collect { allBatches ->
                val assignedIds = if (isAdmin()) allBatches.map { it.id }.toSet() else getStaffAssignedBatchIds()
                val monthRecords = db.attendanceDao().getAttendanceByInstituteDateRange(instId, start, end).firstOrNull().orEmpty()
                val instituteActiveDays = monthRecords.map { it.attendanceDateMs }.distinct().size
                val summaries = mutableListOf<BatchAttendanceSummary>()
                allBatches.filter { it.id in assignedIds }.forEach { batch ->
                    val students = db.batchStudentDao().getStudentsForBatch(batch.id, instId).firstOrNull().orEmpty()
                    val batchRecords = recordsForActiveStudents(
                        monthRecords.filter { it.batchId == batch.id },
                        students
                    )
                    summaries.add(
                        buildBatchSummary(
                            batchId = batch.id,
                            batchName = batch.name,
                            totalStudents = students.size,
                            records = batchRecords,
                            expectedStudentDays = students.size * instituteActiveDays
                        )
                    )
                }
                _batchSummaries.value = summaries
            }
        }
    }

    fun loadBatchMonthSummary(batchId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val (start, end) = getCurrentMonthRange()
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            val batch = db.batchDao().getBatchById(batchId, instId).firstOrNull() ?: return@launch
            val students = db.batchStudentDao().getStudentsForBatch(batchId, instId).firstOrNull() ?: return@launch
            val activeDayCount = db.attendanceDao().getAttendanceByInstituteDateRange(instId, start, end)
                .firstOrNull()
                .orEmpty()
                .map { it.attendanceDateMs }
                .distinct()
                .size
            db.attendanceDao().getAttendanceForBatchByDateRange(instId, batchId, start, end).collect { records ->
                _selectedBatchSummary.value = buildBatchSummary(
                    batchId = batchId,
                    batchName = batch.name,
                    totalStudents = students.size,
                    records = recordsForActiveStudents(records, students),
                    expectedStudentDays = students.size * activeDayCount
                )
            }
        }
    }

    fun loadStudentHistory(studentId: String, batchId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.attendanceDao().getAttendanceForStudent(instId, studentId, batchId).collect { _studentHistory.value = it }
        }
    }
}

class AttendanceViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AttendanceViewModel::class.java)) return AttendanceViewModel(db) as T
        throw IllegalArgumentException()
    }
}

