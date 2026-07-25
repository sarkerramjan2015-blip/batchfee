package com.batchfee.edu.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.ReminderTemplateSyncHelper
import com.batchfee.edu.data.models.ReminderTemplateEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReminderTemplateViewModel(private val db: AppDatabase) : ViewModel() {
    private val _templates = MutableStateFlow<List<ReminderTemplateEntity>>(emptyList())
    val templates = _templates.asStateFlow()

    init {
        loadTemplates()
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.reminderTemplateDao().getTemplatesForInstitute(instId).collect { list ->
                _templates.value = list
            }
        }
    }
}

class ReminderTemplateViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReminderTemplateViewModel::class.java)) return ReminderTemplateViewModel(db) as T
        throw IllegalArgumentException()
    }
}

