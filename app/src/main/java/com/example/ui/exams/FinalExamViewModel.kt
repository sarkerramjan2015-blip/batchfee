package com.batchfee.edu.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.FinalExamSyncHelper
import com.batchfee.edu.data.firestore.FinalResultSyncRow
import com.batchfee.edu.data.models.FinalExamEntity
import com.batchfee.edu.data.models.FinalExamMarksEntity
import com.batchfee.edu.data.models.FinalExamSubjectEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class FinalSubjectView(
    val subject: FinalExamSubjectEntity,
    val hasMcq: Boolean,
    val hasCq: Boolean,
    val hasPractical: Boolean,
    val totalOnly: Boolean
)

data class FinalResultRow(
    val student: StudentEntity,
    val subjectMarks: Map<String, FinalExamMarksEntity>,
    val totalMarks: Double,
    val fullMarks: Double,
    val percentage: Double,
    val gpa: Double,
    val grade: String,
    val passed: Boolean,
    val meritPosition: Int
)

/** Per-subject class performance, worst-first. */
data class SubjectStat(
    val subjectId: String,
    val name: String,
    val fullMarks: Double,
    val passMarks: Double,
    val averagePct: Double,
    val passRatePct: Double,
    val highest: Double,
    val lowest: Double,
    val failCount: Int,
    val studentCount: Int
)

data class WeakSubjectLine(
    val subjectName: String,
    val obtained: Double,
    val passMarks: Double
)

data class WeakStudentRow(
    val student: StudentEntity,
    val failedSubjects: List<WeakSubjectLine>,
    val belowAverage: Boolean,
    val overallPct: Double,
    val meritPosition: Int
)

data class VersionSubject(val subjectId: String, val name: String, val fullMarks: Double, val passMarks: Double)

data class VersionResultRow(
    val studentId: String,
    val studentName: String,
    val meritPosition: Int,
    val totalMarks: Double,
    val fullMarks: Double,
    val percentage: Double,
    val gpa: Double,
    val grade: String,
    val passed: Boolean,
    val subjectMarks: Map<String, Double> // subjectId -> totalMarks
)

data class FinalResultVersion(
    val version: Int,
    val createdAtMs: Long,
    val changedByName: String?,
    val description: String?,
    val subjects: List<VersionSubject>,
    val results: List<VersionResultRow>
)

class FinalExamViewModel(private val db: AppDatabase) : ViewModel() {

    private val _exams = MutableStateFlow<List<FinalExamEntity>>(emptyList())
    val exams: StateFlow<List<FinalExamEntity>> = _exams.asStateFlow()

    private val _selectedExam = MutableStateFlow<FinalExamEntity?>(null)
    val selectedExam: StateFlow<FinalExamEntity?> = _selectedExam.asStateFlow()

    private val _subjects = MutableStateFlow<List<FinalSubjectView>>(emptyList())
    val subjects: StateFlow<List<FinalSubjectView>> = _subjects.asStateFlow()

    private val _marks = MutableStateFlow<List<FinalExamMarksEntity>>(emptyList())
    val marks: StateFlow<List<FinalExamMarksEntity>> = _marks.asStateFlow()

    private val _results = MutableStateFlow<List<FinalResultRow>>(emptyList())
    val results: StateFlow<List<FinalResultRow>> = _results.asStateFlow()

    private val _subjectStats = MutableStateFlow<List<SubjectStat>>(emptyList())
    val subjectStats: StateFlow<List<SubjectStat>> = _subjectStats.asStateFlow()

    private val _weakStudents = MutableStateFlow<List<WeakStudentRow>>(emptyList())
    val weakStudents: StateFlow<List<WeakStudentRow>> = _weakStudents.asStateFlow()

    private val _versions = MutableStateFlow<List<FinalResultVersion>>(emptyList())
    val versions: StateFlow<List<FinalResultVersion>> = _versions.asStateFlow()

    private var versionsRegistration: ListenerRegistration? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isOwner: Boolean get() = SessionManager.isAdmin()
    val currentUserId: String? get() = SessionManager.currentUserId.value

    fun loadExams() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.finalExamDao().getFinalExams(instId).collect { _exams.value = it }
        }
    }

    fun loadExam(examId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            // Each flow must collect in its own coroutine — collecting
            // sequentially would block forever on the first collect.
            launch {
                db.finalExamDao().getFinalExam(examId, instId).collect { exam ->
                    _selectedExam.value = exam
                    loadVersions()
                }
            }
            launch {
                db.finalExamDao().getSubjects(examId).collect { subjects ->
                    _subjects.value = subjects.map { it.toView() }
                    // Marks may have emitted before subjects loaded — re-run the
                    // computation so the results screen never shows a stale empty list.
                    if (_marks.value.isNotEmpty()) recomputeResults(_marks.value)
                }
            }
            launch {
                db.finalExamDao().getMarks(examId).collect { marks ->
                    _marks.value = marks
                    recomputeResults(marks)
                }
            }
            _isLoading.value = false
        }
    }

    /** Listens to the immutable version snapshots of this exam's published results. */
    fun loadVersions() {
        if (versionsRegistration != null) return
        val exam = _selectedExam.value ?: return
        versionsRegistration = FirebaseFirestore.getInstance()
            .collection("institutes").document(exam.instituteId)
            .collection("final_result_versions")
            .whereEqualTo("examId", exam.id)
            .addSnapshotListener { snap, _ ->
                _versions.value = snap?.documents?.mapNotNull { doc ->
                    val version = (doc.get("version") as? Number)?.toInt() ?: return@mapNotNull null
                    FinalResultVersion(
                        version = version,
                        createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: 0L,
                        changedByName = doc.getString("changedByName"),
                        description = doc.getString("description"),
                        subjects = (doc.get("subjects") as? List<*>)?.mapNotNull { raw ->
                            val map = raw as? Map<*, *> ?: return@mapNotNull null
                            VersionSubject(
                                subjectId = map["subjectId"] as? String ?: "",
                                name = map["name"] as? String ?: "Subject",
                                fullMarks = (map["fullMarks"] as? Number)?.toDouble() ?: 0.0,
                                passMarks = (map["passMarks"] as? Number)?.toDouble() ?: 0.0
                            )
                        }.orEmpty(),
                        results = (doc.get("results") as? List<*>)?.mapNotNull { raw ->
                            val map = raw as? Map<*, *> ?: return@mapNotNull null
                            val subjectMarks = (map["subjectMarks"] as? Map<*, *>)?.mapNotNull { (key, value) ->
                                val inner = value as? Map<*, *> ?: return@mapNotNull null
                                (inner["totalMarks"] as? Number)?.toDouble()?.let { key.toString() to it }
                            }?.toMap().orEmpty()
                            VersionResultRow(
                                studentId = map["studentId"] as? String ?: return@mapNotNull null,
                                studentName = map["studentName"] as? String ?: "Student",
                                meritPosition = (map["meritPosition"] as? Number)?.toInt() ?: 0,
                                totalMarks = (map["totalMarks"] as? Number)?.toDouble() ?: 0.0,
                                fullMarks = (map["fullMarks"] as? Number)?.toDouble() ?: 0.0,
                                percentage = (map["percentage"] as? Number)?.toDouble() ?: 0.0,
                                gpa = (map["gpa"] as? Number)?.toDouble() ?: 0.0,
                                grade = map["grade"] as? String ?: "",
                                passed = map["passed"] as? Boolean ?: false,
                                subjectMarks = subjectMarks
                            )
                        }.orEmpty()
                    )
                }.orEmpty().sortedByDescending { it.version }
            }
    }

    override fun onCleared() {
        versionsRegistration?.remove()
        versionsRegistration = null
        super.onCleared()
    }

    fun createExam(
        examName: String,
        batchId: String,
        subjectConfigs: List<SubjectConfig>,
        examFeeAmount: Double = 0.0,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run { onError("No active institute session."); return }
        if (examName.isBlank()) { onError("Final exam name is required."); return }
        if (subjectConfigs.isEmpty()) { onError("Select at least one subject."); return }
        subjectConfigs.forEach { config ->
            if (config.subjectName.isBlank()) { onError("Subject name is required."); return }
            val computedTotal = if (config.components == listOf("total_only")) {
                config.fullMarks
            } else {
                config.mcqFullMarks + config.cqFullMarks + config.practicalFullMarks
            }
            if (computedTotal <= 0) { onError("${config.subjectName}: component marks must sum to a total greater than zero."); return }
            if (config.passMarks < 0 || config.passMarks > computedTotal) {
                onError("${config.subjectName}: pass mark must be within total marks."); return
            }
            if (config.components.isEmpty()) { onError("${config.subjectName}: select at least one marks component."); return }
        }
        val examId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val exam = FinalExamEntity(
            id = examId,
            instituteId = instId,
            batchId = batchId,
            examName = examName.trim(),
            status = "draft",
            createdAtMs = now,
            updatedAtMs = now,
            examFeeAmount = examFeeAmount
        )
        viewModelScope.launch {
            try {
                val subjects = subjectConfigs.mapIndexed { index, config ->
                    val staffName = config.assignedStaffId?.let { staffId ->
                        runCatching {
                            db.staffDao().getStaffByIdOnce(staffId, instId)?.fullName
                        }.getOrNull()
                    }
                    FinalExamSubjectEntity(
                        id = UUID.randomUUID().toString(),
                        finalExamId = examId,
                        instituteId = instId,
                        subjectName = config.subjectName.trim(),
                        fullMarks = config.fullMarks,
                        passMarks = config.passMarks,
                        components = config.components.joinToString(","),
                        sortOrder = index,
                        mcqFullMarks = config.mcqFullMarks,
                        cqFullMarks = config.cqFullMarks,
                        practicalFullMarks = config.practicalFullMarks,
                        mcqPassMarks = config.mcqPassMarks,
                        cqPassMarks = config.cqPassMarks,
                        practicalPassMarks = config.practicalPassMarks,
                        assignedStaffId = config.assignedStaffId,
                        assignedStaffName = staffName
                    )
                }
                db.finalExamDao().upsertFinalExam(exam)
                subjects.forEach { db.finalExamDao().upsertSubject(it) }
                StaffActivityLogger.logCompletedAction(db, "final_exam_created", "final_exams", "Created final exam $examName")
                onSuccess(examId)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to create final exam.")
            }
        }
    }

    fun assignSubjectStaff(subject: FinalExamSubjectEntity, staffId: String?, staffName: String?) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.finalExamDao().upsertSubject(
                subject.copy(assignedStaffId = staffId, assignedStaffName = staffName)
            )
        }
    }

    /** Owner toggles whether staff may input marks for a subject right now. */
    fun toggleMarksEntry(subject: FinalExamSubjectEntity, enabled: Boolean) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            db.finalExamDao().upsertSubject(subject.copy(marksEntryEnabled = enabled))
        }
    }

    /** Owner can delete a draft exam (no marks entered yet). */
    fun deleteDraftExam(examId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can delete exams."); return }
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            try {
                val exam = db.finalExamDao().getFinalExamOnce(examId, instId)
                if (exam == null) { onError("Exam not found."); return@launch }
                if (exam.status != "draft") { onError("Only draft exams can be deleted."); return@launch }
                val marksCount = db.finalExamDao().countMarks(examId)
                if (marksCount > 0) { onError("Marks already entered — cannot delete."); return@launch }
                db.finalExamDao().archiveFinalExam(examId, instId, System.currentTimeMillis(), System.currentTimeMillis())
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete exam.")
            }
        }
    }

    /** Owner renames a draft exam. */
    fun renameDraftExam(examId: String, newName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can edit exams."); return }
        val instId = SessionManager.currentInstituteId.value ?: return
        if (newName.isBlank()) { onError("Exam name is required."); return }
        viewModelScope.launch {
            try {
                val exam = db.finalExamDao().getFinalExamOnce(examId, instId)
                if (exam == null) { onError("Exam not found."); return@launch }
                if (exam.status != "draft") { onError("Only draft exams can be edited."); return@launch }
                db.finalExamDao().upsertFinalExam(exam.copy(examName = newName.trim(), updatedAtMs = System.currentTimeMillis()))
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to rename exam.")
            }
        }
    }

    /**
     * Creates a per-student exam fee through the trusted ledger service — same
     * flow as the existing exam fee system (feeType "exam_fee", one fee per
     * active student of the batch, deterministic business key).
     */
    fun createExamFees(exam: FinalExamEntity, onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can create exam fees."); return }
        if (exam.examFeeAmount <= 0) { onError("Exam fee amount must be greater than zero."); return }
        viewModelScope.launch {
            try {
                val repo = com.batchfee.edu.data.repository.FeeCollectionRepository(db)
                val students = db.batchStudentDao().getStudentsForBatchOnce(exam.batchId, exam.instituteId)
                    .filter { it.status == "active" }
                if (students.isEmpty()) { onError("No active students in this batch."); return@launch }
                var created = 0
                students.forEach { student ->
                    try {
                        repo.createFee(
                            instituteId = exam.instituteId,
                            studentId = student.id,
                            batchId = exam.batchId,
                            feePeriod = exam.examName,
                            feeType = "exam_fee",
                            sourceId = "final_exam:${exam.id}",
                            dueDateMs = System.currentTimeMillis(),
                            baseAmount = exam.examFeeAmount,
                            discountAmount = 0.0,
                            lateFeeAmount = 0.0,
                            note = "Final exam fee"
                        )
                        created += 1
                    } catch (_: Exception) {
                        // Duplicate fee (already created) is fine — deterministic key makes retry safe.
                    }
                }
                onSuccess(created)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to create exam fees.")
            }
        }
    }

    /** Saves marks. Owner edits are always persisted (even on approved marks); staff edits are blocked on approved marks. */
    fun saveMarks(
        subject: FinalExamSubjectEntity,
        marksList: List<FinalExamMarksEntity>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run { onError("No active institute session."); return }
        val userId = SessionManager.currentUserId.value ?: run { onError("No active user session."); return }
        viewModelScope.launch {
            try {
                marksList.forEach { marks ->
                    if (marks.status != "approved" || SessionManager.isAdmin()) {
                        db.finalExamDao().upsertMarks(marks)
                    }
                }
                StaffActivityLogger.logCompletedAction(
                    db, "final_exam_marks_saved", "final_exams",
                    "Saved marks for ${subject.subjectName}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save marks.")
            }
        }
    }

    /** Teacher/staff submits their subject marks. No edits after this. */
    fun submitSubject(subject: FinalExamSubjectEntity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                db.finalExamDao().updateMarksStatusForSubject(subject.id, "submitted", now)
                db.finalExamDao().getMarksOnce(subject.finalExamId).forEach { marks ->
                    if (marks.subjectId == subject.id && marks.status == "submitted" && marks.submittedAtMs == null) {
                        db.finalExamDao().upsertMarks(marks.copy(submittedAtMs = now))
                    }
                }
                StaffActivityLogger.logCompletedAction(
                    db, "final_exam_marks_submitted", "final_exams",
                    "Submitted ${subject.subjectName} marks"
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to submit marks.")
            }
        }
    }

    /** Owner approves one subject's marks — permanently locked for staff. */
    fun approveSubject(
        subject: FinalExamSubjectEntity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can approve marks."); return }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val allMarks = db.finalExamDao().getMarksOnce(subject.finalExamId)
                allMarks.filter { it.subjectId == subject.id }.forEach { marks ->
                    val next = marks.copy(status = "approved", reviewedAtMs = marks.reviewedAtMs ?: now, approvedAtMs = now, updatedAtMs = now)
                    db.finalExamDao().upsertMarks(next)
                }
                db.finalExamDao().updateMarksStatusForSubject(subject.id, "approved", now)
                try {
                    db.auditLogDao().insertAuditLog(
                        com.batchfee.edu.data.models.AuditLogEntity(
                            id = UUID.randomUUID().toString(),
                            instituteId = subject.instituteId,
                            userId = SessionManager.currentUserId.value,
                            action = "final_exam_subject_approved",
                            module = "final_exams",
                            description = "Approved ${subject.subjectName} marks",
                            oldValue = null,
                            newValue = null,
                            createdAtMs = now
                        )
                    )
                } catch (_: Exception) { }
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to approve marks.")
            }
        }
    }

    /** Owner edit after approval — recorded in audit log. */
    fun ownerEditMarks(
        subject: FinalExamSubjectEntity,
        studentId: String,
        mcq: Double,
        cq: Double,
        practical: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can edit approved marks."); return }
        viewModelScope.launch {
            try {
                val existing = db.finalExamDao().getMarksForStudentOnce(subject.id, studentId)
                val now = System.currentTimeMillis()
                val total = mcq + cq + practical
                val updated = FinalExamMarksEntity(
                    id = existing?.id ?: "${subject.id}_$studentId",
                    instituteId = subject.instituteId,
                    finalExamId = subject.finalExamId,
                    subjectId = subject.id,
                    studentId = studentId,
                    mcqMarks = mcq,
                    cqMarks = cq,
                    practicalMarks = practical,
                    totalMarks = total,
                    status = "approved",
                    enteredByUserId = existing?.enteredByUserId ?: SessionManager.currentUserId.value.orEmpty(),
                    enteredByName = existing?.enteredByName ?: "Institute Owner",
                    submittedAtMs = existing?.submittedAtMs ?: now,
                    reviewedAtMs = existing?.reviewedAtMs ?: now,
                    approvedAtMs = existing?.approvedAtMs ?: now,
                    updatedAtMs = now
                )
                db.finalExamDao().upsertMarks(updated)
                try {
                    db.auditLogDao().insertAuditLog(
                        com.batchfee.edu.data.models.AuditLogEntity(
                            id = UUID.randomUUID().toString(),
                            instituteId = subject.instituteId,
                            userId = SessionManager.currentUserId.value,
                            action = "final_exam_marks_owner_edit",
                            module = "final_exams",
                            description = "Owner edited ${subject.subjectName} marks for student $studentId",
                            oldValue = existing?.totalMarks?.toString(),
                            newValue = total.toString(),
                            createdAtMs = now
                        )
                    )
                } catch (_: Exception) { }
                // Post-publish correction: bump the version and push a fresh snapshot.
                val exam = _selectedExam.value
                if (exam != null && exam.status == "published") {
                    try {
                        val subjects = db.finalExamDao().getSubjectsOnce(exam.id)
                        val rows = computeRows(exam, subjects.map { it.toView() }, db.finalExamDao().getMarksOnce(exam.id))
                        val nextVersion = FinalExamSyncHelper.currentVersion(exam.id, exam.instituteId) + 1
                        val studentName = rows.firstOrNull { it.student.id == studentId }?.student?.fullName ?: studentId
                        pushResultsToCloud(
                            exam, subjects, rows, nextVersion,
                            "Marks corrected: ${subject.subjectName} — $studentName"
                        )
                    } catch (_: Exception) {
                        // Local correction stands; a re-sync from the results screen retries the push.
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to edit marks.")
            }
        }
    }

    /** Publish the whole final exam — only when all subject marks are approved. */
    fun publishExam(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val exam = _selectedExam.value ?: run { onError("No exam selected."); return }
        viewModelScope.launch {
            try {
                val unapproved = db.finalExamDao().countUnapprovedMarks(exam.id)
                if (unapproved > 0) {
                    onError("$unapproved subject mark sets are not approved yet.")
                    return@launch
                }
                val marksCount = db.finalExamDao().countMarks(exam.id)
                if (marksCount == 0) {
                    onError("No marks entered yet.")
                    return@launch
                }
                val subjects = db.finalExamDao().getSubjectsOnce(exam.id)
                val rows = computeRows(exam, subjects.map { it.toView() }, db.finalExamDao().getMarksOnce(exam.id))
                if (rows.isEmpty()) {
                    onError("Results are incomplete — every student needs approved marks for every subject.")
                    return@launch
                }
                val now = System.currentTimeMillis()
                val publishedExam = exam.copy(status = "published", publishedAtMs = now, updatedAtMs = now)
                db.finalExamDao().updateFinalExamStatus(exam.id, exam.instituteId, "published", now, now)
                pushResultsToCloud(publishedExam, subjects, rows, 1, "Results published")
                StaffActivityLogger.logCompletedAction(
                    db, "final_exam_published", "final_exams", "Published results for ${exam.examName}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to publish exam.")
            }
        }
    }

    /** Owner-only: hides published results from students. Marks stay intact. */
    fun unpublishExam(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can unpublish results."); return }
        val exam = _selectedExam.value ?: run { onError("No exam selected."); return }
        if (exam.status != "published") { onError("This exam is not published."); return }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                db.finalExamDao().updateFinalExamStatus(exam.id, exam.instituteId, "completed", null, now)
                StaffActivityLogger.logCompletedAction(
                    db, "final_exam_unpublished", "final_exams", "Unpublished results for ${exam.examName}"
                )
                try {
                    val subjects = db.finalExamDao().getSubjectsOnce(exam.id)
                    FinalExamSyncHelper.unpublish(exam, subjects)
                } catch (e: Exception) {
                    onError("Unpublished locally, but cloud sync failed: ${e.message ?: "unknown error"}")
                    return@launch
                }
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to unpublish exam.")
            }
        }
    }

    /** Re-pushes published results without bumping the version (used as a retry / refresh). */
    fun syncPublishedToCloud(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val exam = _selectedExam.value ?: run { onError("No exam selected."); return }
        if (exam.status != "published") { onError("This exam is not published."); return }
        viewModelScope.launch {
            try {
                val subjects = db.finalExamDao().getSubjectsOnce(exam.id)
                val rows = computeRows(exam, subjects.map { it.toView() }, db.finalExamDao().getMarksOnce(exam.id))
                val version = FinalExamSyncHelper.currentVersion(exam.id, exam.instituteId)
                pushResultsToCloud(exam, subjects, rows, version, "Results re-synced")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to sync results.")
            }
        }
    }

    private suspend fun pushResultsToCloud(
        exam: FinalExamEntity,
        subjects: List<FinalExamSubjectEntity>,
        rows: List<FinalResultRow>,
        version: Int,
        description: String
    ) {
        val batchName = db.batchDao().getBatchesByInstituteOnce(exam.instituteId)
            .find { it.id == exam.batchId }?.name ?: "Batch"
        val syncRows = rows.map { row ->
            FinalResultSyncRow(
                studentId = row.student.id,
                studentName = row.student.fullName,
                meritPosition = row.meritPosition,
                totalMarks = row.totalMarks,
                fullMarks = row.fullMarks,
                percentage = row.percentage,
                gpa = row.gpa,
                grade = row.grade,
                passed = row.passed,
                subjectMarks = row.subjectMarks.mapValues { it.value.totalMarks }
            )
        }
        FinalExamSyncHelper.pushPublishedResults(
            db = db,
            exam = exam,
            subjects = subjects,
            rows = syncRows,
            batchName = batchName,
            version = version,
            versionDescription = description,
            changedByUserId = SessionManager.currentUserId.value,
            changedByName = "Institute Owner"
        )
    }

    private fun recomputeResults(marks: List<FinalExamMarksEntity>) {
        val exam = _selectedExam.value ?: return
        val subjectViews = _subjects.value
        viewModelScope.launch {
            _results.value = computeRows(exam, subjectViews, marks)
            computeAnalytics(_results.value, subjectViews)
        }
    }

    /** Builds ranked result rows; students without complete approved marks are excluded. */
    private suspend fun computeRows(
        exam: FinalExamEntity,
        subjectViews: List<FinalSubjectView>,
        marks: List<FinalExamMarksEntity>
    ): List<FinalResultRow> {
        val subjectMap = subjectViews.associateBy { it.subject.id }
        val students = db.batchStudentDao().getStudentsForBatchOnce(exam.batchId, exam.instituteId)
            .filter { it.status == "active" }
        val byStudent = marks.groupBy { it.studentId }
        val rows = students.mapNotNull { student ->
            val studentMarks = byStudent[student.id] ?: emptyList()
            val approved = studentMarks.filter { it.status == "approved" }
            // All subjects must have approved marks for a complete result
            val complete = subjectMap.keys.all { subjectId -> approved.any { it.subjectId == subjectId } }
            if (!complete || approved.isEmpty()) return@mapNotNull null
            val total = approved.sumOf { it.totalMarks }
            val full = subjectViews.sumOf { it.subject.fullMarks }
            val passed = approved.all { it.totalMarks >= (subjectMap[it.subjectId]?.subject?.passMarks ?: 0.0) }
            val pct = if (full > 0) (total / full) * 100 else 0.0
            val (gpa, grade) = gpaAndGrade(pct, passed)
            FinalResultRow(
                student = student,
                subjectMarks = approved.associateBy { it.subjectId },
                totalMarks = total,
                fullMarks = full,
                percentage = pct,
                gpa = gpa,
                grade = grade,
                passed = passed,
                meritPosition = 0
            )
        }.sortedByDescending { it.totalMarks }
        return rows.mapIndexed { index, row -> row.copy(meritPosition = index + 1) }
    }

    /** Subject-wise class performance (worst-first) and weak-student list. */
    private fun computeAnalytics(rows: List<FinalResultRow>, subjectViews: List<FinalSubjectView>) {
        val subjectMap = subjectViews.associateBy { it.subject.id }
        _subjectStats.value = subjectViews.map { view ->
            val subject = view.subject
            val obtained = rows.map { it.subjectMarks[subject.id]?.totalMarks ?: 0.0 }
            val pctList = obtained.map { if (subject.fullMarks > 0) (it / subject.fullMarks) * 100 else 0.0 }
            val passCount = obtained.count { it >= subject.passMarks }
            SubjectStat(
                subjectId = subject.id,
                name = subject.subjectName,
                fullMarks = subject.fullMarks,
                passMarks = subject.passMarks,
                averagePct = if (pctList.isEmpty()) 0.0 else pctList.average(),
                passRatePct = if (pctList.isEmpty()) 0.0 else (passCount.toDouble() / pctList.size) * 100,
                highest = obtained.maxOrNull() ?: 0.0,
                lowest = obtained.minOrNull() ?: 0.0,
                failCount = obtained.size - passCount,
                studentCount = obtained.size
            )
        }.sortedBy { it.averagePct }
        val classAverage = if (rows.isEmpty()) 0.0 else rows.map { it.percentage }.average()
        _weakStudents.value = rows.mapNotNull { row ->
            val failed = row.subjectMarks.values.mapNotNull { marks ->
                val subject = subjectMap[marks.subjectId]?.subject ?: return@mapNotNull null
                if (marks.totalMarks < subject.passMarks) {
                    WeakSubjectLine(subject.subjectName, marks.totalMarks, subject.passMarks)
                } else null
            }
            if (failed.isEmpty() && row.percentage >= classAverage) return@mapNotNull null
            WeakStudentRow(
                student = row.student,
                failedSubjects = failed,
                belowAverage = row.percentage < classAverage,
                overallPct = row.percentage,
                meritPosition = row.meritPosition
            )
        }.sortedWith(compareBy({ it.failedSubjects.isEmpty() }, { it.overallPct }))
    }

    private fun gpaAndGrade(percentage: Double, passed: Boolean): Pair<Double, String> {
        if (!passed) return 0.0 to "F"
        return when {
            percentage >= 80 -> 5.0 to "A+"
            percentage >= 70 -> 4.0 to "A"
            percentage >= 60 -> 3.5 to "A-"
            percentage >= 50 -> 3.0 to "B"
            percentage >= 40 -> 2.0 to "C"
            else -> 1.0 to "D"
        }
    }

    private fun FinalExamSubjectEntity.toView(): FinalSubjectView {
        val comps = components.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return FinalSubjectView(
            subject = this,
            hasMcq = "mcq" in comps,
            hasCq = "cq" in comps,
            hasPractical = "practical" in comps,
            totalOnly = comps.isEmpty() || comps == listOf("total_only")
        )
    }

    data class SubjectConfig(
        val subjectName: String,
        val fullMarks: Double,
        val passMarks: Double,
        val components: List<String>,
        val mcqFullMarks: Double = 0.0,
        val cqFullMarks: Double = 0.0,
        val practicalFullMarks: Double = 0.0,
        val mcqPassMarks: Double = 0.0,
        val cqPassMarks: Double = 0.0,
        val practicalPassMarks: Double = 0.0,
        val assignedStaffId: String? = null
    )
}

class FinalExamViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FinalExamViewModel(db) as T
}
