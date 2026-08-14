package com.batchfee.edu.ui.batches

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.core.content.FileProvider
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.ui.students.drawLogo
import com.batchfee.edu.ui.students.loadBitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ROUTINE_PDF_WIDTH = 842
private const val ROUTINE_PDF_HEIGHT = 595
private const val ROUTINE_ROWS_PER_PAGE = 6

/** Generates a polished landscape A4 routine using only schedule data saved on batches. */
internal suspend fun generateRoutinePdf(
    context: Context,
    institute: InstituteEntity,
    batches: List<BatchEntity>,
    isAllBatches: Boolean
): File {
    require(batches.isNotEmpty()) { "Choose at least one batch." }

    val document = PdfDocument()
    val logo = loadBitmap(context, institute.profilePhotoUri)
    val chunks = batches.chunked(ROUTINE_ROWS_PER_PAGE)
    val generatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

    chunks.forEachIndexed { pageIndex, pageBatches ->
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(ROUTINE_PDF_WIDTH, ROUTINE_PDF_HEIGHT, pageIndex + 1).create()
        )
        val canvas = page.canvas
        drawRoutinePage(
            canvas = canvas,
            institute = institute,
            logo = logo,
            batches = pageBatches,
            title = if (isAllBatches) "ALL BATCH CLASS ROUTINE" else "BATCH CLASS ROUTINE",
            generatedAt = generatedAt,
            pageNumber = pageIndex + 1,
            totalPages = chunks.size
        )
        document.finishPage(page)
    }

    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeName = institute.name.ifBlank { "institute" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val suffix = if (isAllBatches) "all_batches" else batches.first().name.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(directory, "class_routine_${safeName}_${suffix}.pdf")
    file.outputStream().use(document::writeTo)
    document.close()
    return file
}

internal fun shareRoutinePdf(context: Context, file: File, label: String): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, label)
        putExtra(Intent.EXTRA_TEXT, label)
        clipData = ClipData.newRawUri(label, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share routine PDF"))
    true
} catch (_: Exception) {
    false
}

internal fun printRoutinePdf(context: Context, file: File, label: String): Boolean = try {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return false
    printManager.print(label, RoutinePdfPrintAdapter(file, label), PrintAttributes.Builder().build())
    true
} catch (_: Exception) {
    false
}

private fun drawRoutinePage(
    canvas: Canvas,
    institute: InstituteEntity,
    logo: android.graphics.Bitmap?,
    batches: List<BatchEntity>,
    title: String,
    generatedAt: String,
    pageNumber: Int,
    totalPages: Int
) {
    val width = ROUTINE_PDF_WIDTH.toFloat()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    val navy = Color.rgb(5, 27, 50)
    val deepBlue = Color.rgb(19, 69, 158)
    val cyan = Color.rgb(34, 211, 238)
    val paleCyan = Color.rgb(236, 254, 255)
    val slate = Color.rgb(71, 85, 105)
    val border = Color.rgb(203, 213, 225)

    canvas.drawColor(Color.rgb(248, 250, 252))

    // Header - the diagonal and translucent circles keep the PDF branded without being noisy.
    fill.color = navy
    canvas.drawRect(0f, 0f, width, 126f, fill)
    fill.color = deepBlue
    canvas.drawPath(Path().apply {
        moveTo(width * .57f, 0f)
        lineTo(width, 0f)
        lineTo(width, 126f)
        lineTo(width * .73f, 126f)
        close()
    }, fill)
    fill.color = Color.argb(50, 34, 211, 238)
    canvas.drawCircle(width - 48f, 38f, 34f, fill)
    canvas.drawCircle(width - 10f, 100f, 19f, fill)

    drawLogo(canvas, logo, institute.name, 32f, 28f, 56f, navy, cyan)
    val instituteName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f; isFakeBoldText = true }
    canvas.drawText(ellipsize(institute.name.ifBlank { "Institute" }, instituteName, 290f), 102f, 52f, instituteName)
    val instituteMeta = listOfNotNull(
        institute.phone?.takeIf { it.isNotBlank() },
        institute.instituteCode?.takeIf { it.isNotBlank() }?.let { "Code: $it" }
    ).joinToString("  |  ")
    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 243, 252); textSize = 9f }
    canvas.drawText(ellipsize(instituteMeta, metaPaint, 310f), 102f, 70f, metaPaint)
    canvas.drawText(ellipsize(institute.address.orEmpty(), metaPaint, 310f), 102f, 87f, metaPaint)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    canvas.drawText(title, width - 34f, 52f, titlePaint)
    val generatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(191, 219, 254); textSize = 9f; textAlign = Paint.Align.RIGHT }
    canvas.drawText("Generated $generatedAt", width - 34f, 71f, generatedPaint)
    canvas.drawText("Page $pageNumber of $totalPages", width - 34f, 88f, generatedPaint)

    // Key / description band.
    fill.color = paleCyan
    canvas.drawRoundRect(RectF(28f, 142f, width - 28f, 181f), 9f, 9f, fill)
    val bandTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = deepBlue; textSize = 11f; isFakeBoldText = true }
    canvas.drawText("CLASS SCHEDULE & FEE INFORMATION", 42f, 166f, bandTitle)
    val bandMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate; textSize = 9f; textAlign = Paint.Align.RIGHT }
    canvas.drawText("All times are shown in local time", width - 42f, 166f, bandMeta)

    val columns = floatArrayOf(28f, 178f, 382f, 539f, 676f, 814f)
    val tableTop = 198f
    val tableBottom = tableTop + 28f
    fill.color = navy
    canvas.drawRoundRect(RectF(columns.first(), tableTop, columns.last(), tableBottom), 7f, 7f, fill)
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9f; isFakeBoldText = true }
    val headers = listOf("BATCH / CLASS", "WEEKLY SCHEDULE", "CLASS TIME / DURATION", "MONTHLY FEE", "ADMISSION FEE")
    headers.forEachIndexed { index, value -> canvas.drawText(value, columns[index] + 10f, tableTop + 18f, headerPaint) }

    var y = tableBottom
    batches.forEachIndexed { index, batch ->
        val rowHeight = 55f
        fill.color = if (index % 2 == 0) Color.WHITE else Color.rgb(248, 250, 252)
        canvas.drawRect(columns.first(), y, columns.last(), y + rowHeight, fill)
        line.color = border
        columns.forEach { x -> canvas.drawLine(x, y, x, y + rowHeight, line) }
        canvas.drawLine(columns.first(), y + rowHeight, columns.last(), y + rowHeight, line)

        val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 10.5f; isFakeBoldText = true }
        val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate; textSize = 8.5f }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = deepBlue; textSize = 9.5f; isFakeBoldText = true }
        drawTwoLineCell(canvas, batch.name, listOfNotNull(batch.className, batch.subject).joinToString(" · ").ifBlank { "Class not specified" }, columns[0] + 10f, y, columns[1] - columns[0] - 18f, primary, secondary)
        drawTwoLineCell(canvas, weeklyFrequency(batch), formattedDays(batch), columns[1] + 10f, y, columns[2] - columns[1] - 18f, primary, secondary)
        drawTwoLineCell(canvas, classTime(batch), "Duration: ${routineDuration(batch) ?: "Not set"}", columns[2] + 10f, y, columns[3] - columns[2] - 18f, primary, secondary)
        drawTwoLineCell(canvas, "BDT ${formatMoney(batch.monthlyFeeAmount)}", "Monthly fee", columns[3] + 10f, y, columns[4] - columns[3] - 18f, accent, secondary)
        drawTwoLineCell(canvas, "BDT ${formatMoney(batch.admissionFeeAmount)}", "One-time admission", columns[4] + 10f, y, columns[5] - columns[4] - 18f, accent, secondary)
        y += rowHeight
    }

    val footerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(203, 213, 225); strokeWidth = 1f }
    canvas.drawLine(28f, ROUTINE_PDF_HEIGHT - 38f, width - 28f, ROUTINE_PDF_HEIGHT - 38f, footerLine)
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate; textSize = 8.5f; textAlign = Paint.Align.CENTER }
    canvas.drawText("This routine is generated from the current batch settings. Please contact the institute for changes.", width / 2f, ROUTINE_PDF_HEIGHT - 19f, footer)
}

private fun drawTwoLineCell(
    canvas: Canvas,
    first: String,
    second: String,
    x: Float,
    rowY: Float,
    maxWidth: Float,
    primary: Paint,
    secondary: Paint
) {
    canvas.drawText(ellipsize(first, primary, maxWidth), x, rowY + 21f, primary)
    canvas.drawText(ellipsize(second, secondary, maxWidth), x, rowY + 39f, secondary)
}

private fun weeklyFrequency(batch: BatchEntity): String {
    val count = batch.scheduleDays?.split(",")?.map { it.trim() }?.count { it.isNotBlank() } ?: 0
    return if (count > 0) "$count day${if (count == 1) "" else "s"} per week" else "Schedule not set"
}

private fun formattedDays(batch: BatchEntity): String = batch.scheduleDays
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotBlank() }
    ?.joinToString(", ")
    ?.ifBlank { "Choose days in batch settings" }
    ?: "Choose days in batch settings"

private fun classTime(batch: BatchEntity): String {
    val start = formatRoutineTime(batch.startTime)
    val end = formatRoutineTime(batch.endTime)
    return if (start != null && end != null) "$start - $end" else "Time not set"
}

private fun routineDuration(batch: BatchEntity): String? {
    val start = routineMinutes(batch.startTime) ?: return null
    val end = routineMinutes(batch.endTime) ?: return null
    if (end <= start) return null
    val total = end - start
    val hours = total / 60
    val minutes = total % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours hr"
        else -> "$hours hr $minutes min"
    }
}

private fun routineMinutes(value: String?): Int? {
    val parts = value?.split(":") ?: return null
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

private fun formatRoutineTime(value: String?): String? {
    val total = routineMinutes(value) ?: return null
    val hour = total / 60
    val minute = total % 60
    val suffix = if (hour < 12) "AM" else "PM"
    val displayHour = when (val normalized = hour % 12) { 0 -> 12; else -> normalized }
    return String.format(Locale.US, "%d:%02d %s", displayHour, minute, suffix)
}

private fun formatMoney(amount: Double): String = DecimalFormat("#,##0.##").format(amount)

private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end--
    return value.take(end) + suffix
}

private class RoutinePdfPrintAdapter(
    private val file: File,
    private val documentName: String
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("$documentName.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build(),
            oldAttributes != newAttributes
        )
    }

    override fun onWrite(
        pages: Array<android.print.PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
            callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message)
        }
    }
}
