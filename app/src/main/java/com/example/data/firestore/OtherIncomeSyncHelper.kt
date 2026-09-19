package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.OtherIncomeEntity
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Cloud mirror for auditable non-fee income. Student fee payments stay separate. */
object OtherIncomeSyncHelper {
    private const val COLLECTION = "other_income"
    private fun collection(instituteId: String) = FirebaseFirestore.getInstance()
        .collection("institutes").document(instituteId).collection(COLLECTION)

    suspend fun upsertIncome(income: OtherIncomeEntity) = withContext(Dispatchers.IO) {
        collection(income.instituteId).document(income.id).set(fields(income)).await()
    }

    suspend fun syncAllFromFirestore(db: AppDatabase, instituteId: String) = withContext(Dispatchers.IO) {
        try {
            collection(instituteId).get().await().documents
                .mapNotNull { it.toIncome(instituteId) }
                .forEach { db.otherIncomeDao().insertIncome(it) }
        } catch (_: Exception) { }
    }

    private fun fields(income: OtherIncomeEntity) = mapOf(
        "instituteId" to income.instituteId, "category" to income.category,
        "title" to income.title, "amount" to income.amount, "incomeDateMs" to income.incomeDateMs,
        "paymentMethod" to income.paymentMethod, "note" to income.note,
        "createdByUserId" to income.createdByUserId, "createdAtMs" to income.createdAtMs,
        "updatedAtMs" to income.updatedAtMs, "archivedAtMs" to income.archivedAtMs,
    )

    private fun DocumentSnapshot.toIncome(instituteId: String): OtherIncomeEntity? {
        val title = getString("title") ?: return null
        val creator = getString("createdByUserId") ?: return null
        return OtherIncomeEntity(
            id = id, instituteId = getString("instituteId") ?: instituteId,
            category = getString("category") ?: "Other", title = title,
            amount = (get("amount") as? Number)?.toDouble() ?: 0.0,
            incomeDateMs = (get("incomeDateMs") as? Number)?.toLong() ?: 0L,
            paymentMethod = getString("paymentMethod"), note = getString("note"),
            createdByUserId = creator,
            createdAtMs = (get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAtMs = (get("updatedAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
            archivedAtMs = (get("archivedAtMs") as? Number)?.toLong(),
        )
    }
}
