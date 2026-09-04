package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.batchfee.edu.data.models.FinalExamEntity
import com.batchfee.edu.data.models.FinalExamMarksEntity
import com.batchfee.edu.data.models.FinalExamSubjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FinalExamDao {

    // ── Final exams ─────────────────────────────────────────────
    @Query("SELECT * FROM final_exams WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getFinalExams(instituteId: String): Flow<List<FinalExamEntity>>

    @Query("SELECT * FROM final_exams WHERE id = :examId AND instituteId = :instituteId LIMIT 1")
    fun getFinalExam(examId: String, instituteId: String): Flow<FinalExamEntity?>

    @Query("SELECT * FROM final_exams WHERE id = :examId AND instituteId = :instituteId LIMIT 1")
    suspend fun getFinalExamOnce(examId: String, instituteId: String): FinalExamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFinalExam(exam: FinalExamEntity)

    @Query("UPDATE final_exams SET status = :status, publishedAtMs = :publishedAtMs, updatedAtMs = :updatedAtMs WHERE id = :examId AND instituteId = :instituteId")
    suspend fun updateFinalExamStatus(examId: String, instituteId: String, status: String, publishedAtMs: Long?, updatedAtMs: Long)

    @Query("UPDATE final_exams SET archivedAtMs = :archivedAtMs, updatedAtMs = :updatedAtMs WHERE id = :examId AND instituteId = :instituteId")
    suspend fun archiveFinalExam(examId: String, instituteId: String, archivedAtMs: Long, updatedAtMs: Long)

    // ── Subjects ────────────────────────────────────────────────
    @Query("SELECT * FROM final_exam_subjects WHERE finalExamId = :finalExamId ORDER BY sortOrder ASC")
    fun getSubjects(finalExamId: String): Flow<List<FinalExamSubjectEntity>>

    @Query("SELECT * FROM final_exam_subjects WHERE finalExamId = :finalExamId ORDER BY sortOrder ASC")
    suspend fun getSubjectsOnce(finalExamId: String): List<FinalExamSubjectEntity>

    @Query("SELECT COUNT(*) FROM final_exam_subjects WHERE finalExamId = :finalExamId")
    fun getSubjectCount(finalExamId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubject(subject: FinalExamSubjectEntity)

    @Query("DELETE FROM final_exam_subjects WHERE finalExamId = :finalExamId AND id NOT IN (:keepIds)")
    suspend fun deleteSubjectsNotIn(finalExamId: String, keepIds: List<String>)

    @Query("DELETE FROM final_exam_subjects WHERE finalExamId = :finalExamId")
    suspend fun deleteAllSubjects(finalExamId: String)

    @Query("SELECT * FROM final_exam_subjects WHERE assignedStaffId = :staffId")
    suspend fun getSubjectsForStaff(staffId: String): List<FinalExamSubjectEntity>

    // ── Marks ───────────────────────────────────────────────────
    @Query("SELECT * FROM final_exam_marks WHERE finalExamId = :finalExamId")
    fun getMarks(finalExamId: String): Flow<List<FinalExamMarksEntity>>

    @Query("SELECT * FROM final_exam_marks WHERE finalExamId = :finalExamId")
    suspend fun getMarksOnce(finalExamId: String): List<FinalExamMarksEntity>

    @Query("SELECT * FROM final_exam_marks WHERE subjectId = :subjectId")
    fun getMarksForSubject(subjectId: String): Flow<List<FinalExamMarksEntity>>

    @Query("SELECT * FROM final_exam_marks WHERE subjectId = :subjectId AND studentId = :studentId LIMIT 1")
    suspend fun getMarksForStudentOnce(subjectId: String, studentId: String): FinalExamMarksEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarks(marks: FinalExamMarksEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllMarks(marks: List<FinalExamMarksEntity>)

    @Query("UPDATE final_exam_marks SET status = :status, updatedAtMs = :updatedAtMs WHERE subjectId = :subjectId AND status != 'approved'")
    suspend fun updateMarksStatusForSubject(subjectId: String, status: String, updatedAtMs: Long)

    @Query("SELECT COUNT(*) FROM final_exam_marks WHERE finalExamId = :finalExamId AND status != 'approved'")
    suspend fun countUnapprovedMarks(finalExamId: String): Int

    @Query("SELECT COUNT(*) FROM final_exam_marks WHERE finalExamId = :finalExamId")
    suspend fun countMarks(finalExamId: String): Int

    @Query("SELECT COUNT(*) FROM final_exam_marks WHERE finalExamId = :finalExamId AND status = 'approved'")
    suspend fun countApprovedMarks(finalExamId: String): Int

    @Query("SELECT COUNT(DISTINCT subjectId) FROM final_exam_marks WHERE finalExamId = :finalExamId AND status = 'approved'")
    suspend fun countApprovedSubjects(finalExamId: String): Int
}
