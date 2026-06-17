package com.example.ui.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class BirthdayViewModel(private val db: AppDatabase) : ViewModel() {
    private val _todayBirthdays = MutableStateFlow<List<StudentEntity>>(emptyList())
    val todayBirthdays = _todayBirthdays.asStateFlow()

    private val _upcomingBirthdays = MutableStateFlow<List<StudentEntity>>(emptyList())
    val upcomingBirthdays = _upcomingBirthdays.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.studentDao().getStudentsByInstitute(instId).collect { students ->
                val today = Calendar.getInstance()
                val withDays = students
                    .filter { it.dateOfBirthMs != null }
                    .map { student ->
                        val diff = daysUntilNextBirthday(student.dateOfBirthMs!!, today)
                        student to diff
                    }
                    .filter { it.second in 0..30 }
                    .sortedBy { it.second }

                _todayBirthdays.value = withDays
                    .filter { it.second == 0 }
                    .map { it.first }

                _upcomingBirthdays.value = withDays
                    .filter { it.second > 0 }
                    .map { it.first }
            }
        }
    }

    fun daysUntil(dobMs: Long): Int {
        return daysUntilNextBirthday(dobMs, Calendar.getInstance())
    }

    fun calculateAge(dobMs: Long): Int {
        val dob = Calendar.getInstance().apply { timeInMillis = dobMs }
        val today = Calendar.getInstance()
        var age = today.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
        if (today.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) age--
        return age.coerceAtLeast(0)
    }

    private fun daysUntilNextBirthday(dobMs: Long, today: Calendar): Int {
        val dob = Calendar.getInstance().apply { timeInMillis = dobMs }
        val next = Calendar.getInstance().apply {
            val month = dob.get(Calendar.MONTH)
            val day = dob.get(Calendar.DAY_OF_MONTH)
            val isFeb29 = month == Calendar.FEBRUARY && day == 29
            val year = today.get(Calendar.YEAR)
            if (isFeb29 && !isLeapYear(year)) {
                set(year, Calendar.FEBRUARY, 28)
            } else {
                set(year, month, day)
            }
            if (before(today)) {
                val nextYear = year + 1
                if (isFeb29 && !isLeapYear(nextYear)) {
                    set(nextYear, Calendar.FEBRUARY, 28)
                } else {
                    set(nextYear, month, day)
                }
            }
        }
        return ((next.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
    }

    private fun isLeapYear(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

class BirthdayViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BirthdayViewModel::class.java)) return BirthdayViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
