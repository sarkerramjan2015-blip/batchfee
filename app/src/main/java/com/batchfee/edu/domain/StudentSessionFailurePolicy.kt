package com.batchfee.edu.domain

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException

internal fun isTemporaryStudentSessionFailure(error: Throwable): Boolean =
    error is FirebaseNetworkException || (error is FirebaseFirestoreException && error.code in setOf(
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED
    ))
