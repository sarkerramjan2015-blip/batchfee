package com.batchfee.edu.domain

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object InstituteCodeGenerator {

    private val firestore = FirebaseFirestore.getInstance()

    suspend fun generateCode(prefix: String = "BGS"): String {
        return withContext(Dispatchers.IO) {
            val existingCodes = try {
                firestore.collection("institutes").get().await()
                    .documents
                    .mapNotNull { it.getString("instituteCode") }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }

            var counter = 100
            while (counter < 9999) {
                val code = "$prefix-$counter"
                if (code !in existingCodes) return@withContext code
                counter++
            }
            "$prefix-${System.currentTimeMillis().toString().takeLast(4)}"
        }
    }

    suspend fun generateCode(preferredPrefix: String, existingInstitutes: List<String>): String {
        return withContext(Dispatchers.IO) {
            val existingCodes = try {
                firestore.collection("institutes").get().await()
                    .documents
                    .mapNotNull { it.getString("instituteCode") }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }

            val prefix = if (preferredPrefix.length in 2..4) preferredPrefix.uppercase() else "BGS"
            var counter = 101
            while (counter < 9999) {
                val code = "$prefix-$counter"
                if (code !in existingCodes) return@withContext code
                counter++
            }
            "$prefix-${System.currentTimeMillis().toString().takeLast(4)}"
        }
    }
}

