package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val instituteId: String?, // Nullable only for Super Admin
    val name: String,
    val email: String,
    val passwordHash: String, // In a real app, hash!
    val role: String, // SuperAdmin, InstituteOwner, InstituteAdmin, Teacher, Accountant, Student, Parent, Staff
    val createdAtMs: Long
)
