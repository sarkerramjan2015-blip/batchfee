package com.example.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.example.data.models.ExamEntity
import com.example.data.models.ResultEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ExamViewModel(private val db: AppDatabase) : ViewModel() {
    private val _exams = MutableStateFlow<List<ExamEntity>>(emptyList())
    val exams = _exams.asStateFlow()

    private val _batches = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batches = _batches.asStateFlow()

    private val _selectedExam = MutableStateFlow<ExamEntity?>(null)
    val selectedExam = _selectedExam.asStateFlow()

    private val _results = MutableStateFlow<List<ResultEntity>>(emptyList())
    val results = _results.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.examDao().getExamsByInstitute(instId).collect { list ->
                _exams.value = list
            }
        }
        viewModelScope.launch {
            db.batchDao().getBatchesByInstitute(instId).collect { list ->
                _batches.value = list
            }
        }
    }

    fun loadExamDetails(examId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.examDao().getExamById(examId, instId).collect { exam ->
                _selectedExam.value = exam
            }
        }
        viewModelScope.launch {
            db.resultDao().getResultsForExam(examId, instId).collect { list ->
                _results.value = list
            }
        }
    }

    fun createExam(
        batchId: String,
        examName: String,
        totalMarks: Double,
        passingMarks: Double,
        onSuccess: () -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        if (batchId.isBlank() || examName.isBlank() || totalMarks <= 0 || passingMarks > totalMarks) return

        val exam = ExamEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instId,
            batchId = batchId,
            examName = examName,
            subject = null,
            examDateMs = System.currentTimeMillis(),
            totalMarks = totalMarks,
            passingMarks = passingMarks,
            teacherName = null,
            note = null,
            status = "scheduled",
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            db.examDao().insertExam(exam)
            onSuccess()
        }
    }
}

class ExamViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExamViewModel::class.java)) return ExamViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
