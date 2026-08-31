package com.batchfee.edu.data.firestore

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

internal const val FIRESTORE_SYNC_PAGE_SIZE = 200L

internal data class FirestorePagedDocuments(
    val documents: List<DocumentSnapshot>,
    val isServerAuthoritative: Boolean
)

/**
 * Reads a collection in deterministic document-id pages. A bounded page keeps a
 * large institute refresh from asking Firestore and the device to materialise an
 * unbounded QuerySnapshot in one allocation.
 */
internal suspend fun Query.forEachDocumentPage(
    pageSize: Long = FIRESTORE_SYNC_PAGE_SIZE,
    onPage: suspend (List<DocumentSnapshot>) -> Unit
): Boolean {
    require(pageSize in 1L..500L) { "Firestore page size must be between 1 and 500." }

    var cursor: DocumentSnapshot? = null
    var serverAuthoritative = true
    do {
        var pageQuery = orderBy(FieldPath.documentId()).limit(pageSize)
        cursor?.let { pageQuery = pageQuery.startAfter(it) }
        val snapshot = pageQuery.get().await()
        serverAuthoritative = serverAuthoritative && !snapshot.metadata.isFromCache
        val documents = snapshot.documents
        if (documents.isNotEmpty()) {
            onPage(documents)
            cursor = documents.last()
        }
    } while (documents.size.toLong() == pageSize)

    return serverAuthoritative
}

/** Used only where cross-collection validation needs the complete ledger view. */
internal suspend fun Query.collectDocumentPages(
    pageSize: Long = FIRESTORE_SYNC_PAGE_SIZE
): FirestorePagedDocuments {
    val documents = ArrayList<DocumentSnapshot>()
    val authoritative = forEachDocumentPage(pageSize) { documents += it }
    return FirestorePagedDocuments(documents, authoritative)
}
