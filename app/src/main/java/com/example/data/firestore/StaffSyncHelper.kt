package com.example.data.firestore

import com.example.data.models.StaffEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object StaffSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()

    private fun staffPath(instituteId: String, staffId: String) =
        "institutes/$instituteId/staffs/$staffId"

    suspend fun createStaff(staff: StaffEntity) {
        withContext(Dispatchers.IO) {
            try {
                firestore.document(staffPath(staff.instituteId, staff.id)).set(
                    mapOf(
                        "staffCode" to staff.staffCode,
                        "fullName" to staff.fullName,
                        "roleTitle" to staff.roleTitle,
                        "phone" to (staff.phone ?: ""),
                        "email" to (staff.email ?: ""),
                        "address" to (staff.address ?: ""),
                        "joiningDateMs" to (staff.joiningDateMs ?: 0L),
                        "monthlySalary" to staff.monthlySalary,
                        "assignedBatchIds" to (staff.assignedBatchIds ?: ""),
                        "status" to staff.status,
                        "permissions" to (staff.permissions ?: ""),
                        "createdAtMs" to staff.createdAtMs,
                        "updatedAtMs" to staff.updatedAtMs
                    )
                ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun updateStaff(staff: StaffEntity) {
        withContext(Dispatchers.IO) {
            try {
                firestore.document(staffPath(staff.instituteId, staff.id)).update(
                    mapOf(
                        "fullName" to staff.fullName,
                        "roleTitle" to staff.roleTitle,
                        "phone" to (staff.phone ?: ""),
                        "email" to (staff.email ?: ""),
                        "address" to (staff.address ?: ""),
                        "monthlySalary" to staff.monthlySalary,
                        "assignedBatchIds" to (staff.assignedBatchIds ?: ""),
                        "status" to staff.status,
                        "permissions" to (staff.permissions ?: ""),
                        "updatedAtMs" to staff.updatedAtMs
                    )
                ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun archiveStaff(instituteId: String, staffId: String) {
        withContext(Dispatchers.IO) {
            try {
                firestore.document(staffPath(instituteId, staffId)).update(
                    mapOf(
                        "status" to "archived",
                        "updatedAtMs" to System.currentTimeMillis()
                    )
                ).await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    suspend fun syncAllLocalToFirestore(staffList: List<StaffEntity>) {
        staffList.forEach { createStaff(it) }
    }

    data class StaffFirestoreData(
        val staffCode: String = "",
        val fullName: String = "",
        val roleTitle: String = "",
        val phone: String = "",
        val email: String = "",
        val permissions: String = "",
        val assignedBatchIds: String = "",
        val status: String = "active",
        val instituteId: String = ""
    )

    suspend fun fetchStaffFromFirestore(instituteId: String, staffId: String): StaffFirestoreData? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = firestore.document(staffPath(instituteId, staffId)).get().await()
                if (!doc.exists()) return@withContext null
                StaffFirestoreData(
                    staffCode = doc.getString("staffCode") ?: "",
                    fullName = doc.getString("fullName") ?: "",
                    roleTitle = doc.getString("roleTitle") ?: "",
                    phone = doc.getString("phone") ?: "",
                    email = doc.getString("email") ?: "",
                    permissions = doc.getString("permissions") ?: "",
                    assignedBatchIds = doc.getString("assignedBatchIds") ?: "",
                    status = doc.getString("status") ?: "active",
                    instituteId = instituteId
                )
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                null
            }
        }
    }
}
