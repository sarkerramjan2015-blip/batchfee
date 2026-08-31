package com.batchfee.edu.data.repository

import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.StudentEntity
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Creates quota-controlled records through trusted App Check-protected Functions. */
class EntitledCreationRepository {
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)

    data class StudentCreationResult(
        val studentCode: String,
        val photoUri: String?
    )

    suspend fun createStudent(
        student: StudentEntity,
        registrationRequestId: String? = null
    ): StudentCreationResult {
        val payload = mutableMapOf<String, Any?>(
            "instituteId" to student.instituteId,
            "studentId" to student.id,
            "student" to studentPayload(student)
        )
        registrationRequestId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            payload["registrationRequestId"] = it
        }
        // Keep the same student/request IDs when a stale ID token forces one
        // retry. The backend treats registrationRequestId as an idempotency
        // key, so a lost success response cannot create a second student.
        val data = callTrusted("createEntitledStudent", payload) as? Map<*, *>
        val savedStudentCode = (data?.get("studentCode") as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: student.studentCode
        return StudentCreationResult(
            studentCode = savedStudentCode,
            photoUri = (data?.get("photoUri") as? String)?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun createBatch(batch: BatchEntity) {
        val operationId = UUID.randomUUID().toString()
        callTrusted(
            "createEntitledBatch",
            mapOf(
                "operationId" to operationId,
                "instituteId" to batch.instituteId,
                "batchId" to batch.id,
                "batch" to batchPayload(batch)
            )
        )
    }

    suspend fun createStaff(staff: StaffEntity) {
        val operationId = UUID.randomUUID().toString()
        callTrusted(
            "createEntitledStaff",
            mapOf(
                "operationId" to operationId,
                "instituteId" to staff.instituteId,
                "staffId" to staff.id,
                "staff" to staffPayload(staff)
            )
        )
    }

    suspend fun provisionStaff(staff: StaffEntity, password: String): String {
        val operationId = UUID.randomUUID().toString()
        val response = callTrusted(
            "provisionStaffAccount",
            mapOf(
                "operationId" to operationId,
                "instituteId" to staff.instituteId,
                "staff" to staffPayload(staff),
                "password" to password
            )
        )
        val data = response as? Map<*, *>
        return (data?.get("staffId") as? String)?.takeIf { it.isNotBlank() }
            ?: error("Trusted staff service returned an invalid account ID.")
    }

    suspend fun updateStaff(staff: StaffEntity, password: String? = null) {
        val operationId = UUID.randomUUID().toString()
        callTrusted(
            "updateStaffAccount",
            mapOf(
                "operationId" to operationId,
                "instituteId" to staff.instituteId,
                "staffId" to staff.id,
                "staff" to staffPayload(staff),
                "password" to password?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
    }

    private suspend fun callTrusted(functionName: String, payload: Map<String, Any?>): Any? {
        return callTrustedFunction(functions, functionName, payload)
    }

    private fun studentPayload(s: StudentEntity) = mapOf(
        "studentCode" to s.studentCode, "fullName" to s.fullName, "photoUri" to s.photoUri,
        "gender" to s.gender, "dateOfBirthMs" to s.dateOfBirthMs, "phone" to s.phone,
        "email" to s.email, "address" to s.address, "schoolName" to s.schoolName,
        "className" to s.className, "guardianName" to s.guardianName,
        "guardianPhone" to s.guardianPhone, "guardianEmail" to s.guardianEmail,
        "emergencyContact" to s.emergencyContact, "bloodGroup" to s.bloodGroup,
        "admissionDateMs" to s.admissionDateMs, "notes" to s.notes
    )

    private fun batchPayload(b: BatchEntity) = mapOf(
        "batchCode" to b.batchCode, "name" to b.name, "subject" to b.subject,
        "className" to b.className, "teacherName" to b.teacherName,
        "monthlyFeeAmount" to b.monthlyFeeAmount, "admissionFeeAmount" to b.admissionFeeAmount,
        "startDateMs" to b.startDateMs, "endDateMs" to b.endDateMs,
        "scheduleDays" to b.scheduleDays, "startTime" to b.startTime, "endTime" to b.endTime,
        "maxStudents" to b.maxStudents, "description" to b.description
    )

    private fun staffPayload(s: StaffEntity) = mapOf(
        "staffCode" to s.staffCode, "fullName" to s.fullName, "photoUri" to s.photoUri,
        "roleTitle" to s.roleTitle, "phone" to s.phone, "email" to s.email,
        "address" to s.address, "joiningDateMs" to s.joiningDateMs,
        "monthlySalary" to s.monthlySalary, "assignedBatchIds" to s.assignedBatchIds,
        "notes" to s.notes, "permissions" to s.permissions
    )
}
