package com.example.ui.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class BirthdayViewModel(private val db: AppDatabase) : ViewModel() {
    private val _upcomingBirthdays = MutableStateFlow<List<StudentEntity>>(emptyList())
    val upcomingBirthdays = _upcomingBirthdays.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.studentDao().getStudentsByInstitute(instId).collect { students ->
                val upcoming = students.filter { student ->
                    student.dateOfBirthMs?.let { dob ->
                        val calDob = Calendar.getInstance().apply { timeInMillis = dob }
                        val today = Calendar.getInstance()
                        calDob.set(Calendar.YEAR, today.get(Calendar.YEAR))
                        if (calDob.before(today)) {
                            calDob.add(Calendar.YEAR, 1)
                        }
                        val diff = calDob.timeInMillis - today.timeInMillis
                        diff in 0..(30L * 24 * 60 * 60 * 1000)
                    } ?: false
                }.sortedBy { 
                    val calDob = Calendar.getInstance().apply { timeInMillis = it.dateOfBirthMs!! }
                    calDob.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                    calDob.timeInMillis
                }
                _upcomingBirthdays.value = upcoming
            }
        }
    }
}

class BirthdayViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BirthdayViewModel::class.java)) return BirthdayViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
