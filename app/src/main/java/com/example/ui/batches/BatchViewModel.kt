package com.example.ui.batches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.firestore.BatchSyncHelper
import com.example.data.firestore.CoreDataSyncCoordinator
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.firestore.InstituteSyncHelper
import com.example.data.models.BatchEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BatchViewModel(private val db: AppDatabase) : ViewModel() {
    private val _batchList = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batchList = _batchList.asStateFlow()

    init {
        loadBatches()
    }

    private fun loadBatches() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.batchDao().getBatchesByInstitute(instId).collect {
                _batchList.value = it
            }
        }
    }

    fun addBatch(name: String, feeAmount: Double, description: String? = null, onError: (String) -> Unit = {}, onSuccess: () -> Unit) {
        if (name.isBlank()) {
            onError("Batch name is required.")
            return
        }
        if (feeAmount < 0) {
            onError("Fee amount cannot be negative.")
            return
        }
        val instId = SessionManager.currentInstituteId.value ?: return
        val batch = BatchEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instId,
            batchCode = "BAT-${UUID.randomUUID().toString().take(8)}",
            name = name,
            subject = null,
            className = null,
            teacherName = null,
            monthlyFeeAmount = feeAmount,
            admissionFeeAmount = 0.0,
            startDateMs = System.currentTimeMillis(),
            endDateMs = null,
            scheduleDays = null,
            startTime = null,
            endTime = null,
            maxStudents = 50,
            status = "active",
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            BatchSyncHelper.upsertBatch(batch)
            db.batchDao().insertBatch(batch)
            try {
                val count = withContext(Dispatchers.IO) {
                    db.batchDao().getBatchesByInstituteOnce(instId).size
                }
                InstituteSyncHelper.updateBatchCount(instId, count)
            } catch (_: Exception) { }
            onSuccess()
        }
    }

    fun updateBatch(batch: BatchEntity, onError: (String) -> Unit = {}, onSuccess: () -> Unit) {
        if (batch.name.isBlank()) {
            onError("Batch name is required.")
            return
        }
        if (batch.monthlyFeeAmount <= 0) {
            onError("Fee amount must be greater than 0.")
            return
        }
        viewModelScope.launch {
            val updated = batch.copy(updatedAtMs = System.currentTimeMillis())
            BatchSyncHelper.upsertBatch(updated)
            db.batchDao().updateBatch(updated)
            onSuccess()
        }
    }

    fun deleteBatch(batch: BatchEntity, onError: (String) -> Unit = {}, onSuccess: () -> Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) {
            onError("No active institute session.")
            return
        }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                db.batchDao().archiveBatch(batch.id, instId, now, now)
                BatchSyncHelper.archiveBatch(batch)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete batch.")
            }
        }
    }

    fun shiftAllStudents(
        fromBatch: BatchEntity,
        toBatch: BatchEntity,
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) {
            onError("No active institute session.")
            return
        }
        if (fromBatch.id == toBatch.id) {
            onError("Source and target batch must be different.")
            return
        }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val students = withContext(Dispatchers.IO) {
                    db.batchStudentDao().getStudentsForBatch(fromBatch.id, instId).firstOrNull() ?: emptyList()
                }
                if (students.isEmpty()) {
                    onError("No active students found in this batch.")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    students.forEach { student ->
                        db.batchStudentDao().removeStudentFromBatch(fromBatch.id, student.id, instId, now)
                        val enrollment = com.example.data.models.BatchStudentEntity(
                            id = UUID.randomUUID().toString(),
                            instituteId = instId,
                            batchId = toBatch.id,
                            studentId = student.id,
                            joinedAtMs = now,
                            status = "active",
                            leftAtMs = null
                        )
                        db.batchStudentDao().enrollStudent(enrollment)
                        db.feeDao().updateFeeBatchIdForStudent(student.id, fromBatch.id, toBatch.id, instId, now)
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to shift students.")
            }
        }
    }
}

class BatchViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BatchViewModel::class.java)) return BatchViewModel(db) as T
        throw IllegalArgumentException()
    }
}
