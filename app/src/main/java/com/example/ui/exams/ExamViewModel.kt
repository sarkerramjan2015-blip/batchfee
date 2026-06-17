package com.example.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.firestore.ExamSyncHelper
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.BatchEntity
import com.example.data.models.ExamEntity
import com.example.data.models.ResultEntity
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class StudentResultItem(
    val student: StudentEntity,
    val result: ResultEntity?,
    val marksText: String,
    val position: Int
)

class ExamViewModel(private val db: AppDatabase) : ViewModel() {
    private val _exams = MutableStateFlow<List<ExamEntity>>(emptyList())
    val exams = _exams.asStateFlow()

    private val _batches = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batches = _batches.asStateFlow()

    private val _instituteName = MutableStateFlow("")

    private val _selectedExam = MutableStateFlow<ExamEntity?>(null)
    val selectedExam = _selectedExam.asStateFlow()

    private val _studentResults = MutableStateFlow<List<StudentResultItem>>(emptyList())
    val studentResults = _studentResults.asStateFlow()

    private val _batchStudents = MutableStateFlow<List<StudentEntity>>(emptyList())
    val batchStudents = _batchStudents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    init { loadData() }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.examDao().getExamsByInstitute(instId).collect { _exams.value = it }
        }
        viewModelScope.launch {
            db.batchDao().getBatchesByInstitute(instId).collect { _batches.value = it }
        }
        viewModelScope.launch {
            db.instituteDao().getInstituteFlow(instId).collect { institute ->
                _instituteName.value = institute?.name?.trim().orEmpty()
            }
        }
    }

    fun loadExamDetails(examId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        _isLoading.value = true
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.examDao().getExamById(examId, instId).collect { exam ->
                _selectedExam.value = exam
                if (exam != null) {
                    launch {
                        db.batchStudentDao().getStudentsForBatch(exam.batchId, instId).collect { students ->
                            _batchStudents.value = students
                        }
                    }
                    launch {
                        combine(
                            db.resultDao().getResultsForExam(examId, instId),
                            db.batchStudentDao().getStudentsForBatch(exam.batchId, instId)
                        ) { results, students ->
                            val resultMap = results.associateBy { it.studentId }
                            val sorted = students.sortedByDescending { s ->
                                resultMap[s.id]?.marksObtained ?: 0.0
                            }
                            sorted.mapIndexed { idx, s ->
                                val r = resultMap[s.id]
                                StudentResultItem(
                                    student = s,
                                    result = r,
                                    marksText = r?.marksObtained?.let { formatMarks(it) } ?: "",
                                    position = idx + 1
                                )
                            }
                        }.collect { items ->
                            _studentResults.value = items
                            _isLoading.value = false
                        }
                    }
                } else {
                    _isLoading.value = false
                }
            }
        }
    }

    fun createExam(
        batchId: String,
        examName: String,
        subject: String?,
        totalMarks: Double,
        passingMarks: Double,
        examDateMs: Long,
        teacherName: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        if (batchId.isBlank()) { onError("Batch is required."); return }
        if (examName.isBlank()) { onError("Exam name is required."); return }
        if (totalMarks <= 0) { onError("Total marks must be greater than 0."); return }
        if (passingMarks > totalMarks) { onError("Passing marks cannot exceed total marks."); return }

        val exam = ExamEntity(
            id = UUID.randomUUID().toString(), instituteId = instId,
            batchId = batchId, examName = examName,
            subject = subject?.trim()?.takeIf { it.isNotEmpty() },
            examDateMs = examDateMs, totalMarks = totalMarks,
            passingMarks = passingMarks,
            teacherName = teacherName?.trim()?.takeIf { it.isNotEmpty() },
            note = null, status = "scheduled",
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(), archivedAtMs = null
        )
        viewModelScope.launch {
            ExamSyncHelper.upsertExam(exam)
            db.examDao().insertExam(exam)
            onSuccess()
        }
    }

    fun updateExam(
        examId: String,
        batchId: String,
        examName: String,
        subject: String?,
        totalMarks: Double,
        passingMarks: Double,
        examDateMs: Long,
        teacherName: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentExam = _selectedExam.value
        if (currentExam == null || currentExam.id != examId) {
            onError("Exam details not loaded yet.")
            return
        }
        if (batchId.isBlank()) { onError("Batch is required."); return }
        if (examName.isBlank()) { onError("Exam name is required."); return }
        if (totalMarks <= 0) { onError("Total marks must be greater than 0."); return }
        if (passingMarks > totalMarks) { onError("Passing marks cannot exceed total marks."); return }

        viewModelScope.launch {
            val updated = currentExam.copy(
                instituteId = instId,
                batchId = batchId,
                examName = examName.trim(),
                subject = subject?.trim()?.takeIf { it.isNotEmpty() },
                examDateMs = examDateMs,
                totalMarks = totalMarks,
                passingMarks = passingMarks,
                teacherName = teacherName?.trim()?.takeIf { it.isNotEmpty() },
                updatedAtMs = System.currentTimeMillis()
            )
            ExamSyncHelper.upsertExam(updated)
            db.examDao().updateExam(updated)
            onSuccess()
        }
    }

    fun archiveExam(examId: String, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                _selectedExam.value?.copy(archivedAtMs = now, updatedAtMs = now)?.let {
                    ExamSyncHelper.upsertExam(it)
                }
                db.examDao().archiveExam(instId, examId, now)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete exam")
            }
        }
    }

    fun saveResults(
        examId: String,
        batchId: String,
        marksList: List<Pair<String, Double>>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val totalMarks = _selectedExam.value?.totalMarks ?: 100.0
        val passingMarks = _selectedExam.value?.passingMarks ?: 40.0

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val sorted = marksList.sortedByDescending { it.second }
                val results = sorted.mapIndexed { idx, (studentId, marks) ->
                    val grade = calculateGrade(marks, totalMarks, passingMarks)
                    ResultEntity(
                        id = UUID.randomUUID().toString(), instituteId = instId,
                        examId = examId, batchId = batchId, studentId = studentId,
                        marksObtained = marks, grade = grade, position = idx + 1,
                        remarks = null, published = false,
                        createdAtMs = now, updatedAtMs = now
                    )
                }
                withContext(Dispatchers.IO) {
                    results.forEach {
                        ExamSyncHelper.upsertResult(it)
                        db.resultDao().insertOrUpdateResult(it)
                    }
                    val completedExam = _selectedExam.value!!.copy(status = "completed", updatedAtMs = now)
                    ExamSyncHelper.upsertExam(completedExam)
                    db.examDao().updateExam(completedExam)
                }
                loadExamDetails(examId)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save results")
            }
        }
    }

    fun publishResults(examId: String, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val results = _studentResults.value.filter { it.result != null }
        if (results.isEmpty()) { onError("No results to publish."); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    results.forEach { item ->
                        val updated = item.result!!.copy(published = true, updatedAtMs = System.currentTimeMillis())
                        ExamSyncHelper.upsertResult(updated)
                        db.resultDao().insertOrUpdateResult(updated)
                    }
                }
                loadExamDetails(examId)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to publish")
            }
        }
    }

    fun buildMeritMessage(exam: ExamEntity, includeAll: Boolean = true): String {
        val results = _studentResults.value.filter { it.result != null }
        val batchName = _batches.value.find { it.id == exam.batchId }?.name ?: "Batch"
        val instituteName = currentInstituteName()
        val sb = StringBuilder()
        sb.appendLine("Exam Merit List")
        sb.appendLine("${exam.examName} - $batchName")
        if (exam.subject != null) sb.appendLine("Subject: ${exam.subject}")
        sb.appendLine("Total Marks: ${formatMarks(exam.totalMarks)} | Pass: ${formatMarks(exam.passingMarks)}")
        sb.appendLine()
        val list = if (includeAll) results else results.take(10)
        list.forEach { item ->
            val grade = item.result?.grade ?: "-"
            val marks = item.result?.marksObtained?.let { formatMarks(it) } ?: "-"
            sb.appendLine("${item.position}. ${item.student.fullName} - $marks ($grade)")
        }
        sb.appendLine()
        sb.appendLine("Sent via $instituteName")
        return sb.toString()
    }

    fun buildStudentMessage(item: StudentResultItem, exam: ExamEntity): String {
        val grade = item.result?.grade ?: "-"
        val marks = item.result?.marksObtained?.let { formatMarks(it) } ?: "-"
        val batchName = _batches.value.find { it.id == exam.batchId }?.name ?: "Batch"
        val instituteName = currentInstituteName()
        val passFail = if ((item.result?.marksObtained ?: 0.0) >= exam.passingMarks) "Passed" else "Needs Improvement"
        return buildString {
            appendLine("Exam Result")
            appendLine("${exam.examName} - $batchName")
            if (exam.subject != null) appendLine("Subject: ${exam.subject}")
            appendLine("Marks: $marks / ${formatMarks(exam.totalMarks)}")
            appendLine("Grade: $grade | Position: ${item.position}")
            appendLine("Result: $passFail")
            appendLine()
            appendLine("-$instituteName")
        }
    }

    private fun currentInstituteName(): String {
        return _instituteName.value.takeIf { it.isNotBlank() } ?: "BatchFee"
    }

    private fun calculateGrade(marks: Double, total: Double, pass: Double): String {
        if (marks < pass) return "F"
        val pct = (marks / total) * 100
        return when {
            pct >= 80 -> "A+"
            pct >= 70 -> "A"
            pct >= 60 -> "A-"
            pct >= 50 -> "B"
            pct >= 40 -> "C"
            else -> "D"
        }
    }

    private fun formatMarks(value: Double): String {
        return if (value == value.toLong().toDouble()) value.toLong().toString()
        else "%.1f".format(value)
    }
}

class ExamViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExamViewModel::class.java)) return ExamViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
