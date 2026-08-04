package com.batchfee.edu.data.cloudinary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Uploads institute logos to the BatchFee Cloudinary account.
 *
 * The cloud name and unsigned upload preset identify a public upload endpoint;
 * they are not API credentials. API keys and secrets must never be shipped in
 * the Android application.
 */
object CloudinaryImageUploadHelper {
    private const val cloudName = "cbhhlz9q"
    private const val uploadPreset = "bf_institute_logo_9q7k2m4x"
    private val httpClient = OkHttpClient()

    suspend fun uploadInstituteLogo(context: Context, sourceUri: Uri): String =
        uploadImage(context, sourceUri, ImagePolicy.INSTITUTE_LOGO)

    suspend fun uploadStudentPhoto(context: Context, sourceUri: Uri): String =
        uploadImage(context, sourceUri, ImagePolicy.STUDENT_PHOTO)

    suspend fun uploadStaffPhoto(context: Context, sourceUri: Uri): String =
        uploadImage(context, sourceUri, ImagePolicy.STAFF_PHOTO)

    private suspend fun uploadImage(
        context: Context,
        sourceUri: Uri,
        policy: ImagePolicy
    ): String =
        withContext(Dispatchers.IO) {
            val imageBytes = optimizeToJpeg(context, sourceUri, policy)

            val uploadUrl = "https://api.cloudinary.com/v1_1/$cloudName/image/upload"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", uploadPreset)
                .addFormDataPart(
                    "file",
                    "${policy.filePrefix}-photo.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    }.getOrNull() ?: "Cloud logo upload failed (${response.code})."
                    throw IllegalStateException(message)
                }

                JSONObject(responseBody).optString("secure_url").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Cloudinary did not return an image URL.")
            }
        }

    /** Converts every selected image into a small JPEG before it leaves the device. */
    private fun optimizeToJpeg(context: Context, sourceUri: Uri, policy: ImagePolicy): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("Unable to read the selected image.")
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

            // A detailed image can still exceed its target at lower quality. Keep reducing its
            // dimensions until it is within the target or it reaches a sensible minimum size.
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
        val filePrefix: String
    ) {
        INSTITUTE_LOGO(maxDimension = 800, targetBytes = 500 * 1024, filePrefix = "institute-logo"),
        STUDENT_PHOTO(maxDimension = 720, targetBytes = 300 * 1024, filePrefix = "student"),
        STAFF_PHOTO(maxDimension = 720, targetBytes = 300 * 1024, filePrefix = "staff")
    }
}
