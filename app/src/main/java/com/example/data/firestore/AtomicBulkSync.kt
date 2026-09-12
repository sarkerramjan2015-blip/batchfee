package com.batchfee.edu.data.firestore

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.AttendanceEntity
import com.batchfee.edu.data.models.ExamEntity
import com.batchfee.edu.data.models.ResultEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** One cloud commit per operation; never silently split an atomic operation into partial chunks. */
internal object AtomicBulkSync {
    const val MAX_WRITES = 400

    internal suspend fun commitThenMirror(commit: suspend () -> Unit, mirror: suspend () -> Unit) {
        commit()
        mirror()
    }

    internal fun validate(tenantIds: List<String>, keys: List<String>, extraWrites: Int = 0) {
        require(tenantIds.isNotEmpty() && tenantIds.first().isNotBlank() && tenantIds.distinct().size == 1) {
            "Select records from one institute."
        }
        require(keys.size == keys.distinct().size) { "Duplicate records in this save. Please refresh and retry." }
        require(keys.size + extraWrites <= MAX_WRITES) {
            "Too many records for one safe save. Select fewer than ${MAX_WRITES - extraWrites + 1} records."
        }
    }

    suspend fun attendance(db: AppDatabase, records: List<AttendanceEntity>) {
        if (records.isEmpty()) return
        validate(records.map { it.instituteId }, records.map { "${it.batchId}|${it.studentId}|${it.attendanceDateMs}" })
        require(records.map { it.id }.distinct().size == records.size)
        val cloud = FirebaseFirestore.getInstance()
        val first = records.first()
        require(records.all { it.batchId == first.batchId && it.attendanceDateMs == first.attendanceDateMs })
        // Resolve legacy random IDs from the server, not a possibly stale UI list.
        val existing = cloud.collection("institutes").document(first.instituteId).collection("attendance")
            .whereEqualTo("batchId", first.batchId).whereEqualTo("attendanceDateMs", first.attendanceDateMs)
            .get(Source.SERVER).await().documents.groupBy { it.getString("studentId") }
        val canonical = records.map { record ->
            val matches = existing[record.studentId].orEmpty()
            require(matches.size <= 1) { "Duplicate attendance history found. Please contact support before editing." }
            val previous = matches.singleOrNull()
            record.copy(
                id = previous?.id ?: java.util.UUID.nameUUIDFromBytes(
                    "${record.instituteId}|${record.batchId}|${record.studentId}|${record.attendanceDateMs}".toByteArray()
                ).toString(),
                createdAtMs = previous?.getLong("createdAtMs") ?: record.createdAtMs
            )
        }
        val batch = cloud.batch()
        canonical.forEach { r ->
            batch.set(cloud.collection("institutes").document(r.instituteId).collection("attendance").document(r.id), mapOf(
                "instituteId" to r.instituteId, "batchId" to r.batchId, "studentId" to r.studentId,
                "attendanceDateMs" to r.attendanceDateMs, "status" to r.status, "note" to r.note,
                "arrivalTimeMs" to r.arrivalTimeMs, "scheduledStartTimeMs" to r.scheduledStartTimeMs,
                "lateByMinutes" to r.lateByMinutes,
                "markedByUserId" to r.markedByUserId, "createdAtMs" to r.createdAtMs, "updatedAtMs" to r.updatedAtMs
            ))
        }
        commitThenMirror({ batch.commit().await(); Unit }, { db.withTransaction { canonical.forEach {
            db.attendanceDao().deleteAttendance(it.instituteId, it.studentId, it.batchId, it.attendanceDateMs)
            db.attendanceDao().insertOrUpdateAttendance(it)
        } } })
    }

    suspend fun results(db: AppDatabase, records: List<ResultEntity>, completedExam: ExamEntity? = null) {
        if (records.isEmpty()) return
        validate(records.map { it.instituteId }, records.map { "${it.examId}|${it.studentId}" }, if (completedExam == null) 0 else 1)
        require(records.map { it.id }.distinct().size == records.size)
        require(records.map { it.examId }.distinct().size == 1) { "Select results from one exam." }
        require(records.all { it.marksObtained.isFinite() }) { "Enter valid marks." }
        completedExam?.let { exam ->
            require(records.all { it.instituteId == exam.instituteId && it.examId == exam.id && it.batchId == exam.batchId })
        }
        val cloud = FirebaseFirestore.getInstance()
        val batch = cloud.batch()
        records.forEach { r ->
            batch.set(cloud.collection("institutes").document(r.instituteId).collection("results").document(r.id), mapOf(
                "instituteId" to r.instituteId, "examId" to r.examId, "batchId" to r.batchId,
                "studentId" to r.studentId, "marksObtained" to r.marksObtained, "grade" to r.grade,
                "position" to r.position, "remarks" to r.remarks, "published" to r.published,
                "createdAtMs" to r.createdAtMs, "updatedAtMs" to r.updatedAtMs
            ))
        }
        completedExam?.let { exam ->
            batch.update(cloud.collection("institutes").document(exam.instituteId).collection("exams").document(exam.id),
                mapOf("status" to exam.status, "updatedAtMs" to exam.updatedAtMs))
        }
        commitThenMirror({ batch.commit().await(); Unit }, { db.withTransaction {
            records.forEach { db.resultDao().insertOrUpdateResult(it) }
            completedExam?.let { db.examDao().updateExam(it) }
        } })
    }
}
