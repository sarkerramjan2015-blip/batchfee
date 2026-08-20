package com.batchfee.edu.ui.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

class BirthdayViewModel(private val db: AppDatabase) : ViewModel() {
    private val _todayBirthdays = MutableStateFlow<List<StudentEntity>>(emptyList())
    val todayBirthdays = _todayBirthdays.asStateFlow()

    private val _upcomingBirthdays = MutableStateFlow<List<StudentEntity>>(emptyList())
    val upcomingBirthdays = _upcomingBirthdays.asStateFlow()

    // Emits now and once at every local midnight so an open Birthday screen
    // never keeps yesterday's count after the date changes.
    private val _dayTick = MutableStateFlow(System.currentTimeMillis())
    val dayTick = _dayTick.asStateFlow()

    init {
        startDayTicker()
        loadData()
    }

    private fun startDayTicker() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                _dayTick.value = now
                delay(millisecondsUntilNextLocalDay(now))
            }
        }
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.studentDao().getStudentsByInstitute(instId)
                .combine(_dayTick) { students, now ->
                    students to Calendar.getInstance().apply { timeInMillis = now }
                }
                .collect { (students, today) ->
                val withDays = students
                    .filter { it.dateOfBirthMs != null && it.isBirthdayEligible() }
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
        return daysUntilNextBirthday(dobMs, Calendar.getInstance().apply { timeInMillis = _dayTick.value })
    }

    fun calculateAge(dobMs: Long): Int {
        val dob = Calendar.getInstance().apply { timeInMillis = dobMs }
        val today = Calendar.getInstance().apply { timeInMillis = _dayTick.value }
        var age = today.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
        val birthdayThisYear = Calendar.getInstance().apply {
            clear()
            val isFeb29 = dob.get(Calendar.MONTH) == Calendar.FEBRUARY && dob.get(Calendar.DAY_OF_MONTH) == 29
            set(
                today.get(Calendar.YEAR),
                dob.get(Calendar.MONTH),
                if (isFeb29 && !isLeapYear(today.get(Calendar.YEAR))) 28 else dob.get(Calendar.DAY_OF_MONTH)
            )
        }
        if (today.before(birthdayThisYear)) age--
        return age.coerceAtLeast(0)
    }

    private fun daysUntilNextBirthday(dobMs: Long, today: Calendar): Int {
        val dob = Calendar.getInstance().apply { timeInMillis = dobMs }
        val startOfToday = (today.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val next = Calendar.getInstance().apply {
            val month = dob.get(Calendar.MONTH)
            val day = dob.get(Calendar.DAY_OF_MONTH)
            val isFeb29 = month == Calendar.FEBRUARY && day == 29
            val year = startOfToday.get(Calendar.YEAR)
            clear()
            if (isFeb29 && !isLeapYear(year)) {
                set(year, Calendar.FEBRUARY, 28)
            } else {
                set(year, month, day)
            }
            if (before(startOfToday)) {
                val nextYear = year + 1
                clear()
                if (isFeb29 && !isLeapYear(nextYear)) {
                    set(nextYear, Calendar.FEBRUARY, 28)
                } else {
                    set(nextYear, month, day)
                }
            }
        }
        return ((next.timeInMillis - startOfToday.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
    }

    private fun isLeapYear(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun millisecondsUntilNextLocalDay(now: Long): Long {
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return (next.timeInMillis - now).coerceAtLeast(1_000L)
    }

    private fun StudentEntity.isBirthdayEligible(): Boolean =
        status.trim().lowercase() !in setOf("inactive", "closed", "close", "removed")
}

class BirthdayViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BirthdayViewModel::class.java)) return BirthdayViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

