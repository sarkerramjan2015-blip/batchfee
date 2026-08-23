package com.batchfee.edu.ui.batches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.BatchSyncHelper
import com.batchfee.edu.data.firestore.CoreDataSyncCoordinator
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.repository.SafeDeletionRepository
import com.batchfee.edu.data.repository.EntitledCreationRepository
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BatchViewModel(private val db: AppDatabase) : ViewModel() {
    private val entitledCreationRepository = EntitledCreationRepository()
    private val mutationsInProgress = mutableSetOf<String>()
    private val _batchList = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batchList = _batchList.asStateFlow()

    init {
        loadBatches()
    }

    private fun loadBatches() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.batchDao().getBatchesByInstitute(instId).collect {
                _batchList.value = it
            }
        }
    }

    fun addBatch(
        name: String,
        feeAmount: Double,
        admissionFeeAmount: Double = 0.0,
        scheduleDays: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        description: String? = null,
        batchId: String = UUID.randomUUID().toString(),
        batchCode: String = "BAT-${UUID.randomUUID().toString().take(8)}",
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit
    ) {
        if (name.isBlank()) {
            onError("Batch name is required.")
            return
        }
        if (feeAmount < 0) {
            onError("Fee amount cannot be negative.")
            return
        }
        if (admissionFeeAmount < 0) {
            onError("Admission fee cannot be negative.")
            return
        }
        val instId = SessionManager.currentInstituteId.value ?: run {
            onError("No active institute session.")
            return
        }
        val mutationKey = "create:$batchId"
        if (!startMutation(mutationKey)) {
            onError("Batch is already being saved.")
            return
        }
        val batch = BatchEntity(
            id = batchId,
            instituteId = instId,
            batchCode = batchCode,
            name = name,
            subject = null,
            className = null,
            teacherName = null,
            monthlyFeeAmount = feeAmount,
            admissionFeeAmount = admissionFeeAmount,
            startDateMs = System.currentTimeMillis(),
            endDateMs = null,
            scheduleDays = scheduleDays,
            startTime = startTime,
            endTime = endTime,
            maxStudents = 50,
            status = "active",
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            try {
                entitledCreationRepository.createBatch(batch)
                db.batchDao().insertBatch(batch)
                StaffActivityLogger.logCompletedAction(
                    db, "batch_created", "batches", "Created batch ${batch.name}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save batch.")
            } finally {
                finishMutation(mutationKey)
            }
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
        val mutationKey = "update:${batch.id}"
        if (!startMutation(mutationKey)) {
            onError("Batch update is already in progress.")
            return
        }
        viewModelScope.launch {
            try {
                val updated = batch.copy(updatedAtMs = System.currentTimeMillis())
                BatchSyncHelper.upsertBatch(updated)
                db.batchDao().updateBatch(updated)
                StaffActivityLogger.logCompletedAction(
                    db, "batch_updated", "batches", "Updated batch ${updated.name}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update batch.")
            } finally {
                finishMutation(mutationKey)
            }
        }
    }

    private fun startMutation(key: String): Boolean = synchronized(mutationsInProgress) {
        mutationsInProgress.add(key)
    }

    private fun finishMutation(key: String) {
        synchronized(mutationsInProgress) { mutationsInProgress.remove(key) }
    }

    fun archiveBatch(batch: BatchEntity, onError: (String) -> Unit = {}, onSuccess: () -> Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) {
            onError("No active institute session.")
            return
        }
        viewModelScope.launch {
            try {
                SafeDeletionRepository(db).archiveBatch(
                    batch,
                    reason = "Batch archived from batch management"
                )
                StaffActivityLogger.logCompletedAction(
                    db, "batch_archived", "batches", "Archived batch ${batch.name}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to archive batch safely.")
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
                        val enrollment = com.batchfee.edu.data.models.BatchStudentEntity(
                            id = UUID.randomUUID().toString(),
                            instituteId = instId,
                            batchId = toBatch.id,
                            studentId = student.id,
                            joinedAtMs = now,
                            status = "active",
                            leftAtMs = null,
                            firstMonthFeePeriod = MonthlyDueCalculator.periodFor(now),
                            firstMonthFeeAmount = MonthlyDueCalculator.calculateFirstMonthFee(
                                toBatch.monthlyFeeAmount,
                                now
                            )
                        )
                        db.batchStudentDao().enrollStudent(enrollment)
                    }
                }
                StaffActivityLogger.logCompletedAction(
                    db,
                    "batch_students_shifted",
                    "batches",
                    "Moved ${students.size} students from ${fromBatch.name} to ${toBatch.name}"
                )
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

