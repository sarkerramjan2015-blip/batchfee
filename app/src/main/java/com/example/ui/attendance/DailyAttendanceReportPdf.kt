package com.batchfee.edu.ui.attendance

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.batchfee.edu.data.models.AttendanceEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DailyAttendanceReportRow(
    val student: StudentEntity,
    val record: AttendanceEntity,
)

/** Creates an immutable, batch-specific daily attendance report only from complete records. */
internal fun createDailyAttendanceReportPdf(
    context: Context,
    institute: InstituteEntity?,
    batchName: String,
    dateMs: Long,
    rows: List<DailyAttendanceReportRow>,
): Uri {
    require(rows.isNotEmpty()) { "There are no attendance records to export." }
    require(rows.all { it.record.status in setOf("present", "absent", "late", "leave", "holiday") }) {
        "Attendance is incomplete."
    }
    val document = PdfDocument()
    val chunks = rows.chunked(24)
    val date = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(dateMs))
    val generated = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    val present = rows.count { it.record.status == "present" }
    val absent = rows.count { it.record.status == "absent" }
    val late = rows.count { it.record.status == "late" }
    val leave = rows.count { it.record.status == "leave" }
    val name = institute?.name?.trim().orEmpty().ifBlank { "BatchFee Institute" }

    chunks.forEachIndexed { pageIndex, pageRows ->
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(15, 23, 42)
        canvas.drawRect(0f, 0f, 595f, 118f, paint)
        paint.color = Color.rgb(34, 211, 238); paint.textSize = 19f; paint.isFakeBoldText = true
        canvas.drawText(name.take(52), 34f, 44f, paint)
        paint.color = Color.WHITE; paint.textSize = 16f
        canvas.drawText("Daily Attendance Report", 34f, 73f, paint)
        paint.color = Color.rgb(203, 213, 225); paint.textSize = 10f; paint.isFakeBoldText = false
        canvas.drawText("Batch/Class: $batchName  •  Date: $date", 34f, 97f, paint)

        paint.color = Color.rgb(241, 245, 249); canvas.drawRect(30f, 136f, 565f, 184f, paint)
        paint.color = Color.rgb(22, 163, 74); paint.textSize = 11f; paint.isFakeBoldText = true
        canvas.drawText("PRESENT  $present", 46f, 165f, paint)
        paint.color = Color.rgb(220, 38, 38); canvas.drawText("ABSENT  $absent", 166f, 165f, paint)
        paint.color = Color.rgb(217, 119, 6); canvas.drawText("LATE  $late", 286f, 165f, paint)
        paint.color = Color.rgb(2, 132, 199); canvas.drawText("LEAVE  $leave", 386f, 165f, paint)
        paint.color = Color.rgb(51, 65, 85); canvas.drawText("TOTAL  ${rows.size}", 480f, 165f, paint)

        val top = 207f
        paint.color = Color.rgb(30, 41, 59); canvas.drawRect(30f, top, 565f, top + 27f, paint)
        paint.color = Color.WHITE; paint.textSize = 9f
        canvas.drawText("#", 42f, top + 18f, paint); canvas.drawText("STUDENT", 70f, top + 18f, paint)
        canvas.drawText("ID", 286f, top + 18f, paint); canvas.drawText("STATUS", 372f, top + 18f, paint); canvas.drawText("LATE / NOTE", 462f, top + 18f, paint)
        pageRows.forEachIndexed { index, row ->
            val y = top + 27f + index * 23f
            paint.color = if (index % 2 == 0) Color.rgb(248, 250, 252) else Color.WHITE
            canvas.drawRect(30f, y, 565f, y + 23f, paint)
            paint.color = Color.rgb(51, 65, 85); paint.textSize = 9f; paint.isFakeBoldText = false
            canvas.drawText("${pageIndex * 24 + index + 1}", 42f, y + 15f, paint)
            canvas.drawText(row.student.fullName.take(30), 70f, y + 15f, paint)
            canvas.drawText(row.student.studentCode.take(13), 286f, y + 15f, paint)
            val status = row.record.status.replaceFirstChar { it.uppercase() }
            paint.color = when (row.record.status) { "present" -> Color.rgb(22, 163, 74); "absent" -> Color.rgb(220, 38, 38); "late" -> Color.rgb(217, 119, 6); else -> Color.rgb(2, 132, 199) }
            paint.isFakeBoldText = true; canvas.drawText(status, 372f, y + 15f, paint)
            paint.color = Color.rgb(71, 85, 105); paint.isFakeBoldText = false
            val detail = row.record.lateByMinutes?.let { "$it min" } ?: row.record.note.orEmpty()
            canvas.drawText(detail.take(17), 462f, y + 15f, paint)
        }
        paint.color = Color.rgb(100, 116, 139); paint.textSize = 8f
        canvas.drawText("Generated: $generated", 30f, 815f, paint)
        canvas.drawText("BatchFee • Page ${pageIndex + 1} of ${chunks.size}", 420f, 815f, paint)
        document.finishPage(page)
    }

    val safeBatch = batchName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).ifBlank { "batch" }
    val fileName = "attendance_${safeBatch}_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dateMs))}.pdf"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BatchFee")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the download file.")
            context.contentResolver.openOutputStream(uri)?.use(document::writeTo)
                ?: error("Could not write the PDF.")
            uri
        } else {
            val file = File(context.cacheDir, fileName)
            file.outputStream().use(document::writeTo)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    } finally {
        document.close()
    }
}
