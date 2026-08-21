package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "salaries")
data class SalaryEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val staffId: String,
    val salaryMonth: String,
    val basicSalary: Double,
    val bonusAmount: Double,
    val deductionAmount: Double,
    val advanceAmount: Double,
    val netSalary: Double,
    /** Total cash actually paid so far. It keeps partial salary payments auditable. */
    val paidAmount: Double = 0.0,
    val paymentMethod: String?,
    val paymentDateMs: Long?,
    val status: String,
    val salarySlipNumber: String,
    val note: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val cancelledAtMs: Long?
)

