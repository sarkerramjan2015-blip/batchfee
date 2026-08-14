package com.batchfee.edu.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.ReminderTemplateSyncHelper
import com.batchfee.edu.data.models.ReminderTemplateEntity
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ReminderTemplateViewModel(private val db: AppDatabase) : ViewModel() {
    private val _templates = MutableStateFlow<List<ReminderTemplateEntity>>(emptyList())
    val templates = _templates.asStateFlow()

    init { loadTemplates() }

    private fun loadTemplates() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.reminderTemplateDao().getTemplatesForInstitute(instId).collect { _templates.value = it }
        }
    }

    fun upsertTemplate(title: String, type: String, message: String) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val now = System.currentTimeMillis()
            val template = ReminderTemplateEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId, title = title, type = type,
                messageTemplate = message, isDefault = false,
                createdAtMs = now, updatedAtMs = now
            )
            db.reminderTemplateDao().insertTemplate(template)
            try { ReminderTemplateSyncHelper.upsertTemplate(template) } catch (_: Exception) {}
            StaffActivityLogger.logCompletedAction(
                db, "reminder_created", "reminders", "Saved reminder template $title"
            )
        }
    }

    fun deleteTemplate(template: ReminderTemplateEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.reminderTemplateDao().deleteTemplate(template)
            try {
                FirebaseFirestore.getInstance()
                    .collection("institutes").document(template.instituteId)
                    .collection("reminder_templates").document(template.id)
                    .delete().await()
            } catch (_: Exception) {}
            StaffActivityLogger.logCompletedAction(
                db, "reminder_deleted", "reminders", "Deleted reminder template ${template.title}"
            )
        }
    }
}

class ReminderTemplateViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReminderTemplateViewModel::class.java)) return ReminderTemplateViewModel(db) as T
        throw IllegalArgumentException()
    }
}
