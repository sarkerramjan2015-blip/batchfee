package com.example.data.firestore

import com.example.data.database.AppDatabase
import com.example.data.models.StudentEntity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object StudentSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()

    private fun studentsCollection(instituteId: String) =
        firestore.collection("institutes").document(instituteId).collection("students")

    suspend fun upsertStudent(student: StudentEntity) {
        withContext(Dispatchers.IO) {
            try {
                studentsCollection(student.instituteId)
                    .document(student.id)
                    .set(student.toFirestore())
                    .await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                // Best-effort sync — don't crash local operations
            }
        }
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = studentsCollection(instituteId).get().await()
                snapshot.documents
                    .mapNotNull { document -> document.toStudentEntity(instituteId) }
                    .forEach { student -> db.studentDao().insertStudent(student) }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun StudentEntity.toFirestore(): Map<String, Any?> = mapOf(
        "instituteId" to instituteId,
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
        "notes" to notes,
        "createdAtMs" to createdAtMs,
        "updatedAtMs" to updatedAtMs,
        "archivedAtMs" to archivedAtMs
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toStudentEntity(
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
            archivedAtMs = getLongCompat("archivedAtMs")
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.getLongCompat(field: String): Long? =
        (get(field) as? Number)?.toLong()
}
