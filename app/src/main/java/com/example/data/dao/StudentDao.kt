package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.StudentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM students WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY fullName ASC")
    fun getStudentsByInstitute(instituteId: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE instituteId = :instituteId AND archivedAtMs IS NOT NULL ORDER BY archivedAtMs DESC")
    fun getArchivedStudentsByInstitute(instituteId: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY fullName ASC")
    suspend fun getStudentsByInstituteOnce(instituteId: String): List<StudentEntity>

    @Query("SELECT * FROM students WHERE id = :studentId AND instituteId = :instituteId LIMIT 1")
    fun getStudentById(studentId: String, instituteId: String): Flow<StudentEntity?>

    @Query("SELECT COUNT(*) FROM students WHERE instituteId = :instituteId AND archivedAtMs IS NULL")
    fun countStudents(instituteId: String): Flow<Int>

    /**
     * Operational screens must only count students who can currently attend
     * classes. Historic inactive/closed students are kept for audit and fee
     * history, but must not make the Home snapshot or attendance total larger
     * than the default Active student list.
     */
    @Query(
        """
        SELECT COUNT(*) FROM students
        WHERE instituteId = :instituteId
          AND archivedAtMs IS NULL
          AND (
            status IS NULL OR LOWER(TRIM(status)) NOT IN ('inactive', 'close', 'closed', 'archived')
          )
        """
    )
    fun countActiveStudents(instituteId: String): Flow<Int>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM students
            WHERE instituteId = :instituteId
              AND UPPER(TRIM(studentCode)) = UPPER(TRIM(:studentCode))
              AND id != :excludingStudentId
            LIMIT 1
        )
        """
    )
    suspend fun isStudentCodeInUse(
        instituteId: String,
        studentCode: String,
        excludingStudentId: String = ""
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: StudentEntity)

    @Update
    suspend fun updateStudent(student: StudentEntity)

    @Query("DELETE FROM students WHERE instituteId = :instituteId AND id = :studentId")
    suspend fun deleteStudent(instituteId: String, studentId: String)

}
