package com.batchfee.edu.data.repository

import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class StudentDataRepository {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun fetchStudent(studentId: String, instituteId: String): StudentEntity? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = firestore.collection("institutes").document(instituteId)
                    .collection("students").document(studentId).get().await()
                doc.toStudentCacheEntity(instituteId)
            } catch (_: Exception) { null }
        }
    }

    suspend fun fetchInstitute(instituteId: String): InstituteEntity? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = firestore.collection("institutes").document(instituteId).get().await()
                InstituteEntity(
                    id = doc.id, name = doc.getString("name") ?: "Institute",
                    currentPlanId = "", subscriptionStatus = "",
                    trialStartDateMs = 0L, trialEndDateMs = 0L, currentPeriodEndMs = 0L, createdAtMs = 0L,
                    phone = doc.getString("phone"), address = doc.getString("address"),
                    profilePhotoUri = doc.getString("profilePhotoUri"), email = doc.getString("email"),
                    instituteCode = doc.getString("instituteCode")
                )
            } catch (_: Exception) { null }
        }
    }

    suspend fun fetchFees(instituteId: String, studentId: String): List<FeeInfo> {
        return withContext(Dispatchers.IO) {
            try {
                // The main app writes feePeriod (not monthYear/description) and
                // marks mistaken-enrollment fees cancelled. Mirroring those rows
                // unfiltered previously surfaced cancelled charges to the student.
                val snapshot = firestore.collection("institutes").document(instituteId)
                    .collection("fees")
                    .whereEqualTo("studentId", studentId)
                    .whereEqualTo("cancelledAtMs", null)
                    .get().await()
                snapshot.documents.map { doc ->
                    val period = doc.getString("feePeriod") ?: doc.getString("monthYear")
                    FeeInfo(
                        id = doc.id,
                        description = period ?: doc.getString("feeType") ?: "Fee",
                        totalAmount = doc.getDouble("totalAmount") ?: 0.0,
                        paidAmount = doc.getDouble("paidAmount") ?: 0.0,
                        status = doc.getString("status") ?: "pending",
                        monthYear = period
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    suspend fun fetchAttendance(instituteId: String, studentId: String): List<AttendanceInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = firestore.collection("institutes").document(instituteId)
                    .collection("attendance").whereEqualTo("studentId", studentId).get().await()
                snapshot.documents.map { doc ->
                    AttendanceInfo(
                        id = doc.id,
                        attendanceDateMs = doc.getLong("attendanceDateMs") ?: 0L,
                        status = doc.getString("status") ?: "absent"
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    suspend fun fetchResults(instituteId: String, studentId: String): List<ResultInfo> {
        return withContext(Dispatchers.IO) {
            try {
                // The main app writes marksObtained/position/published on the
                // result document and stores exam names/totals on the exam
                // document. Only published results may reach the student.
                val snapshot = firestore.collection("institutes").document(instituteId)
                    .collection("results")
                    .whereEqualTo("studentId", studentId)
                    .whereEqualTo("published", true)
                    .get().await()
                val examIds = snapshot.documents.mapNotNull { it.getString("examId") }.distinct()
                val examDocs = examIds.mapNotNull { examId ->
                    val exam = runCatching {
                        firestore.collection("institutes").document(instituteId)
                            .collection("exams").document(examId).get().await()
                    }.getOrNull()
                    if (exam != null && exam.exists()) examId to exam else null
                }.toMap()
                snapshot.documents.map { doc ->
                    val exam = doc.getString("examId")?.let { examDocs[it] }
                    val obtained = (doc.get("marksObtained") as? Number)?.toDouble() ?: 0.0
                    val total = (exam?.get("totalMarks") as? Number)?.toDouble()
                        ?: (doc.get("totalMarks") as? Number)?.toDouble()
                        ?: 100.0
                    ResultInfo(
                        id = doc.id,
                        examName = exam?.getString("examName") ?: "Exam",
                        examDateMs = (exam?.get("examDateMs") as? Number)?.toLong(),
                        subject = exam?.getString("subject"),
                        obtainedMarks = obtained,
                        totalMarks = total,
                        grade = doc.getString("grade"),
                        percentage = if (total > 0) (obtained / total) * 100 else 0.0,
                        rank = (doc.get("position") as? Number)?.toInt(),
                        totalStudents = null
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toStudentCacheEntity(instituteId: String): StudentEntity {
        return StudentEntity(
            id = id, instituteId = instituteId,
            studentCode = getString("studentCode") ?: "",
            fullName = getString("fullName") ?: "Student",
            photoUri = getString("photoUri"), gender = getString("gender"),
            dateOfBirthMs = (get("dateOfBirthMs") as? Number)?.toLong(),
            phone = getString("phone"), email = getString("email"),
            address = getString("address"), schoolName = getString("schoolName"),
            className = getString("className"), guardianName = getString("guardianName"),
            guardianPhone = getString("guardianPhone"), guardianEmail = getString("guardianEmail"),
            emergencyContact = getString("emergencyContact"),
            bloodGroup = getString("bloodGroup"),
            admissionDateMs = (get("admissionDateMs") as? Number)?.toLong() ?: 0L,
            status = getString("status") ?: "active", notes = getString("notes"),
            createdAtMs = (get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            archivedAtMs = (get("archivedAtMs") as? Number)?.toLong()
        )
    }
}

data class FeeInfo(val id: String, val description: String, val totalAmount: Double, val paidAmount: Double, val status: String, val monthYear: String?)
data class AttendanceInfo(val id: String, val attendanceDateMs: Long, val status: String)
data class ResultInfo(val id: String, val examName: String, val examDateMs: Long?, val subject: String?, val obtainedMarks: Double, val totalMarks: Double, val grade: String?, val percentage: Double, val rank: Int?, val totalStudents: Int?)
