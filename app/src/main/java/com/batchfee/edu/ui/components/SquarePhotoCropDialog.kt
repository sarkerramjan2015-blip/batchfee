package com.batchfee.edu.ui.components

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

private const val PROFILE_PHOTO_SIZE = 300
private const val MAX_CROP_DECODE_DIMENSION = 2048

/**
 * Shared profile-photo editor. It deliberately keeps one fixed square frame so every
 * student/staff avatar, ID card and document gets a consistent crop without asking
 * non-technical users to choose an aspect ratio.
 */
@Composable
fun SquarePhotoCropDialog(
    sourceUri: Uri,
    onCropped: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(sourceUri) { mutableStateOf<String?>(null) }
    var viewport by remember(sourceUri) { mutableStateOf(IntSize.Zero) }
    var zoom by remember(sourceUri) { mutableStateOf(1f) }
    var pan by remember(sourceUri) { mutableStateOf(Offset.Zero) }
    var isSaving by remember(sourceUri) { mutableStateOf(false) }

    LaunchedEffect(sourceUri) {
        runCatching {
            withContext(Dispatchers.IO) { decodeForCrop(context, sourceUri) }
        }.onSuccess { bitmap = it }
            .onFailure { loadError = it.message ?: "Could not read this image." }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF020913),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Color(0xFF073642))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel crop", tint = Color(0xFFB7F5FF))
                    }
                    Text(
                        "Adjust photo",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { if (bitmap != null && viewport.width > 0) isSaving = true },
                        enabled = bitmap != null && viewport.width > 0 && !isSaving,
                    ) {
                        Icon(Icons.Filled.Check, "Use cropped photo", tint = Color(0xFF8CEFFC))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Pinch to zoom - Drag to position",
                        color = Color(0xFFA7B7C9),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))

                    val currentBitmap = bitmap
                    Box(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF111827))
                            .onSizeChanged { viewport = it }
                            .pointerInput(currentBitmap, viewport, zoom) {
                                val image = currentBitmap
                                if (image != null && viewport.width > 0 && viewport.height > 0) {
                                    detectTransformGestures { _, drag, zoomChange, _ ->
                                        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                        val nextLayout = cropLayout(image, viewport, nextZoom)
                                        zoom = nextZoom
                                        pan = Offset(
                                            x = (pan.x + drag.x).coerceIn(-nextLayout.maxPanX, nextLayout.maxPanX),
                                            y = (pan.y + drag.y).coerceIn(-nextLayout.maxPanY, nextLayout.maxPanY),
                                        )
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            currentBitmap != null && viewport.width > 0 -> {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val layout = cropLayout(currentBitmap, viewport, zoom)
                                    val left = (size.width - layout.drawWidth) / 2f + pan.x
                                    val top = (size.height - layout.drawHeight) / 2f + pan.y
                                    drawImage(
                                        image = currentBitmap.asImageBitmap(),
                                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                        dstSize = IntSize(layout.drawWidth.roundToInt(), layout.drawHeight.roundToInt()),
                                    )
                                    val grid = Color.White.copy(alpha = 0.56f)
                                    for (index in 1..2) {
                                        val fraction = index / 3f
                                        drawLine(grid, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 1.1.dp.toPx())
                                        drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1.1.dp.toPx())
                                    }
                                    drawRect(Color.White.copy(alpha = 0.92f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                    listOf(
                                        Offset(0f, 0f), Offset(size.width, 0f),
                                        Offset(0f, size.height), Offset(size.width, size.height),
                                    ).forEach { corner ->
                                        drawCircle(Color.White, 9.dp.toPx(), corner)
                                    }
                                }
                            }

                            loadError != null -> {
                                Text(loadError!!, color = Color(0xFFFF9A9A), fontSize = 14.sp)
                            }

                            else -> CircularProgressIndicator(color = Color(0xFF20D7F5), modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "The square frame is used for profile, ID card and documents.",
                        color = Color(0xFF7F93AA),
                        fontSize = 11.sp,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF061D29))
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CropToolButton("Rotate left", Icons.Filled.RotateLeft, enabled = bitmap != null && !isSaving) {
                        bitmap?.let { current ->
                            rotateBitmap(current, -90f)?.let { rotated ->
                                bitmap = rotated
                                zoom = 1f
                                pan = Offset.Zero
                            }
                        }
                    }
                    CropToolButton("Reset", Icons.Filled.RestartAlt, enabled = bitmap != null && !isSaving) {
                        zoom = 1f
                        pan = Offset.Zero
                    }
                    CropToolButton("Rotate right", Icons.Filled.RotateRight, enabled = bitmap != null && !isSaving) {
                        bitmap?.let { current ->
                            rotateBitmap(current, 90f)?.let { rotated ->
                                bitmap = rotated
                                zoom = 1f
                                pan = Offset.Zero
                            }
                        }
                    }
                    Button(
                        onClick = { if (bitmap != null && viewport.width > 0) isSaving = true },
                        enabled = bitmap != null && viewport.width > 0 && !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20D7F5)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = Color(0xFF06141B))
                        } else {
                            Icon(Icons.Filled.Check, null, tint = Color(0xFF06141B), modifier = Modifier.size(18.dp))
                            Text(" Use photo", color = Color(0xFF06141B), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(isSaving) {
        if (!isSaving) return@LaunchedEffect
        val image = bitmap ?: run {
            isSaving = false
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) { cropToSquareFile(context, image, viewport, zoom, pan) }
        }.onSuccess(onCropped)
            .onFailure {
                loadError = it.message ?: "Could not crop this image."
                isSaving = false
            }
    }
}

@Composable
private fun CropToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF0B3342)),
        ) {
            Icon(icon, label, tint = if (enabled) Color(0xFF9DEFFF) else Color(0xFF50677A))
        }
        Text(label, color = if (enabled) Color(0xFFB6CBD7) else Color(0xFF50677A), fontSize = 10.sp)
    }
}

private data class CropLayout(
    val scale: Float,
    val drawWidth: Float,
    val drawHeight: Float,
    val maxPanX: Float,
    val maxPanY: Float,
)

private fun cropLayout(bitmap: Bitmap, viewport: IntSize, zoom: Float): CropLayout {
    val baseScale = maxOf(
        viewport.width.toFloat() / bitmap.width.toFloat(),
        viewport.height.toFloat() / bitmap.height.toFloat(),
    )
    val scale = baseScale * zoom
    val drawWidth = bitmap.width * scale
    val drawHeight = bitmap.height * scale
    return CropLayout(
        scale = scale,
        drawWidth = drawWidth,
        drawHeight = drawHeight,
        maxPanX = ((drawWidth - viewport.width) / 2f).coerceAtLeast(0f),
        maxPanY = ((drawHeight - viewport.height) / 2f).coerceAtLeast(0f),
    )
}

private fun cropToSquareFile(
    context: Context,
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
    pan: Offset,
): Uri {
    require(viewport.width > 0 && viewport.height > 0) { "Crop frame is not ready." }
    val layout = cropLayout(bitmap, viewport, zoom)
    val drawLeft = (viewport.width - layout.drawWidth) / 2f + pan.x
    val drawTop = (viewport.height - layout.drawHeight) / 2f + pan.y
    val cropWidth = (viewport.width / layout.scale).roundToInt().coerceIn(1, bitmap.width)
    val cropHeight = (viewport.height / layout.scale).roundToInt().coerceIn(1, bitmap.height)
    val cropLeft = ((-drawLeft) / layout.scale).roundToInt().coerceIn(0, bitmap.width - cropWidth)
    val cropTop = ((-drawTop) / layout.scale).roundToInt().coerceIn(0, bitmap.height - cropHeight)
    val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
    val output = if (cropped.width == PROFILE_PHOTO_SIZE && cropped.height == PROFILE_PHOTO_SIZE) cropped else {
        Bitmap.createScaledBitmap(cropped, PROFILE_PHOTO_SIZE, PROFILE_PHOTO_SIZE, true).also { cropped.recycle() }
    }
    try {
        val target = File(context.cacheDir, "cropped_photo_${UUID.randomUUID()}.jpg")
        FileOutputStream(target).use { stream ->
            check(output.compress(Bitmap.CompressFormat.JPEG, 84, stream)) { "Could not save cropped image." }
        }
        return Uri.fromFile(target)
    } finally {
        output.recycle()
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap? = runCatching {
    Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        Matrix().apply { postRotate(degrees) },
        true,
    )
}.getOrNull()

private fun imageInputStream(context: Context, uri: Uri): InputStream? = when (uri.scheme) {
    ContentResolver.SCHEME_FILE -> uri.path
        ?.let(::File)
        ?.takeIf { it.isFile && it.canRead() }
        ?.inputStream()
    else -> context.contentResolver.openInputStream(uri)
}

private fun decodeForCrop(context: Context, sourceUri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(context, sourceUri)?.let { return it }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // decodeStream deliberately returns null when inJustDecodeBounds=true. Opening the
    // stream successfully is what matters here; treating that expected null as an error
    // was the reason the old crop screen showed "Could not read this image".
    imageInputStream(context, sourceUri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    } ?: error("Could not read this image.")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Choose a valid image file.")

    var sampleSize = 1
    while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_CROP_DECODE_DIMENSION) sampleSize *= 2
    val decoded = imageInputStream(context, sourceUri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } ?: error("Could not decode this image.")

    val orientation = runCatching {
        if (sourceUri.scheme == ContentResolver.SCHEME_FILE) {
            ExifInterface(requireNotNull(sourceUri.path)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } else {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return decoded
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { decoded.recycle() }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(context: Context, sourceUri: Uri): Bitmap? = runCatching {
    val source = if (sourceUri.scheme == ContentResolver.SCHEME_FILE) {
        val file = sourceUri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
            ?: error("Could not read this image.")
        ImageDecoder.createSource(file)
    } else {
        ImageDecoder.createSource(context.contentResolver, sourceUri)
    }
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        var sampleSize = 1
        val largestSide = maxOf(info.size.width, info.size.height)
        while (largestSide / sampleSize > MAX_CROP_DECODE_DIMENSION) sampleSize *= 2
        if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
    }
}.getOrNull()
