package com.batchfee.edu.domain

import com.batchfee.edu.data.models.EnquiryEntity
import kotlinx.coroutines.flow.MutableStateFlow

/** One-navigation handoff; never persisted or used to auto-create a student. */
object EnquiryConversionDraft {
    val pending = MutableStateFlow<EnquiryEntity?>(null)
}
