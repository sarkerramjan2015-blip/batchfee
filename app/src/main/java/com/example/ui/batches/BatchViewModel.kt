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
import com.batchfee.edu.data.repository.BatchEnrollmentRepository
import com.batchfee.edu.domain.BatchBillingMode
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.isCourseBatch
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
        billingMode: String = BatchBillingMode.MONTHLY,
        courseFeeAmount: Double = 0.0,
        admissionFeeAmount: Double = 0.0,
        startDateMs: Long? = null,
        endDateMs: Long? = null,
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
        val normalizedBillingMode = BatchBillingMode.normalize(billingMode)
        if (normalizedBillingMode == BatchBillingMode.MONTHLY && feeAmount <= 0) {
            onError("Monthly fee must be greater than 0.")
            return
        }
        if (normalizedBillingMode == BatchBillingMode.COURSE && courseFeeAmount <= 0) {
            onError("Course fee must be greater than 0.")
            return
        }
        if (normalizedBillingMode == BatchBillingMode.COURSE &&
            (startDateMs == null || endDateMs == null || endDateMs < startDateMs)
        ) {
            onError("Select a valid course start and end date.")
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
            monthlyFeeAmount = if (normalizedBillingMode == BatchBillingMode.MONTHLY) feeAmount else 0.0,
            admissionFeeAmount = admissionFeeAmount,
            startDateMs = if (normalizedBillingMode == BatchBillingMode.COURSE) startDateMs else System.currentTimeMillis(),
            endDateMs = if (normalizedBillingMode == BatchBillingMode.COURSE) endDateMs else null,
            scheduleDays = scheduleDays,
            startTime = startTime,
            endTime = endTime,
            maxStudents = 50,
            status = "active",
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null,
            billingMode = normalizedBillingMode,
            courseFeeAmount = if (normalizedBillingMode == BatchBillingMode.COURSE) courseFeeAmount else 0.0
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
        if (batch.isCourseBatch()) {
            if (batch.courseFeeAmount <= 0.0) {
                onError("Course fee must be greater than 0.")
                return
            }
            if (batch.startDateMs == null || batch.endDateMs == null || batch.endDateMs < batch.startDateMs) {
                onError("Select a valid course start and end date.")
                return
            }
        } else if (batch.monthlyFeeAmount <= 0) {
            onError("Monthly fee must be greater than 0.")
            return
        }
        val mutationKey = "update:${batch.id}"
        if (!startMutation(mutationKey)) {
            onError("Batch update is already in progress.")
            return
        }
        viewModelScope.launch {
            try {
                val persisted = withContext(Dispatchers.IO) {
                    db.batchDao().getBatchById(batch.id, batch.instituteId).firstOrNull()
                }
                val persistedMode = persisted?.let { BatchBillingMode.normalize(it.billingMode) }
                val requestedMode = BatchBillingMode.normalize(batch.billingMode)
                if (persistedMode != null && persistedMode != requestedMode) {
                    onError("Batch type is locked after creation. Create a new batch for a different billing type.")
                    return@launch
                }
                val updated = batch.copy(
                    billingMode = persistedMode ?: requestedMode,
                    updatedAtMs = System.currentTimeMillis()
                )
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
                val sourceEnrollments = withContext(Dispatchers.IO) {
                    db.batchStudentDao().getActiveEnrollmentsForBatchOnce(fromBatch.id, instId)
                }
                if (sourceEnrollments.isEmpty()) {
                    onError("No active students found in this batch.")
                    return@launch
                }
                val targetStudentIds = withContext(Dispatchers.IO) {
                    db.batchStudentDao().getStudentsForBatchOnce(toBatch.id, instId).map { it.id }.toSet()
                }
                val duplicates = sourceEnrollments.count { it.studentId in targetStudentIds }
                if (duplicates > 0) {
                    onError("$duplicates student(s) are already in the target batch. Remove duplicates before moving everyone.")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val enrollmentRepository = BatchEnrollmentRepository(db)
                    sourceEnrollments.forEach { sourceEnrollment ->
                        enrollmentRepository.shift(
                            sourceEnrollment = sourceEnrollment,
                            targetBatch = toBatch,
                            shiftDateMs = now
                        )
                    }
                }
                StaffActivityLogger.logCompletedAction(
                    db,
                    "batch_students_shifted",
                    "batches",
                    "Moved ${sourceEnrollments.size} students from ${fromBatch.name} to ${toBatch.name}"
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

