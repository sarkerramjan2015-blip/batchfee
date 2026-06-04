package com.example.ui.enquiries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.EnquiryEntity
import com.example.domain.SessionManager
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
            db.enquiryDao().getEnquiriesByInstitute(instId).collect { list ->
                _allEnquiries.value = list

                val startOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                _todayCount.value = list.count { it.enquiryDateMs >= startOfToday }
                _followUpCount.value = list.count {
                    it.status.equals("follow_up", ignoreCase = true) ||
                    it.status.equals("follow up", ignoreCase = true)
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
                    db.enquiryDao().updateEnquiry(
                        enquiry.copy(
                            status = newStatus,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update status")
            }
        }
    }

    fun addEnquiry(
        name: String,
        phone: String,
        address: String?,
        subjectName: String,
        enquiryDateMs: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) { onError("No active institute."); return }
        if (name.isBlank()) { onError("Name is required."); return }
        if (phone.isBlank()) { onError("Phone is required."); return }
        if (subjectName.isBlank()) { onError("Subject is required."); return }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val enquiry = EnquiryEntity(
                    id = UUID.randomUUID().toString(),
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
                    db.enquiryDao().insertEnquiry(enquiry)
                }
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save enquiry.")
            }
        }
    }
}

class EnquiryViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EnquiryViewModel::class.java)) return EnquiryViewModel(db) as T
        throw IllegalArgumentException()
    }
}
