package com.batchfee.edu.domain

import com.batchfee.edu.data.models.BatchEntity

/** Billing is explicit; legacy documents always fall back to [MONTHLY]. */
object BatchBillingMode {
    const val MONTHLY = "monthly"
    const val COURSE = "course"

    fun normalize(value: String?): String =
        if (value.equals(COURSE, ignoreCase = true)) COURSE else MONTHLY
}

fun BatchEntity.isCourseBatch(): Boolean =
    BatchBillingMode.normalize(billingMode) == BatchBillingMode.COURSE

fun BatchEntity.billingFeeAmount(): Double =
    if (isCourseBatch()) courseFeeAmount else monthlyFeeAmount

fun BatchEntity.billingFeeLabel(): String =
    if (isCourseBatch()) "Course fee" else "Monthly fee"
