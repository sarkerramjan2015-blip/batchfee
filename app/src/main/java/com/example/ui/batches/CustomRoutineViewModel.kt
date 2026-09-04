package com.batchfee.edu.ui.batches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.CustomRoutineEntity
import com.batchfee.edu.data.models.CustomRoutineEntryEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class CustomRoutineViewModel(private val db: AppDatabase) : ViewModel() {

    private val _routines = MutableStateFlow<List<CustomRoutineEntity>>(emptyList())
    val routines: StateFlow<List<CustomRoutineEntity>> = _routines.asStateFlow()

    private val _selectedRoutine = MutableStateFlow<CustomRoutineEntity?>(null)
    val selectedRoutine: StateFlow<CustomRoutineEntity?> = _selectedRoutine.asStateFlow()

    private val _entries = MutableStateFlow<List<CustomRoutineEntryEntity>>(emptyList())
    val entries: StateFlow<List<CustomRoutineEntryEntity>> = _entries.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadRoutines() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.customRoutineDao().getRoutines(instId).collect { _routines.value = it }
        }
    }

    fun loadRoutine(routineId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            launch {
                db.customRoutineDao().getRoutine(routineId, instId).collect { _selectedRoutine.value = it }
            }
            launch {
                db.customRoutineDao().getEntries(routineId).collect { _entries.value = it }
            }
            _isLoading.value = false
        }
    }

    /** Creates or updates a routine with all its day-wise entries. */
    fun saveRoutine(
        routineId: String?,
        routineName: String,
        className: String,
        section: String?,
        academicSession: String?,
        periodCount: Int,
        effectiveDateMs: Long?,
        entries: List<CustomRoutineEntryEntity>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run { onError("No active institute session."); return }
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can save custom routines."); return }
        if (routineName.isBlank()) { onError("Routine name is required."); return }
        if (className.isBlank()) { onError("Class name is required."); return }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val finalId = routineId ?: UUID.randomUUID().toString()
                val routine = CustomRoutineEntity(
                    id = finalId,
                    instituteId = instId,
                    routineName = routineName.trim(),
                    className = className.trim(),
                    section = section?.trim()?.takeIf { it.isNotBlank() },
                    academicSession = academicSession?.trim()?.takeIf { it.isNotBlank() },
                    periodCount = periodCount.coerceIn(1, 14),
                    effectiveDateMs = effectiveDateMs,
                    createdAtMs = routineId?.let { db.customRoutineDao().getRoutineOnce(it, instId)?.createdAtMs } ?: now,
                    updatedAtMs = now
                )
                db.customRoutineDao().upsertRoutine(routine)
                db.customRoutineDao().upsertEntries(entries.map { it.copy(routineId = finalId, instituteId = instId) })
                db.customRoutineDao().deleteEntriesNotIn(finalId, entries.map { it.id })
                onSuccess(finalId)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save routine.")
            }
        }
    }

    fun deleteRoutine(routineId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can delete routines."); return }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                db.customRoutineDao().archiveRoutine(routineId, instId, now, now)
                db.customRoutineDao().deleteAllEntries(routineId)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete routine.")
            }
        }
    }

    /** Updates a single class entry (subject / teacher / time) from the weekly detail view. */
    fun updateEntry(
        entry: CustomRoutineEntryEntity,
        subject: String,
        teacherId: String?,
        teacherName: String,
        startMinutes: Int,
        endMinutes: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run { onError("No active institute session."); return }
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can edit custom routines."); return }
        if (subject.isBlank()) { onError("Subject name is required."); return }
        if (endMinutes <= startMinutes) { onError("End time must be after start time."); return }
        val clash = _entries.value.firstOrNull {
            it.id != entry.id && it.dayIndex == entry.dayIndex &&
                startMinutes < it.endMinutes && it.startMinutes < endMinutes
        }
        if (clash != null) { onError("Time overlaps with '${clash.subjectName}' on this day."); return }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                db.customRoutineDao().upsertEntries(
                    listOf(
                        entry.copy(
                            subjectName = subject.trim(),
                            teacherId = teacherId,
                            teacherName = teacherName.ifBlank { subject.trim() },
                            startMinutes = startMinutes,
                            endMinutes = endMinutes
                        )
                    )
                )
                val current = _selectedRoutine.value ?: db.customRoutineDao().getRoutineOnce(entry.routineId, instId)
                if (current != null) db.customRoutineDao().upsertRoutine(current.copy(updatedAtMs = now))
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update class.")
            }
        }
    }
}

class CustomRoutineViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CustomRoutineViewModel(db) as T
}
