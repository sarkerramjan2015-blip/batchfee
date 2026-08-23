package com.batchfee.edu.ui.enquiries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.EnquirySyncHelper
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.EnquiryEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class EnquiryViewModel(private val db: AppDatabase) : ViewModel() {
    private val mutationsInProgress = mutableSetOf<String>()
    private val _allEnquiries = MutableStateFlow<List<EnquiryEntity>>(emptyList())
    val allEnquiries = _allEnquiries.asStateFlow()

    private val _filterStatus = MutableStateFlow("all")
    val filterStatus = _filterStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _todayCount = MutableStateFlow(0)
    val todayCount = _todayCount.asStateFlow()

    private val _followUpCount = MutableStateFlow(0)
    val followUpCount = _followUpCount.asStateFlow()

    private val _todayFollowUpCount = MutableStateFlow(0)
    val todayFollowUpCount = _todayFollowUpCount.asStateFlow()

    private val _overdueFollowUpCount = MutableStateFlow(0)
    val overdueFollowUpCount = _overdueFollowUpCount.asStateFlow()

    val todayLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(
        Calendar.getInstance().time
    )

    init {
        loadEnquiries()
    }

    private fun loadEnquiries() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            _isLoading.value = true
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.enquiryDao().getEnquiriesByInstitute(instId).collect { list ->
                _allEnquiries.value = list

                val startOfToday = startOfDay(System.currentTimeMillis())
                val startOfTomorrow = Calendar.getInstance().apply {
                    timeInMillis = startOfToday
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                _todayCount.value = list.count { it.enquiryDateMs >= startOfToday }
                val followUps = list.filter { isFollowUp(it.status) }
                _followUpCount.value = followUps.size
                // Old records did not have a scheduled follow-up date. Do not
                // guess a date from their original enquiry date: owners should
                // only be reminded about contacts they explicitly scheduled.
                _todayFollowUpCount.value = followUps.count { enquiry ->
                    enquiry.followUpDateMs?.let { it in startOfToday until startOfTomorrow } == true
                }
                _overdueFollowUpCount.value = followUps.count { enquiry ->
                    enquiry.followUpDateMs?.let { it < startOfToday } == true
                }
                _isLoading.value = false
            }
        }
    }

    fun setFilter(status: String) {
        _filterStatus.value = status
    }

    fun updateStatus(enquiry: EnquiryEntity, newStatus: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val updated = enquiry.copy(
                        status = newStatus,
                        followUpDateMs = if (isFollowUp(newStatus)) {
                            enquiry.followUpDateMs ?: startOfDay(System.currentTimeMillis())
                        } else enquiry.followUpDateMs,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    EnquirySyncHelper.upsertEnquiry(updated)
                    db.enquiryDao().updateEnquiry(updated)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update status")
            }
        }
    }

    /** Scheduling always keeps this enquiry in the follow-up queue. */
    fun scheduleFollowUp(enquiry: EnquiryEntity, dateMs: Long, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val updated = enquiry.copy(
                        status = "follow_up",
                        followUpDateMs = startOfDay(dateMs),
                        updatedAtMs = System.currentTimeMillis()
                    )
                    EnquirySyncHelper.upsertEnquiry(updated)
                    db.enquiryDao().updateEnquiry(updated)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Could not schedule follow-up")
            }
        }
    }

    fun updateNote(enquiry: EnquiryEntity, note: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val updated = enquiry.copy(
                        note = note.trim().takeIf { it.isNotEmpty() },
                        updatedAtMs = System.currentTimeMillis()
                    )
                    EnquirySyncHelper.upsertEnquiry(updated)
                    db.enquiryDao().updateEnquiry(updated)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update note")
            }
        }
    }

    fun deleteEnquiry(enquiry: EnquiryEntity, onError: (String) -> Unit = {}) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    EnquirySyncHelper.deleteEnquiry(enquiry.id, instId)
                    db.enquiryDao().deleteEnquiry(enquiry.id, instId)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete enquiry")
            }
        }
    }

    fun addEnquiry(
        name: String,
        phone: String,
        address: String?,
        subjectName: String,
        enquiryDateMs: Long,
        enquiryId: String = UUID.randomUUID().toString(),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) { onError("No active institute."); return }
        if (name.isBlank()) { onError("Name is required."); return }
        if (phone.isBlank()) { onError("Phone is required."); return }
        if (subjectName.isBlank()) { onError("Subject is required."); return }
        val mutationKey = "create:$enquiryId"
        if (!synchronized(mutationsInProgress) { mutationsInProgress.add(mutationKey) }) {
            onError("This enquiry is already being saved.")
            return
        }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val enquiry = EnquiryEntity(
                    id = enquiryId,
                    instituteId = instId,
                    name = name.trim(),
                    phone = phone.trim(),
                    address = address?.trim()?.takeIf { it.isNotEmpty() },
                    subjectName = subjectName.trim(),
                    enquiryDateMs = enquiryDateMs,
                    status = "active",
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
                withContext(Dispatchers.IO) {
                    EnquirySyncHelper.upsertEnquiry(enquiry)
                    db.enquiryDao().insertEnquiry(enquiry)
                }
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save enquiry.")
            } finally {
                synchronized(mutationsInProgress) { mutationsInProgress.remove(mutationKey) }
            }
        }
    }

    private fun isFollowUp(status: String): Boolean =
        status.equals("follow_up", ignoreCase = true) || status.equals("follow up", ignoreCase = true)

    private fun startOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

class EnquiryViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EnquiryViewModel::class.java)) return EnquiryViewModel(db) as T
        throw IllegalArgumentException()
    }
}

