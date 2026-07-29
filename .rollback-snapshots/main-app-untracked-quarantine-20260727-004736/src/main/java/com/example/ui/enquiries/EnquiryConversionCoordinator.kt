package com.batchfee.edu.ui.enquiries

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.EnquirySyncHelper
import com.batchfee.edu.data.models.EnquiryEntity
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StaffAuditLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object EnquiryConversionCoordinator {
    fun complete(db: AppDatabase, enquiry: EnquiryEntity, studentId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val updated = enquiry.copy(status = "converted", convertedStudentId = studentId, convertedAtMs = System.currentTimeMillis(), convertedByUserId = SessionManager.currentUserId.value, updatedAtMs = System.currentTimeMillis())
            db.enquiryDao().updateEnquiry(updated); EnquirySyncHelper.upsertEnquiry(updated)
            StaffAuditLogger.record(db, enquiry.instituteId, SessionManager.currentUserId.value, "enquiry_converted", "enquiry", "Converted ${enquiry.name} to student", newValue = studentId)
        }
    }
}
