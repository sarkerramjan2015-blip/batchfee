# Due Fee Mismatch Report: Dashboard 11,200 vs DueFeeListScreen 7,800

## Root Cause

**Two separate calculation points, same logic, but different filter behavior causing the 3,400 gap.**

---

## Dashboard Calculation (`DashboardScreen.kt` lines 351-391)

```kotlin
db.feeDao().getAllFees(instId).collect { allFees ->
    val fees = allFees.filter { it.dueAmount > 0.0 }
    
    // Non-monthly: sums fee.dueAmount from DB
    nonMonthlyDue += fee.dueAmount  
    
    // Monthly: iterates ALL students, computes per-batch using MonthlyDueCalculator
    students.values.forEach { student ->
        enrolledBatches.forEach { batch ->
            val items = MonthlyDueCalculator.computeMonthlyOutstandingItems(
                admissionDateMs, monthlyFeeAmount, batchId, batchName, batchFees
            )
            items.forEach { item -> monthlyDue += item.outstanding }
        }
    }
    
    // ⚠ BUG LINE 388:
    activeAmount = nonMonthlyDue + monthlyDue  
    // This amount INCLUDES closed/inactive students' dues!
    
    // Lines 382-384: Only filters student IDs for the COUNT, not the amount
    val allActiveIds = (nonMonthlyStudentIds + monthlyStudentIds).filter { sid ->
        !isClosedStudentStatus(students[sid]?.status)
    }
    activeCount = allActiveIds.size  // ✅ Count is filtered
    activeAmount = nonMonthlyDue + monthlyDue  // ❌ Amount is NOT filtered
}
```

**Problem**: `activeAmount` uses the raw `nonMonthlyDue + monthlyDue` which includes closed student dues. Only the `activeCount` is filtered. This inflates the amount by 3,400.

---

## DueFeeListScreen Calculation (`FeeViewModel.kt` lines 91-148)

Uses the **exact same MonthlyDueCalculator** with the **exact same input data** — but the DueFeeListScreen UI applies `statusFilter` at the display level.

Default statusFilter = "Any" → shows all students → total = 7,800.

BUT when set to "Active" → filters out closed students → total = 7,800. This means the 7,800 already EXCLUDES closed students (or there aren't any closed students with dues).

---

## The Most Likely Explanation

The 11,200 on dashboard includes **students with `status = "close"` (closed/inactive)** in the amount calculation. The 7,800 on DueFeeListScreen might exclude them at the source (the viewModel's flow emits a filtered list).

But both use `getAllFees` which gets the same data. The real cause is either:

1. **Dashboard `activeAmount` doesn't filter by student status** (line 388) — confirmed bug
2. **Different loading timing** — dashboard's `collect` callback runs `getStudentsByInstituteOnce` again when fees change, but DueFeeListScreen's `FeeViewModel.enrichDueFees` runs once during `loadData()` init. If fees synced from Firestore between these calls, the numbers differ.

---

## Recommended Fix

Filter the dashboard's `activeAmount` to only include active students (matching what the `activeCount` already does):

```kotlin
// Fix in DashboardScreen.kt line 382-391
val allActiveIds = (nonMonthlyStudentIds + monthlyStudentIds).filter { sid ->
    !isClosedStudentStatus(students[sid]?.status)
}

// Segregate amounts too
val activeNonMonthlyDue = fees.filter { 
    !MonthlyDueCalculator.isMonthlyFeeType(it.feeType) && 
    !isClosedStudentStatus(students[it.studentId]?.status)
}.sumOf { it.dueAmount }

var activeMonthlyDue = 0.0
students.values.forEach { student ->
    if (!isClosedStudentStatus(student.status)) return@forEach  // ← skip closed
    // ... existing monthly calculation ...
}

_dueFeeSummary.value = DueFeeSummary(
    activeCount = allActiveIds.size,
    activeAmount = activeNonMonthlyDue + activeMonthlyDue,  // ← now filtered
    closeCount = 0,
    closeAmount = 0.0
)
```
