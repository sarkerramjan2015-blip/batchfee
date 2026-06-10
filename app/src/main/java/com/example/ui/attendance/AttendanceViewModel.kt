package com.example.ui.attendance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.AbsentMessageEntity
import com.example.data.models.AttendanceEntity
import com.example.data.models.BatchEntity
import com.example.data.models.StudentEntity
import com.example.domain.appendInstituteSignature
import com.example.domain.loadInstituteSignature
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

data class BatchAttendanceSummary(
    val batchId: String = "",
    val batchName: String = "",
    val totalStudents: Int = 0,
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val leaveCount: Int = 0,
    val holidayCount: Int = 0,
    val expectedStudentDays: Int = 0,
    val attendanceDays: Int = 0
) {
    val markedCount get() = presentCount + absentCount + leaveCount + holidayCount
    val chartTotal get() = markedCount.takeIf { it > 0 } ?: totalStudents
    private val statusDenominator get() = markedCount.takeIf { it > 0 } ?: totalStudents
    private val performanceDenominator get() = (presentCount + absentCount).takeIf { it > 0 } ?: markedCount
    val presentPct get() = if (statusDenominator > 0) presentCount * 100f / statusDenominator else 0f
    val absentPct get() = if (statusDenominator > 0) absentCount * 100f / statusDenominator else 0f
    val leavePct get() = if (statusDenominator > 0) leaveCount * 100f / statusDenominator else 0f
    val holidayPct get() = if (statusDenominator > 0) holidayCount * 100f / statusDenominator else 0f
    val coveragePct get() = if (expectedStudentDays > 0) markedCount * 100f / expectedStudentDays else 0f
    val presentPerformancePct get() = if (performanceDenominator > 0) presentCount * 100f / performanceDenominator else 0f
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
    val chartTotal get() = markedCount.takeIf { it > 0 } ?: totalStaff
    private val statusDenominator get() = markedCount.takeIf { it > 0 } ?: totalStaff
    val presentPct get() = if (statusDenominator > 0) presentCount * 100f / statusDenominator else 0f
    val absentPct get() = if (statusDenominator > 0) absentCount * 100f / statusDenominator else 0f
    val leavePct get() = if (statusDenominator > 0) leaveCount * 100f / statusDenominator else 0f
    val holidayPct get() = if (statusDenominator > 0) holidayCount * 100f / statusDenominator else 0f
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

    private val _absentMessageMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val absentMessageMap = _absentMessageMap.asStateFlow()

    private val _currentBatch = MutableStateFlow<BatchEntity?>(null)
    val currentBatch = _currentBatch.asStateFlow()

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

    init { loadBatches() }

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

    fun loadBatchStudentsAndAttendance(batchId: String, dateMs: Long) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val startDay = startOfDay(dateMs)
        viewModelScope.launch { db.batchDao().getBatchById(batchId, instId).collect { _currentBatch.value = it } }
        viewModelScope.launch { db.batchStudentDao().getStudentsForBatch(batchId, instId).collect { _students.value = it } }
        viewModelScope.launch {
            db.attendanceDao().getAttendanceForBatchByDate(instId, batchId, startDay).collect { records ->
                _attendanceRecords.value = records.associateBy { it.studentId }
            }
        }
        viewModelScope.launch {
            db.absentMessageDao().getMessagesForBatchDate(instId, batchId, startDay).collect { msgs ->
                _absentMessageMap.value = msgs.associate { it.studentId to true }
            }
        }
    }

    fun markAttendance(studentId: String, batchId: String, dateMs: Long, status: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentUserId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        viewModelScope.launch {
            val existing = _attendanceRecords.value[studentId]
            val record = existing?.copy(status = status, updatedAtMs = System.currentTimeMillis())
                ?: AttendanceEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId, batchId = batchId, studentId = studentId,
                    attendanceDateMs = startDay, status = status, note = null,
                    markedByUserId = currentUserId,
                    createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis()
                )
            db.attendanceDao().insertOrUpdateAttendance(record)
        }
    }

    fun markAll(batchId: String, dateMs: Long, status: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentUserId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        viewModelScope.launch {
            _students.value.forEach { student ->
                val existing = _attendanceRecords.value[student.id]
                val record = existing?.copy(status = status, updatedAtMs = System.currentTimeMillis())
                    ?: AttendanceEntity(
                        id = UUID.randomUUID().toString(),
                        instituteId = instId, batchId = batchId, studentId = student.id,
                        attendanceDateMs = startDay, status = status, note = null,
                        markedByUserId = currentUserId,
                        createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis()
                    )
                db.attendanceDao().insertOrUpdateAttendance(record)
            }
        }
    }

    fun bulkMark(batchId: String, dateMs: Long, studentIds: List<String>, status: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentUserId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        viewModelScope.launch {
            studentIds.forEach { sid ->
                val existing = _attendanceRecords.value[sid]
                val record = existing?.copy(status = status, updatedAtMs = System.currentTimeMillis())
                    ?: AttendanceEntity(
                        id = UUID.randomUUID().toString(),
                        instituteId = instId, batchId = batchId, studentId = sid,
                        attendanceDateMs = startDay, status = status, note = null,
                        markedByUserId = currentUserId,
                        createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis()
                    )
                db.attendanceDao().insertOrUpdateAttendance(record)
            }
        }
    }

    fun undoAttendance(studentId: String, dateMs: Long, batchId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch { db.attendanceDao().deleteAttendance(instId, studentId, batchId, startOfDay(dateMs)) }
    }

    fun sendAbsentMessage(
        context: Context, studentId: String, batchId: String, dateMs: Long,
        channel: String, studentName: String, batchName: String,
        phone: String?, staffLabel: String,
        onSent: () -> Unit, onError: () -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val startDay = startOfDay(dateMs)
        val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(startDay))
        addSendingId(studentId)

        viewModelScope.launch {
            val instituteSignature = loadInstituteSignature(db, instId)
            val msg = buildString {
                append("Dear Parent, $studentName was absent on $dateLabel in $batchName.")
                append(" Please note your attendance.")
                if (staffLabel.isNotBlank()) append(" \u2013 $staffLabel")
            }.let { appendInstituteSignature(it, instituteSignature) }
            val alreadySent = db.absentMessageDao().hasMessageForStudentDate(instId, studentId, startDay)
            if (alreadySent > 0) {
                removeSendingId(studentId)
                onSent(); return@launch
            }
            db.absentMessageDao().insertMessage(
                AbsentMessageEntity(
                    id = UUID.randomUUID().toString(), instituteId = instId,
                    batchId = batchId, studentId = studentId, attendanceDateMs = startDay,
                    messageType = channel, messageText = msg, sentByUserId = userId,
                    status = "sent", createdAtMs = System.currentTimeMillis()
                )
            )
            try {
                val encoded = URLEncoder.encode(msg, "UTF-8")
                when (channel) {
                    "whatsapp" -> {
                        val number = phone?.replace("+", "")?.replace(" ", "")
                        val url = if (!number.isNullOrBlank()) "https://wa.me/$number?text=$encoded" else "https://wa.me/?text=$encoded"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    "sms" -> context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone ?: ""}")).apply { putExtra("sms_body", msg) }
                    )
                }
                removeSendingId(studentId)
                onSent()
            } catch (e: Exception) {
                removeSendingId(studentId)
                onError()
            }
        }
    }

    fun sendAllAbsentMessages(context: Context, batchId: String, dateMs: Long, channel: String, onComplete: () -> Unit) {
        val absentRecords = _attendanceRecords.value.values.filter { it.status == "absent" }
        if (absentRecords.isEmpty()) { onComplete(); return }
        val total = absentRecords.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        absentRecords.forEach { record ->
            val student = _students.value.find { it.id == record.studentId } ?: run { completed.incrementAndGet(); return@forEach }
            val batchName = _currentBatch.value?.name ?: ""
            sendAbsentMessage(context, record.studentId, batchId, dateMs, channel,
                student.fullName, batchName, student.phone, _staffName.value,
                onSent = { if (completed.incrementAndGet() >= total) onComplete() },
                onError = { if (completed.incrementAndGet() >= total) onComplete() }
            )
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
            holidayCount = records.count { it.status == "holiday" },
            expectedStudentDays = expectedStudentDays,
            attendanceDays = records.map { it.attendanceDateMs }.distinct().size
        )
    }

    private fun buildStaffSummary(
        totalStaff: Int,
        records: List<com.example.data.models.StaffAttendanceEntity>,
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
                            records = records,
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
        viewModelScope.launch {
            db.batchDao().getBatchesByInstitute(instId).collect { allBatches ->
                val assignedIds = if (isAdmin()) allBatches.map { it.id }.toSet() else getStaffAssignedBatchIds()
                val monthRecords = db.attendanceDao().getAttendanceByInstituteDateRange(instId, start, end).firstOrNull().orEmpty()
                val instituteActiveDays = monthRecords.map { it.attendanceDateMs }.distinct().size
                val summaries = mutableListOf<BatchAttendanceSummary>()
                allBatches.filter { it.id in assignedIds }.forEach { batch ->
                    val students = db.batchStudentDao().getStudentsForBatch(batch.id, instId).firstOrNull().orEmpty()
                    val batchRecords = monthRecords.filter { it.batchId == batch.id }
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
                    records = records,
                    expectedStudentDays = students.size * activeDayCount
                )
            }
        }
    }

    fun loadStudentHistory(studentId: String, batchId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
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
