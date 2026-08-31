package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.models.StudentEntity
import androidx.room.withTransaction
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object StudentSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    private fun studentsCollection(instituteId: String) =
        firestore.collection("institutes").document(instituteId).collection("students")

    suspend fun upsertStudent(student: StudentEntity) {
        withContext(Dispatchers.IO) {
            try {
                upsertStudentOrThrow(student)
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync student to Firestore", permissionDeniedIsExpected = true)
                // Best-effort sync — don't crash local operations
            }
        }
    }

    /**
     * Profile writes run through the trusted backend. This keeps legacy
     * credential fields private while still letting an owner update a student
     * record or photo.
     */
    suspend fun upsertStudentOrThrow(student: StudentEntity) {
        withContext(Dispatchers.IO) {
            functions.getHttpsCallable("updateStudentProfile")
                .call(
                    mapOf(
                        "instituteId" to student.instituteId,
                        "studentId" to student.id,
                        "student" to student.toProfilePayload()
                    )
                )
                .await()
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                studentsCollection(instituteId).forEachDocumentPage { documents ->
                    db.withTransaction {
                        documents
                            .mapNotNull { document -> document.toStudentEntity(instituteId) }
                            .forEach { student -> db.studentDao().insertStudent(student) }
                    }
                }
            } catch (e: Exception) {
                FirebaseFailureReporter.report(e, "sync students from Firestore", permissionDeniedIsExpected = true)
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
                        change.document.toStudentEntity(instituteId)?.let {
                            db.studentDao().insertStudent(it)
                        }

                    DocumentChange.Type.REMOVED ->
                        db.studentDao().deleteStudent(instituteId, change.document.id)
                }
            }
        }
    }

    private fun StudentEntity.toProfilePayload(): Map<String, Any?> = mapOf(
        "studentCode" to studentCode,
        "fullName" to fullName,
        "photoUri" to photoUri,
        "gender" to gender,
        "dateOfBirthMs" to dateOfBirthMs,
        "phone" to phone,
        "email" to email,
        "address" to address,
        "schoolName" to schoolName,
        "className" to className,
        "guardianName" to guardianName,
        "guardianPhone" to guardianPhone,
        "guardianEmail" to guardianEmail,
        "emergencyContact" to emergencyContact,
        "bloodGroup" to bloodGroup,
        "admissionDateMs" to admissionDateMs,
        "status" to status,
        "notes" to notes
    )

    private fun DocumentSnapshot.toStudentEntity(
        instituteId: String
    ): StudentEntity? {
        val fullName = getString("fullName") ?: return null
        val studentCode = getString("studentCode") ?: return null
        return StudentEntity(
            id = id,
            instituteId = getString("instituteId") ?: instituteId,
            studentCode = studentCode,
            fullName = fullName,
            photoUri = getString("photoUri"),
            gender = getString("gender"),
            dateOfBirthMs = getLongCompat("dateOfBirthMs"),
            phone = getString("phone"),
            email = getString("email"),
            address = getString("address"),
            schoolName = getString("schoolName"),
            className = getString("className"),
            guardianName = getString("guardianName"),
            guardianPhone = getString("guardianPhone"),
            guardianEmail = getString("guardianEmail"),
            emergencyContact = getString("emergencyContact"),
            bloodGroup = getString("bloodGroup"),
            admissionDateMs = getLongCompat("admissionDateMs") ?: 0L,
            status = getString("status") ?: "active",
            notes = getString("notes"),
            createdAtMs = getLongCompat("createdAtMs") ?: System.currentTimeMillis(),
            updatedAtMs = getLongCompat("updatedAtMs") ?: System.currentTimeMillis(),
            archivedAtMs = getLongCompat("archivedAtMs"),
            isAppAccessEnabled = getBoolean("isAppAccessEnabled") ?: false
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.getLongCompat(field: String): Long? =
        (get(field) as? Number)?.toLong()
}

