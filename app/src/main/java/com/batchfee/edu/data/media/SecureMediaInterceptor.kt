package com.batchfee.edu.data.media

import android.net.Uri
import coil.intercept.Interceptor
import coil.request.ImageResult
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/** Transparently exchanges a private Firebase Storage media reference for a short-lived URL. */
class SecureMediaInterceptor(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) : Interceptor {
    private data class CachedUrl(val url: String, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedUrl>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val reference = when (val data = chain.request.data) {
            is Uri -> data.toString()
            is String -> data
            else -> null
        }
        if (!FirebaseStorageImageUploadHelper.isManagedReference(reference)) {
            return chain.proceed(chain.request)
        }
        val resolvedUrl = resolve(reference!!)
        val authorizedRequest = chain.request.newBuilder().data(resolvedUrl).build()
        return chain.proceed(authorizedRequest)
    }

    private suspend fun resolve(reference: String): String {
        val now = System.currentTimeMillis()
        cache[reference]?.takeIf { it.expiresAtMs - 30_000L > now }?.let { return it.url }
        val response = functions.getHttpsCallable("getSecureMediaUrl")
            .call(mapOf("reference" to reference))
            .await()
        val data = response.data as? Map<*, *>
            ?: throw IllegalStateException("Secure media service returned an invalid response.")
        val url = (data["url"] as? String)?.takeIf { it.startsWith("https://") }
            ?: throw IllegalStateException("Secure media service did not return an HTTPS URL.")
        val expiresAtMs = (data["expiresAtMs"] as? Number)?.toLong()
            ?: throw IllegalStateException("Secure media service did not return an expiry.")
        cache[reference] = CachedUrl(url, expiresAtMs)
        return url
    }
}
