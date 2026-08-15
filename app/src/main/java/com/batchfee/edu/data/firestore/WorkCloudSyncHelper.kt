package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.models.AssignmentEntity
import com.batchfee.edu.data.models.HomeworkEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** The student app reads published work from Firestore, never from the owner device. */
object WorkCloudSyncHelper {
    private fun institute(instituteId: String) = FirebaseFirestore.getInstance()
        .collection("institutes")
        .document(instituteId)

    suspend fun syncHomework(homework: HomeworkEntity) {
        institute(homework.instituteId).collection("homework").document(homework.id).set(
            mapOf(
                "instituteId" to homework.instituteId,
                "batchId" to homework.batchId,
                "title" to homework.title,
                "subject" to homework.subject,
                "className" to homework.className,
                "instructions" to homework.instructions,
                "bookPage" to homework.bookPage,
                "startDateMs" to homework.startDateMs,
                "dueDateMs" to homework.dueDateMs,
                "requiresSubmission" to homework.requiresSubmission,
                "status" to homework.status,
                "createdAtMs" to homework.createdAtMs,
                "updatedAtMs" to homework.updatedAtMs
            )
        ).await()
    }

    suspend fun syncAssignment(assignment: AssignmentEntity) {
        institute(assignment.instituteId).collection("assignments").document(assignment.id).set(
            mapOf(
                "instituteId" to assignment.instituteId,
                "batchId" to assignment.batchId,
                "title" to assignment.title,
                "subject" to assignment.subject,
                "className" to assignment.className,
                "assignmentType" to assignment.assignmentType,
                "instructions" to assignment.instructions,
                "learningObjective" to assignment.learningObjective,
                "totalMarks" to assignment.totalMarks,
                "gradingMethod" to assignment.gradingMethod,
                "startDateMs" to assignment.startDateMs,
                "dueDateMs" to assignment.dueDateMs,
                "allowLateSubmission" to assignment.allowLateSubmission,
                "submissionFormat" to assignment.submissionFormat,
                "status" to assignment.status,
                "publishDateMs" to assignment.publishDateMs,
                "createdAtMs" to assignment.createdAtMs,
                "updatedAtMs" to assignment.updatedAtMs
            )
        ).await()
    }
}
