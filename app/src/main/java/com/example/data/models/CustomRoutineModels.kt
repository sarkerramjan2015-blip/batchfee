package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A custom day-wise class routine created by the institute owner.
 * Independent from batch-based routines — entries live in [CustomRoutineEntryEntity].
 */
@Entity(
    tableName = "custom_routines",
    indices = [androidx.room.Index("instituteId")]
)
data class CustomRoutineEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val routineName: String,
    val className: String,
    val section: String? = null,
    val academicSession: String? = null,
    /** Number of periods per day shown in the routine table (owner-configurable). */
    @androidx.room.ColumnInfo(defaultValue = "7")
    val periodCount: Int = 7,
    val effectiveDateMs: Long? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long? = null
)

/**
 * One class entry inside a custom routine. A day may have zero or many
 * entries — each with its own subject, teacher, start and end time.
 * `dayIndex` follows the week: 0=Saturday … 6=Friday.
 */
@Entity(
    tableName = "custom_routine_entries",
    indices = [
        androidx.room.Index("routineId"),
        androidx.room.Index("instituteId")
    ]
)
data class CustomRoutineEntryEntity(
    @PrimaryKey val id: String,
    val routineId: String,
    val instituteId: String,
    val dayIndex: Int,
    val subjectName: String,
    val teacherName: String,
    val teacherId: String? = null,
    val startMinutes: Int,
    val endMinutes: Int,
    val sortOrder: Int = 0
)
