package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctionsException

/**
 * The Firebase Functions SDK marks its exception constructors `internal`, so
 * Kotlin test code cannot call them directly. The bytecode constructor is
 * public and this reflective helper keeps focused tests able to script
 * authentic server error codes.
 */
internal fun functionsException(
    code: FirebaseFunctionsException.Code,
    message: String
): FirebaseFunctionsException {
    val constructor = FirebaseFunctionsException::class.java.getConstructor(
        String::class.java,
        FirebaseFunctionsException.Code::class.java,
        Any::class.java
    )
    return constructor.newInstance(message, code, null)
}
