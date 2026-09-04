package com.batchfee.edu.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.SalarySyncHelper
import com.batchfee.edu.data.firestore.TeachingSessionSyncHelper
import com.batchfee.edu.data.models.ExpenseEntity
import com.batchfee.edu.data.models.SalaryEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Calendar
import java.nio.charset.StandardCharsets

class SalaryViewModel(private val db: AppDatabase) : ViewModel() {
    data class TeacherPayPreview(val sessionCount: Int = 0, val amount: Double = 0.0)
    private val _salaries = MutableStateFlow<List<SalaryEntity>>(emptyList())
    val salaries = _salaries.asStateFlow()

    private val _activeStaff = MutableStateFlow<List<StaffEntity>>(emptyList())
    val activeStaff = _activeStaff.asStateFlow()

    private val _teacherPayPreview = MutableStateFlow(TeacherPayPreview())
    val teacherPayPreview = _teacherPayPreview.asStateFlow()

    // A salary receipt must never be saved twice when the user taps the
    // payment button again while the first cloud request is still running.
    private val paymentsInProgress = mutableSetOf<String>()
    private val salaryMutationsInProgress = mutableSetOf<String>()

    /**
     * Every newly generated salary has the same id for one institute, staff
     * member and salary month. This lets the Firestore transaction reject a
     * second tap (or a second device) before it can create another salary.
     */
    private fun salaryIdFor(instituteId: String, staffId: String, salaryMonth: String): String =
        "salary-" + UUID.nameUUIDFromBytes(
            "$instituteId|$staffId|$salaryMonth".toByteArray(StandardCharsets.UTF_8)
        ).toString()

    init {
        loadData()
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.salaryDao().getSalariesByInstitute(instId).collect { list ->
                _salaries.value = list
            }
        }
        viewModelScope.launch {
            db.staffDao().getActiveStaff(instId).collect { list ->
                _activeStaff.value = list
            }
        }
        // Earlier releases marked a salary as paid without creating the
        // matching institute expense. Repair those records once, using a
        // deterministic expense ID so this can never duplicate an expense.
        if (SessionManager.isAdmin()) {
            viewModelScope.launch { reconcilePaidSalaryExpenses(instId) }
        }
    }

    private fun salaryExpenseId(salaryId: String) = "salary-expense-$salaryId"

    fun loadTeacherPayPreview(staffId: String?, salaryMonth: String) {
        if (staffId == null) {
            _teacherPayPreview.value = TeacherPayPreview()
            return
        }
        val instituteId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            val staff = db.staffDao().getStaffByIdOnce(staffId, instituteId)
            val bounds = salaryMonthBounds(salaryMonth)
            if (staff == null || staff.salaryType == "monthly" || bounds == null) {
                _teacherPayPreview.value = TeacherPayPreview()
                return@launch
            }
            val sessions = db.teachingSessionDao().getUnpaidSessionsForPeriod(
                instituteId, staffId, bounds.first, bounds.second
            )
            _teacherPayPreview.value = TeacherPayPreview(
                sessionCount = sessions.size,
                amount = sessions.sumOf { it.calculatedAmount }
            )
        }
    }

    private fun buildSalaryExpense(
        salary: SalaryEntity,
        staff: StaffEntity?,
        userId: String,
        previous: ExpenseEntity? = null,
    ): ExpenseEntity {
        val now = System.currentTimeMillis()
        val staffName = staff?.fullName?.takeIf { it.isNotBlank() } ?: "Staff"
        val paid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
        val due = (salary.netSalary - paid).coerceAtLeast(0.0)
        return ExpenseEntity(
            id = salaryExpenseId(salary.id),
            instituteId = salary.instituteId,
            category = "Staff Salary",
            title = "Staff salary · $staffName · ${salary.salaryMonth}",
            amount = salary.netSalary.coerceAtLeast(0.0),
            expenseDateMs = previous?.expenseDateMs ?: salary.createdAtMs,
            paymentMethod = salary.paymentMethod,
            description = "Salary slip ${salary.salarySlipNumber} | Paid BDT ${paid.toLong()} | Due BDT ${due.toLong()}",
            attachmentUri = null,
            createdByUserId = previous?.createdByUserId ?: userId,
            createdAtMs = previous?.createdAtMs ?: now,
            updatedAtMs = now,
            archivedAtMs = null,
        )
    }

    private suspend fun reconcilePaidSalaryExpenses(instId: String) {
        val userId = SessionManager.currentUserId.value ?: return
        val salaries = db.salaryDao().getSalariesByInstitute(instId).firstOrNull().orEmpty()
        salaries.filter { it.cancelledAtMs == null }.forEach { salary ->
            val expenseId = salaryExpenseId(salary.id)
            if (db.expenseDao().getExpenseById(expenseId, instId) != null) return@forEach
            val staff = db.staffDao().getStaffByIdOnce(salary.staffId, instId)
            val expense = buildSalaryExpense(salary, staff, userId)
            runCatching {
                SalarySyncHelper.upsertSalaryWithExpense(salary, expense)
                db.withTransaction {
                    db.salaryDao().updateSalary(salary)
                    db.expenseDao().insertExpense(expense)
                }
            }
        }
    }

    fun generateSalary(
        staffId: String,
        salaryMonth: String,
        basicSalary: Double,
        bonusAmount: Double,
        deductionAmount: Double,
        advanceAmount: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run {
            onError("No active institute session.")
            return
        }
        val userId = SessionManager.currentUserId.value ?: run {
            onError("No user session found. Please sign in again.")
            return
        }
        val mutationKey = "$instId:$staffId:$salaryMonth"
        if (!synchronized(salaryMutationsInProgress) { salaryMutationsInProgress.add(mutationKey) }) {
            onError("Salary generation is already in progress for this staff and month.")
            return
        }

        viewModelScope.launch {
            try {
                val existing = db.salaryDao().countByStaffAndMonth(staffId, salaryMonth, instId)
                if (existing > 0) {
                    onError("Salary already exists for this staff in $salaryMonth.")
                    return@launch
                }

                val staff = db.staffDao().getStaffByIdOnce(staffId, instId) ?: run {
                    onError("The selected staff profile no longer exists.")
                    return@launch
                }
                val (fromMs, untilMs) = salaryMonthBounds(salaryMonth) ?: run {
                    onError("Choose a valid salary month.")
                    return@launch
                }
                val payableSessions = if (staff.salaryType == "monthly") {
                    emptyList()
                } else {
                    db.teachingSessionDao().getUnpaidSessionsForPeriod(instId, staffId, fromMs, untilMs)
                }
                val calculatedBasic = when (staff.salaryType) {
                    "per_class", "per_hour" -> payableSessions.sumOf { it.calculatedAmount }
                    else -> basicSalary
                }
                val net = calculatedBasic + bonusAmount - (deductionAmount + advanceAmount)
                if (calculatedBasic <= 0) {
                    val label = if (staff.salaryType == "monthly") "Basic salary" else "Completed class amount"
                    onError("$label must be greater than zero.")
                    return@launch
                }
                if (net < 0) { onError("Net salary cannot be negative."); return@launch }

                val entity = SalaryEntity(
                    id = salaryIdFor(instId, staffId, salaryMonth),
                    instituteId = instId,
                    staffId = staffId,
                    salaryMonth = salaryMonth,
                    basicSalary = calculatedBasic,
                    bonusAmount = bonusAmount,
                    deductionAmount = deductionAmount,
                    advanceAmount = advanceAmount,
                    netSalary = net,
                    paidAmount = 0.0,
                    paymentMethod = null,
                    paymentDateMs = null,
                    status = "unpaid",
                    salarySlipNumber = "SLP-${UUID.randomUUID().toString().take(8)}",
                    note = null,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                    cancelledAtMs = null,
                    calculationType = staff.salaryType,
                    calculationSessionIds = payableSessions.joinToString(",") { it.id }.takeIf { it.isNotBlank() }
                )
                val expense = buildSalaryExpense(entity, staff, userId)
                val lockedSessions = payableSessions.map {
                    it.copy(salaryId = entity.id, updatedAtMs = System.currentTimeMillis())
                }
                TeachingSessionSyncHelper.upsertSalaryWithExpenseAndSessions(entity, expense, lockedSessions)
                db.withTransaction {
                    db.salaryDao().insertSalary(entity)
                    db.expenseDao().insertExpense(expense)
                    if (lockedSessions.isNotEmpty()) db.teachingSessionDao().insertSessions(lockedSessions)
                }
                StaffActivityLogger.logCompletedAction(
                    db, "salary_generated", "salary", "Generated salary for $salaryMonth"
                )
                onSuccess()
            } catch (error: IllegalStateException) {
                onError(error.message ?: "Salary could not be generated. Refresh and try again.")
            } catch (_: Exception) {
                onError("Could not generate salary. Check your connection and try again.")
            } finally {
                synchronized(salaryMutationsInProgress) { salaryMutationsInProgress.remove(mutationKey) }
            }
        }
    }

    fun cancelSalary(
        salaryId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            val salary = db.salaryDao().getSalaryById(salaryId, instId) ?: return@launch
            if (salary.paidAmount > 0.0) {
                onError("A paid or partial salary cannot be cancelled. Keep its payment record for accurate expenses.")
                return@launch
            }
            val now = System.currentTimeMillis()
            val updated = salary.copy(cancelledAtMs = now, updatedAtMs = now)
            try {
                val staff = db.staffDao().getStaffByIdOnce(salary.staffId, instId)
                val existingExpense = db.expenseDao().getExpenseById(salaryExpenseId(salary.id), instId)
                val archivedExpense = buildSalaryExpense(
                    updated,
                    staff,
                    SessionManager.currentUserId.value ?: "system",
                    existingExpense,
                ).copy(archivedAtMs = now, updatedAtMs = now)
                val unlockedSessions = db.teachingSessionDao().getSessionsForSalary(instId, salaryId)
                    .map { it.copy(salaryId = null, updatedAtMs = now) }
                TeachingSessionSyncHelper.upsertSalaryWithExpenseAndSessions(updated, archivedExpense, unlockedSessions)
                db.withTransaction {
                    db.salaryDao().updateSalary(updated)
                    db.expenseDao().insertExpense(archivedExpense)
                    if (unlockedSessions.isNotEmpty()) db.teachingSessionDao().insertSessions(unlockedSessions)
                }
                StaffActivityLogger.logCompletedAction(
                    db, "salary_cancelled", "salary", "Cancelled a salary record"
                )
                onSuccess()
            } catch (_: Exception) {
                onError("Could not cancel salary. Check your connection and try again.")
            }
        }
    }

    fun recordPayment(
        salaryId: String,
        amount: Double,
        paymentMethod: String,
        note: String?,
        onSuccess: (SalaryEntity) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: run {
            onError("No user session found. Please sign in again.")
            return
        }
        if (amount <= 0.0) {
            onError("Payment amount must be greater than zero.")
            return
        }
        if (!paymentsInProgress.add(salaryId)) {
            onError("This payment is already being saved.")
            return
        }
        viewModelScope.launch {
            try {
                val salary = db.salaryDao().getSalaryById(salaryId, instId)
                if (salary == null || salary.cancelledAtMs != null) {
                    onError("This salary record is no longer available.")
                    return@launch
                }
                val alreadyPaid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
                val due = (salary.netSalary - alreadyPaid).coerceAtLeast(0.0)
                if (due <= 0.009) {
                    onError("This salary is already fully paid.")
                    return@launch
                }
                if (amount > due + 0.009) {
                    onError("Payment cannot be more than the remaining due: BDT ${due.toLong()}.")
                    return@launch
                }
                val now = System.currentTimeMillis()
                val paidToDate = (alreadyPaid + amount).coerceAtMost(salary.netSalary)
                val remainingDue = (salary.netSalary - paidToDate).coerceAtLeast(0.0)
                val updated = salary.copy(
                    paidAmount = paidToDate,
                    status = if (remainingDue <= 0.009) "paid" else "partial",
                    paymentMethod = paymentMethod,
                    paymentDateMs = now,
                    note = note?.trim()?.takeIf { it.isNotBlank() } ?: salary.note,
                    updatedAtMs = now,
                )
                val staff = db.staffDao().getStaffByIdOnce(salary.staffId, instId)
                val previousExpense = db.expenseDao().getExpenseById(salaryExpenseId(salary.id), instId)
                val expense = buildSalaryExpense(updated, staff, userId, previousExpense)
                // Cloud batch first, then a local Room transaction: the salary state and
                // institute expense always advance together.
                SalarySyncHelper.upsertSalaryWithExpense(updated, expense)
                db.withTransaction {
                    db.salaryDao().updateSalary(updated)
                    db.expenseDao().insertExpense(expense)
                }
                StaffActivityLogger.logCompletedAction(
                    db,
                    "salary_payment_recorded",
                    "salary",
                    "Recorded BDT ${amount.toLong()} salary payment by $paymentMethod",
                )
                onSuccess(updated)
            } catch (_: Exception) {
                onError("Could not record salary payment. Check your connection and try again.")
            } finally {
                paymentsInProgress.remove(salaryId)
            }
        }
    }
}

private fun salaryMonthBounds(value: String): Pair<Long, Long>? = runCatching {
    val parts = value.trim().split("-")
    val year = parts[0].toInt()
    val month = parts[1].toInt()
    require(month in 1..12)
    val start = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1, 0, 0, 0)
    }
    val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
    start.timeInMillis to end.timeInMillis
}.getOrNull()

class SalaryViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SalaryViewModel::class.java)) return SalaryViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

