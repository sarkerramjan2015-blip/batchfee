package com.batchfee.edu.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Optimizes images locally, then sends them to an Auth + App Check protected
 * callable. The function writes Firebase Storage through the Admin SDK; clients
 * never receive Storage write access, object paths, or service credentials.
 */
object FirebaseStorageImageUploadHelper {
    private const val MANAGED_REFERENCE_PREFIX = "batchfee-media://v1/"
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    suspend fun uploadInstituteLogo(
        context: Context,
        sourceUri: Uri,
        replacesReference: String? = null
    ): String = uploadImage(context, sourceUri, ImagePolicy.INSTITUTE_LOGO, null, replacesReference)

    suspend fun uploadStudentPhoto(
        context: Context,
        sourceUri: Uri,
        subjectId: String? = null,
        replacesReference: String? = null
    ): String = uploadImage(context, sourceUri, ImagePolicy.STUDENT_PHOTO, subjectId, replacesReference)

    suspend fun uploadStaffPhoto(
        context: Context,
        sourceUri: Uri,
        subjectId: String? = null,
        replacesReference: String? = null
    ): String = uploadImage(context, sourceUri, ImagePolicy.STAFF_PHOTO, subjectId, replacesReference)

    fun isManagedReference(value: String?): Boolean =
        value?.startsWith(MANAGED_REFERENCE_PREFIX) == true

    /**
     * Returns an on-device copy when available. Media is still stored in Firebase,
     * but the local copy keeps newly saved photos visible in cards and PDFs while
     * the device is temporarily offline.
     */
    fun displaySource(context: Context, reference: String?): String? {
        if (reference.isNullOrBlank()) return null
        val cachedPath = context.getSharedPreferences("secure_media_cache", Context.MODE_PRIVATE)
            .getString(cacheKey(reference), null)
        if (!cachedPath.isNullOrBlank()) {
            val file = File(cachedPath)
            if (file.isFile && file.length() > 0L) return Uri.fromFile(file).toString()
        }
        // Earlier app versions kept the newly chosen institute logo only in cache.
        // Adopt that most recent copy once so existing installations can render
        // their current logo in PDFs even before the next logo change.
        if (reference.startsWith("https://") || reference.startsWith("http://")) {
            recoverRecentInstituteLogo(context, reference)?.let { return it }
        }
        return reference
    }

    /**
     * Resolves a source for direct bitmap/PDF rendering. Coil already resolves managed
     * references through its interceptor, but PDF generators do not use Coil. This keeps
     * student photos available in admission forms, reports and ID cards after local cache
     * has been cleared or on another signed-in device.
     */
    suspend fun resolveForDirectRead(context: Context, reference: String?): String? {
        val source = displaySource(context, reference) ?: return null
        if (!isManagedReference(source)) return source
        return runCatching {
            val response = functions.getHttpsCallable("getSecureMediaUrl")
                .call(mapOf("reference" to source))
                .await()
            val data = response.data as? Map<*, *>
                ?: error("Secure media service returned an invalid response.")
            (data["url"] as? String)?.takeIf { it.startsWith("https://") }
                ?: error("Secure media service did not return an HTTPS URL.")
        }.getOrNull()
    }

    /**
     * Copies a gallery result into the app cache while its temporary picker permission is valid.
     * This makes the image safe to upload later, including after the picker has closed.
     */
    fun cacheSelectedImage(context: Context, sourceUri: Uri, prefix: String): Uri {
        val target = File(
            context.cacheDir,
            "${prefix}_${UUID.randomUUID()}.img"
        ).apply { parentFile?.mkdirs() }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Could not read this image. Please choose it again.")
        // This file belongs to our own app. A direct file URI avoids relying on a
        // FileProvider read grant again during the later Save action.
        return Uri.fromFile(target)
    }

    private fun isExistingCloudReference(value: String): Boolean =
        isManagedReference(value) || value.startsWith("https://") || value.startsWith("http://")

    private suspend fun uploadImage(
        context: Context,
        sourceUri: Uri,
        policy: ImagePolicy,
        subjectId: String?,
        replacesReference: String?
    ): String {
        val source = sourceUri.toString()
        if (isExistingCloudReference(source)) return source
        val instituteId = SessionManager.currentInstituteId.value
            ?: throw IllegalStateException("Institute session was not found.")
        val imageBytes = withContext(Dispatchers.IO) {
            optimizeToJpeg(context, sourceUri, policy)
        }
        val request = mutableMapOf<String, Any>(
            "instituteId" to instituteId,
            "purpose" to policy.purpose,
            "operationId" to UUID.randomUUID().toString(),
            "imageBase64" to Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        )
        subjectId?.takeIf { it.isNotBlank() }?.let { request["subjectId"] = it }
        // Local URIs are not remote assets and are deliberately omitted. Existing HTTPS
        // references and managed references remain auditable replacement metadata at the
        // trusted backend.
        replacesReference?.takeIf(::isExistingCloudReference)
            ?.let { request["replacesReference"] = it }
        val response = functions.getHttpsCallable("uploadSecureMedia").call(request).await()
        val data = response.data as? Map<*, *>
            ?: throw IllegalStateException("Secure media service returned an invalid response.")
        val reference = (data["reference"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Secure media service did not return a reference.")
        cacheForLocalDisplay(context, reference, imageBytes)
        return reference
    }

    private fun cacheForLocalDisplay(context: Context, reference: String, bytes: ByteArray) {
        runCatching {
            val directory = File(context.filesDir, "secure_media").apply { mkdirs() }
            val target = File(directory, "${cacheKey(reference)}.jpg")
            target.outputStream().use { it.write(bytes) }
            context.getSharedPreferences("secure_media_cache", Context.MODE_PRIVATE)
                .edit()
                .putString(cacheKey(reference), target.absolutePath)
                .apply()
        }
    }

    private fun recoverRecentInstituteLogo(context: Context, reference: String): String? = runCatching {
        val candidate = context.cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() > 0L && it.name.startsWith("institute_logo_") }
            ?.maxByOrNull(File::lastModified)
            ?: return null
        val directory = File(context.filesDir, "secure_media").apply { mkdirs() }
        val target = File(directory, "${cacheKey(reference)}.jpg")
        candidate.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        context.getSharedPreferences("secure_media_cache", Context.MODE_PRIVATE)
            .edit()
            .putString(cacheKey(reference), target.absolutePath)
            .apply()
        Uri.fromFile(target).toString()
    }.getOrNull()

    private fun cacheKey(reference: String): String = MessageDigest.getInstance("SHA-256")
        .digest(reference.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Converts every selected image into a small JPEG before it leaves the device. */
    private fun optimizeToJpeg(context: Context, sourceUri: Uri, policy: ImagePolicy): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds Android correctly returns null from decodeStream;
        // only the bounds fields are populated. Treating that expected null as a
        // read failure blocked every valid PNG/JPEG before upload could begin.
        val sourceWasOpened = resolver.openInputStream(sourceUri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            true
        } ?: false
        if (!sourceWasOpened) {
            throw IllegalArgumentException("Unable to read the selected image.")
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Choose a valid image file.")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, policy.maxDimension)
        }
        val decoded = resolver.openInputStream(sourceUri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalArgumentException("Unable to decode the selected image.")

        var working = scaleToLimit(decoded, policy.maxDimension)
        if (working.hasAlpha()) {
            val withWhiteBackground = Bitmap.createBitmap(
                working.width,
                working.height,
                Bitmap.Config.ARGB_8888
            )
            Canvas(withWhiteBackground).apply {
                drawColor(Color.WHITE)
                drawBitmap(working, 0f, 0f, null)
            }
            if (working !== decoded) working.recycle()
            working = withWhiteBackground
        }

        try {
            fun compress(quality: Int): ByteArray = ByteArrayOutputStream().use { stream ->
                working.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }

            var compressed = compress(85)
            for (quality in listOf(70, 55)) {
                if (compressed.size <= policy.targetBytes) return compressed
                compressed = compress(quality)
            }
            if (compressed.size <= policy.targetBytes) return compressed

            repeat(3) {
                if (working.width <= 320 && working.height <= 320) return compressed
                val reduced = Bitmap.createScaledBitmap(
                    working,
                    (working.width * 0.75f).toInt().coerceAtLeast(320),
                    (working.height * 0.75f).toInt().coerceAtLeast(320),
                    true
                )
                if (reduced === working) return compressed
                if (working !== decoded) working.recycle()
                working = reduced
                compressed = compress(65)
                if (compressed.size <= policy.targetBytes) return compressed
            }
            return compressed
        } finally {
            if (working !== decoded) working.recycle()
            decoded.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= maxDimension || sampledHeight / 2 >= maxDimension) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun scaleToLimit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largestSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private enum class ImagePolicy(
        val maxDimension: Int,
        val targetBytes: Int,
        val purpose: String
    ) {
        INSTITUTE_LOGO(maxDimension = 800, targetBytes = 500 * 1024, purpose = "institute_logo"),
        STUDENT_PHOTO(maxDimension = 720, targetBytes = 300 * 1024, purpose = "student_photo"),
        STAFF_PHOTO(maxDimension = 720, targetBytes = 300 * 1024, purpose = "staff_photo")
    }
}
