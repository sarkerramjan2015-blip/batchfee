package com.batchfee.edu.data.repository

import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.StudentEntity
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/** Creates quota-controlled records through trusted App Check-protected Functions. */
class EntitledCreationRepository {
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)

    suspend fun createStudent(student: StudentEntity) {
        functions.getHttpsCallable("createEntitledStudent").call(
            mapOf("instituteId" to student.instituteId, "studentId" to student.id, "student" to studentPayload(student))
        ).await()
    }

    suspend fun createBatch(batch: BatchEntity) {
        functions.getHttpsCallable("createEntitledBatch").call(
            mapOf("instituteId" to batch.instituteId, "batchId" to batch.id, "batch" to batchPayload(batch))
        ).await()
    }

    suspend fun createStaff(staff: StaffEntity) {
        functions.getHttpsCallable("createEntitledStaff").call(
            mapOf("instituteId" to staff.instituteId, "staffId" to staff.id, "staff" to staffPayload(staff))
        ).await()
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
