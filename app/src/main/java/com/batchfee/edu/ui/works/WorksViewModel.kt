package com.batchfee.edu.ui.works

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.WorkSyncHelper
import com.batchfee.edu.data.models.WorkEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class WorksViewModel(private val db: AppDatabase) : ViewModel() {
    private val instId = SessionManager.currentInstituteId.value ?: ""

    private val _works = MutableStateFlow<List<WorkEntity>>(emptyList())
    val works: StateFlow<List<WorkEntity>> = _works.asStateFlow()

    private val _batches = MutableStateFlow<List<com.batchfee.edu.data.models.BatchEntity>>(emptyList())
    val batches: StateFlow<List<com.batchfee.edu.data.models.BatchEntity>> = _batches.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            db.workDao().getActiveWorks(instId).collect { _works.value = it }
        }
        viewModelScope.launch {
            db.batchDao().getBatchesByInstitute(instId).collect { _batches.value = it }
        }
    }

    fun addWork(
        batchId: String?,
        type: String,
        title: String,
        description: String,
        dueDateMs: Long?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (title.isBlank()) { onError("Title is required."); return }
        viewModelScope.launch {
            val work = WorkEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                batchId = batchId,
                type = type,
                title = title.trim(),
                description = description.trim(),
                dueDateMs = dueDateMs,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
                archivedAtMs = null
            )
            withContext(Dispatchers.IO) { db.workDao().upsertWork(work) }
            launch { WorkSyncHelper.upsertWork(work) }
            onSuccess()
        }
    }

    fun archiveWork(work: WorkEntity) {
        viewModelScope.launch {
            val copy = work.copy(archivedAtMs = System.currentTimeMillis())
            withContext(Dispatchers.IO) {
                db.workDao().archiveWork(work.id, instId, System.currentTimeMillis())
            }
            launch { WorkSyncHelper.upsertWork(copy) }
        }
    }

    fun deleteWork(work: WorkEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.workDao().archiveWork(work.id, instId, System.currentTimeMillis()) }
            launch {
                val copy = work.copy(archivedAtMs = System.currentTimeMillis())
                WorkSyncHelper.upsertWork(copy)
            }
        }
    }
}

class WorksViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorksViewModel::class.java)) return WorksViewModel(db) as T
        throw IllegalArgumentException()
    }
}
