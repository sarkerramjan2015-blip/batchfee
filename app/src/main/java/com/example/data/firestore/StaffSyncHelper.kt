package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.models.StaffEntity
import androidx.room.withTransaction
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object StaffSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()

    private fun staffPath(instituteId: String, staffId: String) =
        "institutes/$instituteId/staffs/$staffId"

    private fun staffCollection(instituteId: String) =
        firestore.collection("institutes").document(instituteId).collection("staffs")

    suspend fun createStaff(staff: StaffEntity) {
        withContext(Dispatchers.IO) {
            try {
                firestore.document(staffPath(staff.instituteId, staff.id)).set(
                    mapOf(
                        "staffCode" to staff.staffCode,
                        "fullName" to staff.fullName,
                        "photoUri" to staff.photoUri,
                        "roleTitle" to staff.roleTitle,
                        "phone" to (staff.phone ?: ""),
                        "email" to (staff.email ?: ""),
                        "address" to (staff.address ?: ""),
                        "joiningDateMs" to (staff.joiningDateMs ?: 0L),
                        "monthlySalary" to staff.monthlySalary,
                        "assignedBatchIds" to (staff.assignedBatchIds ?: ""),
                        "status" to staff.status,
                        "notes" to (staff.notes ?: ""),
                        "permissions" to (staff.permissions ?: ""),
                        "createdAtMs" to staff.createdAtMs,
                        "updatedAtMs" to staff.updatedAtMs,
                        "archivedAtMs" to staff.archivedAtMs
                    )
                ).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "create staff", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun updateStaff(staff: StaffEntity) {
        withContext(Dispatchers.IO) {
            try {
                firestore.document(staffPath(staff.instituteId, staff.id)).update(
                    mapOf(
                        "staffCode" to staff.staffCode,
                        "fullName" to staff.fullName,
                        "photoUri" to (staff.photoUri ?: ""),
                        "roleTitle" to staff.roleTitle,
                        "phone" to (staff.phone ?: ""),
                        "email" to (staff.email ?: ""),
                        "address" to (staff.address ?: ""),
                        "joiningDateMs" to (staff.joiningDateMs ?: 0L),
                        "monthlySalary" to staff.monthlySalary,
                        "assignedBatchIds" to (staff.assignedBatchIds ?: ""),
                        "status" to staff.status,
                        "notes" to (staff.notes ?: ""),
                        "permissions" to (staff.permissions ?: ""),
                        "updatedAtMs" to staff.updatedAtMs,
                        "archivedAtMs" to staff.archivedAtMs
                    )
                ).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "update staff", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun archiveStaff(instituteId: String, staffId: String) {
        withContext(Dispatchers.IO) {
            try {
                firestore.document(staffPath(instituteId, staffId)).update(
                    mapOf(
                        "status" to "archived",
                        "archivedAtMs" to System.currentTimeMillis(),
                        "updatedAtMs" to System.currentTimeMillis()
                    )
                ).await()
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync staff status", permissionDeniedIsExpected = true)
            }
        }
    }

    suspend fun syncAllLocalToFirestore(staffList: List<StaffEntity>) {
        staffList.forEach { createStaff(it) }
    }

    data class StaffFirestoreData(
        val staffCode: String = "",
        val fullName: String = "",
        val photoUri: String = "",
        val roleTitle: String = "",
        val phone: String = "",
        val email: String = "",
        val address: String = "",
        val joiningDateMs: Long? = null,
        val monthlySalary: Double = 0.0,
        val permissions: String = "",
        val assignedBatchIds: String = "",
        val status: String = "active",
        val notes: String = "",
        val instituteId: String = "",
        val createdAtMs: Long = 0L,
        val updatedAtMs: Long = 0L,
        val archivedAtMs: Long? = null
    )

    suspend fun fetchStaffFromFirestore(instituteId: String, staffId: String): StaffFirestoreData? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = firestore.document(staffPath(instituteId, staffId)).get().await()
                if (!doc.exists()) return@withContext null
                StaffFirestoreData(
                    staffCode = doc.getString("staffCode") ?: "",
                    fullName = doc.getString("fullName") ?: "",
                    photoUri = doc.getString("photoUri") ?: "",
                    roleTitle = doc.getString("roleTitle") ?: "",
                    phone = doc.getString("phone") ?: "",
                    email = doc.getString("email") ?: "",
                    address = doc.getString("address") ?: "",
                    joiningDateMs = (doc.get("joiningDateMs") as? Number)?.toLong(),
                    monthlySalary = (doc.get("monthlySalary") as? Number)?.toDouble() ?: 0.0,
                    permissions = doc.getString("permissions") ?: "",
                    assignedBatchIds = doc.getString("assignedBatchIds") ?: "",
                    status = doc.getString("status") ?: "active",
                    notes = doc.getString("notes") ?: "",
                    instituteId = instituteId,
                    createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: 0L,
                    updatedAtMs = (doc.get("updatedAtMs") as? Number)?.toLong() ?: 0L,
                    archivedAtMs = (doc.get("archivedAtMs") as? Number)?.toLong()
                )
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync staff to Firestore", permissionDeniedIsExpected = true)
                null
            }
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                staffCollection(instituteId).forEachDocumentPage { documents ->
                    val staff = documents.mapNotNull { doc ->
                    val fullName = doc.getString("fullName") ?: return@mapNotNull null
                    val staffCode = doc.getString("staffCode") ?: return@mapNotNull null
                    StaffEntity(
                        id = doc.id,
                        instituteId = instituteId,
                        staffCode = staffCode,
                        fullName = fullName,
                        photoUri = doc.getString("photoUri")?.takeIf { it.isNotBlank() },
                        roleTitle = doc.getString("roleTitle") ?: "",
                        phone = doc.getString("phone")?.takeIf { it.isNotBlank() },
                        email = doc.getString("email")?.takeIf { it.isNotBlank() },
                        address = doc.getString("address")?.takeIf { it.isNotBlank() },
                        joiningDateMs = (doc.get("joiningDateMs") as? Number)?.toLong()?.takeIf { it > 0L },
                        monthlySalary = (doc.get("monthlySalary") as? Number)?.toDouble() ?: 0.0,
                        assignedBatchIds = doc.getString("assignedBatchIds")?.takeIf { it.isNotBlank() },
                        status = doc.getString("status") ?: "active",
                        notes = doc.getString("notes")?.takeIf { it.isNotBlank() },
                        permissions = doc.getString("permissions")?.takeIf { it.isNotBlank() },
                        createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
                        updatedAtMs = (doc.get("updatedAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
                        archivedAtMs = (doc.get("archivedAtMs") as? Number)?.toLong()
                    )
                    }
                    db.withTransaction {
                        staff.forEach { db.staffDao().insertStaff(it) }
                    }
                }
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync staff from Firestore", permissionDeniedIsExpected = true)
            }
        }
    }

    /** Applies only the documents delivered by an active realtime listener. */
    suspend fun applyRealtimeChanges(
        db: AppDatabase,
        instituteId: String,
        changes: List<DocumentChange>
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            changes.forEach { change ->
                when (change.type) {
                    DocumentChange.Type.ADDED,
                    DocumentChange.Type.MODIFIED ->
                        change.document.toStaffEntity(instituteId)?.let {
                            db.staffDao().insertStaff(it)
                        }

                    DocumentChange.Type.REMOVED ->
                        db.staffDao().deleteStaff(instituteId, change.document.id)
                }
            }
        }
    }

    private fun DocumentSnapshot.toStaffEntity(instituteId: String): StaffEntity? {
        val fullName = getString("fullName") ?: return null
        val staffCode = getString("staffCode") ?: return null
        return StaffEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            staffCode = staffCode,
            fullName = fullName,
            photoUri = getString("photoUri")?.takeIf { it.isNotBlank() },
            roleTitle = getString("roleTitle") ?: "",
            phone = getString("phone")?.takeIf { it.isNotBlank() },
            email = getString("email")?.takeIf { it.isNotBlank() },
            address = getString("address")?.takeIf { it.isNotBlank() },
            joiningDateMs = (get("joiningDateMs") as? Number)?.toLong()?.takeIf { it > 0L },
            monthlySalary = (get("monthlySalary") as? Number)?.toDouble() ?: 0.0,
            assignedBatchIds = getString("assignedBatchIds")?.takeIf { it.isNotBlank() },
            status = getString("status") ?: "active",
            notes = getString("notes")?.takeIf { it.isNotBlank() },
            permissions = getString("permissions")?.takeIf { it.isNotBlank() },
            createdAtMs = (get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            archivedAtMs = (get("archivedAtMs") as? Number)?.toLong()
        )
    }
}

