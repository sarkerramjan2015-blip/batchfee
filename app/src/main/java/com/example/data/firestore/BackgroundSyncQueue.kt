package com.batchfee.edu.data.firestore

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.*
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/** Durable rows, process-lifetime delivery. Undelivered rows resume on the next app start. */
object BackgroundSyncQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val delivery = Mutex()
    private var runner: Job? = null

    @Synchronized fun start(db: AppDatabase) {
        if (runner?.isActive == true) return
        runner = scope.launch {
            while (isActive) {
                flush(db)
                delay(30_000)
            }
        }
    }

    private fun row(tenant: String, kind: String, documentId: String, fields: Map<String, Any?>): BackgroundSyncEntity {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in before saving.")
        require(SessionManager.currentInstituteId.value == tenant) { "Institute session changed. Please retry." }
        return BackgroundSyncEntity(UUID.randomUUID().toString(), uid, tenant, kind, documentId,
            JSONObject(fields).toString(), System.currentTimeMillis())
    }

    suspend fun audit(db: AppDatabase, log: AuditLogEntity) {
        val pending = row(log.instituteId, "audit", log.id, mapOf(
            "instituteId" to log.instituteId, "userId" to log.userId, "action" to log.action,
            "module" to log.module, "description" to log.description, "oldValue" to log.oldValue,
            "newValue" to log.newValue, "createdAtMs" to log.createdAtMs
        ))
        require(pending.actorUid == log.userId)
        db.withTransaction { db.auditLogDao().insertAuditLog(log); db.backgroundSyncDao().put(pending) }
        start(db)
        scope.launch { flush(db) }
    }

    suspend fun profile(db: AppDatabase, institute: InstituteEntity, owner: UserEntity) {
        val pending = row(institute.id, "profile", institute.id, mapOf(
            "instituteName" to institute.name, "phone" to institute.phone, "address" to institute.address,
            "whatsappNumber" to institute.whatsappNumber, "profilePhotoUri" to institute.profilePhotoUri,
            "ownerName" to owner.name
        ))
        db.withTransaction {
            db.instituteDao().updateInstitute(institute.copy(ownerName = owner.name))
            db.userDao().updateUser(owner)
            db.backgroundSyncDao().put(pending)
        }
        start(db)
        scope.launch { flush(db) }
    }

    private suspend fun flush(db: AppDatabase) = delivery.withLock {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withLock
            val tenant = SessionManager.currentInstituteId.value ?: return@withLock
            val blockedKinds = mutableSetOf<String>()
            for (pending in db.backgroundSyncDao().pending(uid, tenant)) {
                if (pending.kind in blockedKinds) continue
                if (FirebaseAuth.getInstance().currentUser?.uid != uid || SessionManager.currentInstituteId.value != tenant) break
                try {
                    val json = JSONObject(pending.payload)
                    val fields = json.keys().asSequence().associateWith { key -> json.get(key).takeUnless { it == JSONObject.NULL } }
                    val institute = FirebaseFirestore.getInstance().collection("institutes").document(tenant)
                    when (pending.kind) {
                        "audit" -> institute.collection("audit_logs").document(pending.documentId).set(fields).await()
                        "profile" -> institute.update(fields).await() // Never recreate a deleted institute or overwrite billing fields.
                        else -> error("Unsupported background task")
                    }
                    db.backgroundSyncDao().remove(pending.id)
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) {
                    // Preserve order for profiles, but a log permission failure must not block profile delivery.
                    if (pending.kind == "profile") blockedKinds += pending.kind
                }
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { /* Retain rows and retry next pass. */ }
    }
}
