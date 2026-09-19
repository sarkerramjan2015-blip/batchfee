package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.FinalExamEntity
import com.batchfee.edu.data.models.FinalExamSubjectEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** One row of a final-exam result, as mirrored to the cloud. */
data class FinalResultSyncRow(
    val studentId: String,
    val studentName: String,
    val meritPosition: Int,
    val totalMarks: Double,
    val fullMarks: Double,
    val percentage: Double,
    val gpa: Double,
    val grade: String,
    val passed: Boolean,
    val subjectMarks: Map<String, Double> // subjectId -> totalMarks
)

/**
 * Cloud mirror for final exams (Room stays the source of truth for editing).
 * Published results, per-student docs and immutable version snapshots live in:
 *   institutes/{iid}/final_exams, final_results, final_result_versions
 */
object FinalExamSyncHelper {

    const val MAX_RESULT_ROWS = 400

    suspend fun pushPublishedResults(
        db: AppDatabase,
        exam: FinalExamEntity,
        subjects: List<FinalExamSubjectEntity>,
        rows: List<FinalResultSyncRow>,
        batchName: String,
        version: Int,
        versionDescription: String,
        changedByUserId: String?,
        changedByName: String?
    ) {
        require(exam.status == "published") { "Only published exams are mirrored to the cloud." }
        require(rows.isNotEmpty()) { "No results to publish." }
        require(rows.size <= MAX_RESULT_ROWS) { "Too many students for one publish. Contact support." }
        val subjectMap = subjects.associateBy { it.id }
        val now = System.currentTimeMillis()
        val cloud = FirebaseFirestore.getInstance()
        val instituteRef = cloud.collection("institutes").document(exam.instituteId)
        val batch = cloud.batch()

        batch.set(instituteRef.collection("final_exams").document(exam.id), mapOf(
            "instituteId" to exam.instituteId,
            "examName" to exam.examName,
            "batchId" to exam.batchId,
            "batchName" to batchName,
            "status" to "published",
            "publishedAtMs" to (exam.publishedAtMs ?: now),
            "totalStudents" to rows.size,
            "version" to version,
            "updatedAtMs" to now
        ))

        rows.forEach { row ->
            batch.set(instituteRef.collection("final_results").document("${exam.id}_${row.studentId}"), mapOf(
                "instituteId" to exam.instituteId,
                "finalExamId" to exam.id,
                "examName" to exam.examName,
                "batchId" to exam.batchId,
                "batchName" to batchName,
                "studentId" to row.studentId,
                "totalMarks" to row.totalMarks,
                "fullMarks" to row.fullMarks,
                "percentage" to row.percentage,
                "gpa" to row.gpa,
                "grade" to row.grade,
                "passed" to row.passed,
                "meritPosition" to row.meritPosition,
                "totalStudents" to rows.size,
                "published" to true,
                "publishedAtMs" to (exam.publishedAtMs ?: now),
                "version" to version,
                "subjectMarks" to subjects.mapNotNull { subject ->
                    val obtained = row.subjectMarks[subject.id] ?: return@mapNotNull null
                    mapOf(
                        "subjectId" to subject.id,
                        "name" to subject.subjectName,
                        "fullMarks" to subject.fullMarks,
                        "passMarks" to subject.passMarks,
                        "totalMarks" to obtained,
                        "passed" to (obtained >= subject.passMarks)
                    )
                },
                "updatedAtMs" to now
            ))
        }

        batch.set(instituteRef.collection("final_result_versions").document(versionDocId(exam.id, version)), mapOf(
            "instituteId" to exam.instituteId,
            "examId" to exam.id,
            "examName" to exam.examName,
            "batchName" to batchName,
            "version" to version,
            "createdAtMs" to now,
            "changedByUserId" to changedByUserId,
            "changedByName" to changedByName,
            "description" to versionDescription,
            "subjects" to subjects.map { subject ->
                mapOf(
                    "subjectId" to subject.id,
                    "name" to subject.subjectName,
                    "fullMarks" to subject.fullMarks,
                    "passMarks" to subject.passMarks
                )
            },
            "results" to rows.map { row ->
                mapOf(
                    "studentId" to row.studentId,
                    "studentName" to row.studentName,
                    "meritPosition" to row.meritPosition,
                    "totalMarks" to row.totalMarks,
                    "fullMarks" to row.fullMarks,
                    "percentage" to row.percentage,
                    "gpa" to row.gpa,
                    "grade" to row.grade,
                    "passed" to row.passed,
                    "subjectMarks" to row.subjectMarks.mapValues { (subjectId, obtained) ->
                        mapOf(
                            "totalMarks" to obtained,
                            "passed" to (obtained >= (subjectMap[subjectId]?.passMarks ?: 0.0))
                        )
                    }
                )
            }
        ))

        batch.commit().await()
    }

    /** Hides a published exam from students without touching local marks. */
    suspend fun unpublish(exam: FinalExamEntity, subjects: List<FinalExamSubjectEntity>) {
        val cloud = FirebaseFirestore.getInstance()
        val instituteRef = cloud.collection("institutes").document(exam.instituteId)
        val existing = instituteRef.collection("final_results")
            .whereEqualTo("finalExamId", exam.id)
            .get(Source.SERVER).await()
        val batch = cloud.batch()
        existing.documents.forEach { doc ->
            batch.set(instituteRef.collection("final_results").document(doc.id), mapOf(
                "instituteId" to exam.instituteId,
                "finalExamId" to exam.id,
                "examName" to exam.examName,
                "batchId" to exam.batchId,
                "batchName" to doc.getString("batchName"),
                "studentId" to doc.getString("studentId"),
                "totalMarks" to doc.get("totalMarks"),
                "fullMarks" to doc.get("fullMarks"),
                "percentage" to doc.get("percentage"),
                "gpa" to doc.get("gpa"),
                "grade" to doc.getString("grade"),
                "passed" to doc.getBoolean("passed"),
                "meritPosition" to doc.get("meritPosition"),
                "totalStudents" to doc.get("totalStudents"),
                "published" to false,
                "publishedAtMs" to doc.get("publishedAtMs"),
                "version" to doc.get("version"),
                "subjectMarks" to doc.get("subjectMarks"),
                "updatedAtMs" to System.currentTimeMillis()
            ))
        }
        batch.set(instituteRef.collection("final_exams").document(exam.id), mapOf(
            "instituteId" to exam.instituteId,
            "examName" to exam.examName,
            "batchId" to exam.batchId,
            "batchName" to null,
            "status" to "completed",
            "publishedAtMs" to null,
            "totalStudents" to 0,
            "version" to ((existing.documents.firstOrNull()?.get("version") as? Number)?.toInt() ?: 1),
            "updatedAtMs" to System.currentTimeMillis()
        ))
        batch.commit().await()
    }

    /** Light pull so other admin devices reflect publish/unpublish state. Never merges marks. */
    suspend fun syncPublishedStatusFromCloud(db: AppDatabase, instituteId: String) {
        val cloud = FirebaseFirestore.getInstance()
        val snap = cloud.collection("institutes").document(instituteId)
            .collection("final_exams").get(Source.SERVER).await()
        snap.documents.forEach { doc ->
            val local = db.finalExamDao().getFinalExamOnce(doc.id, instituteId) ?: return@forEach
            val cloudStatus = doc.getString("status")
            if (cloudStatus == "published" && local.status != "published") {
                db.finalExamDao().updateFinalExamStatus(
                    doc.id, instituteId, "published",
                    (doc.get("publishedAtMs") as? Number)?.toLong(), System.currentTimeMillis()
                )
            } else if (cloudStatus != "published" && local.status == "published") {
                db.finalExamDao().updateFinalExamStatus(
                    doc.id, instituteId, "completed", null, System.currentTimeMillis()
                )
            }
        }
    }

    /** Latest version number stored on the cloud exam doc (1 when never published). */
    suspend fun currentVersion(examId: String, instituteId: String): Int {
        val cloud = FirebaseFirestore.getInstance()
        val doc = cloud.collection("institutes").document(instituteId)
            .collection("final_exams").document(examId).get(Source.SERVER).await()
        return (doc.get("version") as? Number)?.toInt() ?: 1
    }

    fun versionDocId(examId: String, version: Int): String = "${examId}_v$version"
}
