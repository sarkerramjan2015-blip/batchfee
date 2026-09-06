package com.batchfee.edu.ui.batches

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.batchfee.edu.data.models.CustomRoutineEntity
import com.batchfee.edu.data.models.CustomRoutineEntryEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.ui.students.drawLogo
import com.batchfee.edu.ui.students.loadBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Page geometry (landscape A4) ──────────────────────────────
private const val CUSTOM_PDF_WIDTH = 842
private const val CUSTOM_PDF_HEIGHT = 595
private const val TABLE_TOP = 184f
private const val COL_HEADER_H = 28f
private const val ROW_H = 49f
private const val FOOTER_Y = CUSTOM_PDF_HEIGHT - 38f

// ── Table columns ─────────────────────────────────────────────
private const val TABLE_LEFT = 28f
private const val TABLE_RIGHT = 814f
private const val DAYS_COL_W = 96f
private const val PERIOD_START = TABLE_LEFT + DAYS_COL_W

// ── Brand colors ──────────────────────────────────────────────
private val CustNavy = Color.rgb(5, 27, 50)
private val CustDeepBlue = Color.rgb(19, 69, 158)
private val CustCyan = Color.rgb(34, 211, 238)
private val CustPaleCyan = Color.rgb(236, 254, 255)
private val CustSlate = Color.rgb(71, 85, 105)
private val CustBorder = Color.rgb(203, 213, 225)
private val CustRowAlt = Color.rgb(248, 250, 252)

/**
 * Generates a polished landscape A4 weekly custom-routine PDF.
 *
 * The routine table is a timetable matrix:
 *  - first column = day name (Saturday … Friday),
 *  - following columns = 1st, 2nd, 3rd, … periods (count is owner-configurable),
 *  - every period cell stacks subject name / teacher name / time.
 *
 * The institute logo is drawn as a soft watermark behind the table.
 * Print / share are handled by the existing [shareRoutinePdf] /
 * [printRoutinePdf] helpers in RoutinePdfGenerator.kt.
 */
internal suspend fun generateCustomRoutinePdf(
    context: Context,
    institute: InstituteEntity,
    routine: CustomRoutineEntity,
    entries: List<CustomRoutineEntryEntity>
): File {
    val document = PdfDocument()
    val logo = loadBitmap(context, institute.profilePhotoUri)
    val periodCount = routine.periodCount.coerceIn(1, 14)
    val rows = buildDayRows(entries, periodCount)
    val pages = paginateRows(rows)
    val generatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

    pages.forEachIndexed { pageIndex, pageRows ->
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(CUSTOM_PDF_WIDTH, CUSTOM_PDF_HEIGHT, pageIndex + 1).create()
        )
        drawCustomRoutinePage(
            canvas = page.canvas,
            institute = institute,
            routine = routine,
            logo = logo,
            rows = pageRows,
            periodCount = periodCount,
            generatedAt = generatedAt,
            pageNumber = pageIndex + 1,
            totalPages = pages.size
        )
        document.finishPage(page)
    }

    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeName = institute.name.ifBlank { "institute" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val safeRoutine = routine.routineName.ifBlank { "routine" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(directory, "custom_routine_${safeName}_${safeRoutine}.pdf")
    file.outputStream().use(document::writeTo)
    document.close()
    return file
}

private data class DayRow(
    val dayIndex: Int,
    val label: String,
    val entries: List<CustomRoutineEntryEntity> // size <= periodCount; empty = no classes
)

/**
 * Builds the timetable rows. Entries are ordered by start time so the Nth entry
 * of a day is that day's Nth period. If a day has more entries than the owner's
 * period count, the overflow continues in extra "(cont.)" rows for that day.
 */
private fun buildDayRows(entries: List<CustomRoutineEntryEntity>, periodCount: Int): List<DayRow> {
    val rows = mutableListOf<DayRow>()
    for (day in 0 until 7) {
        val dayEntries = entries.filter { it.dayIndex == day }.sortedBy { it.startMinutes }
        val dayName = CR_DAY_NAMES.getOrElse(day) { "Day ${day + 1}" }
        if (dayEntries.isEmpty()) {
            rows.add(DayRow(day, dayName, emptyList()))
        } else {
            dayEntries.chunked(periodCount).forEachIndexed { part, partEntries ->
                val label = if (part == 0) dayName else "$dayName (cont.)"
                rows.add(DayRow(day, label, partEntries))
            }
        }
    }
    return rows
}

private fun rowsPerPage(): Int {
    val usable = (CUSTOM_PDF_HEIGHT - 40f) - (TABLE_TOP + COL_HEADER_H)
    return (usable / ROW_H).toInt().coerceAtLeast(1)
}

private fun paginateRows(rows: List<DayRow>): List<List<DayRow>> =
    if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(rowsPerPage())

private fun drawCustomRoutinePage(
    canvas: Canvas,
    institute: InstituteEntity,
    routine: CustomRoutineEntity,
    logo: android.graphics.Bitmap?,
    rows: List<DayRow>,
    periodCount: Int,
    generatedAt: String,
    pageNumber: Int,
    totalPages: Int
) {
    val width = CUSTOM_PDF_WIDTH.toFloat()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    val periodWidth = (TABLE_RIGHT - PERIOD_START) / periodCount

    canvas.drawColor(Color.rgb(248, 250, 252))

    // Branded header — mirrors the batch routine look.
    fill.color = CustNavy
    canvas.drawRect(0f, 0f, width, 126f, fill)
    fill.color = CustDeepBlue
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

    drawLogo(canvas, logo, institute.name, 32f, 28f, 56f, CustNavy, CustCyan)
    val instituteName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f; isFakeBoldText = true }
    canvas.drawText(ellipsizeCustom(institute.name.ifBlank { "Institute" }, instituteName, 290f), 102f, 52f, instituteName)
    val instituteMeta = listOfNotNull(
        institute.phone?.takeIf { it.isNotBlank() },
        institute.instituteCode?.takeIf { it.isNotBlank() }?.let { "Code: $it" }
    ).joinToString("  |  ")
    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 243, 252); textSize = 9f }
    canvas.drawText(ellipsizeCustom(instituteMeta, metaPaint, 310f), 102f, 70f, metaPaint)
    canvas.drawText(ellipsizeCustom(institute.address.orEmpty(), metaPaint, 310f), 102f, 87f, metaPaint)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    canvas.drawText("CUSTOM CLASS ROUTINE", width - 34f, 52f, titlePaint)
    val generatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(191, 219, 254); textSize = 9f; textAlign = Paint.Align.RIGHT }
    canvas.drawText("Generated $generatedAt", width - 34f, 71f, generatedPaint)
    canvas.drawText("Page $pageNumber of $totalPages", width - 34f, 88f, generatedPaint)

    // Routine info band
    fill.color = CustPaleCyan
    canvas.drawRoundRect(RectF(28f, 140f, width - 28f, 180f), 9f, 9f, fill)
    val bandTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CustDeepBlue; textSize = 11f; isFakeBoldText = true }
    canvas.drawText(
        ellipsizeCustom(routine.routineName.uppercase().ifBlank { "CLASS ROUTINE" }, bandTitle, 420f),
        42f, 165f, bandTitle
    )
    val bandMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CustSlate; textSize = 8.5f; textAlign = Paint.Align.RIGHT }
    val routineMeta = listOfNotNull(
        routine.className,
        routine.section?.takeIf { it.isNotBlank() },
        routine.academicSession?.takeIf { it.isNotBlank() }
    ).joinToString(" • ")
    canvas.drawText(ellipsizeCustom(routineMeta.ifBlank { "Weekly routine" }, bandMeta, 330f), width - 42f, 165f, bandMeta)

    // ── Table header: DAYS + period columns (1st, 2nd, 3rd, …) ──
    fill.color = CustNavy
    canvas.drawRoundRect(RectF(TABLE_LEFT, TABLE_TOP, TABLE_RIGHT, TABLE_TOP + COL_HEADER_H), 7f, 7f, fill)
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 9.5f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("DAYS", TABLE_LEFT + DAYS_COL_W / 2f, TABLE_TOP + 18f, headerPaint)
    for (p in 0 until periodCount) {
        val centerX = PERIOD_START + periodWidth * (p + 0.5f)
        canvas.drawText(periodOrdinal(p), centerX, TABLE_TOP + 18f, headerPaint)
    }

    // ── Day rows: each period cell stacks subject / teacher / time ──
    val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CustNavy; textSize = 10f; isFakeBoldText = true }
    var y = TABLE_TOP + COL_HEADER_H
    rows.forEachIndexed { rowIndex, row ->
        fill.color = if (rowIndex % 2 == 0) Color.WHITE else CustRowAlt
        canvas.drawRect(TABLE_LEFT, y, TABLE_RIGHT, y + ROW_H, fill)

        canvas.drawText(row.label, TABLE_LEFT + 10f, y + ROW_H / 2f + dayPaint.textSize * 0.35f, dayPaint)

        row.entries.forEachIndexed { p, entry ->
            drawPeriodCell(canvas, entry, PERIOD_START + periodWidth * p, y, periodWidth)
        }

        line.color = CustBorder
        var x = PERIOD_START
        while (x <= TABLE_RIGHT) {
            canvas.drawLine(x, y, x, y + ROW_H, line)
            x += periodWidth
        }
        canvas.drawLine(TABLE_LEFT, y, TABLE_LEFT, y + ROW_H, line)
        canvas.drawLine(TABLE_LEFT, y + ROW_H, TABLE_RIGHT, y + ROW_H, line)
        y += ROW_H
    }

    // Institute logo watermark behind the table — drawn after the opaque
    // header and alternating row fills so the faded logo stays visible.
    drawTableWatermark(canvas, logo)

    // Footer
    line.color = CustBorder
    canvas.drawLine(TABLE_LEFT, FOOTER_Y, TABLE_RIGHT, FOOTER_Y, line)
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CustSlate; textSize = 8.5f; textAlign = Paint.Align.CENTER }
    canvas.drawText(
        "This routine is generated from BatchFee. Please contact the institute for changes.",
        CUSTOM_PDF_WIDTH / 2f, CUSTOM_PDF_HEIGHT - 19f, footer
    )
}

/** Draws the institute logo as a soft, centered watermark behind the routine table. */
private fun drawTableWatermark(canvas: Canvas, logo: android.graphics.Bitmap?) {
    if (logo == null) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 42 }
    val maxW = 260f
    val maxH = 210f
    val scale = minOf(maxW / logo.width, maxH / logo.height)
    val w = logo.width * scale
    val h = logo.height * scale
    val left = (TABLE_LEFT + TABLE_RIGHT) / 2f - w / 2f
    val top = (TABLE_TOP + FOOTER_Y) / 2f - h / 2f
    canvas.drawBitmap(logo, null, RectF(left, top, left + w, top + h), paint)
}

/**
 * Draws one period cell: subject name on top, teacher name below it, and the
 * class time below the teacher name. The same layout is used for every period.
 */
private fun drawPeriodCell(
    canvas: Canvas,
    entry: CustomRoutineEntryEntity,
    left: Float,
    top: Float,
    cellWidth: Float
) {
    val maxWidth = cellWidth - 14f
    val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 10f; isFakeBoldText = true }
    val teacherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CustSlate; textSize = 8.5f }
    val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CustDeepBlue; textSize = 8.5f; isFakeBoldText = true }

    canvas.drawText(
        ellipsizeCustom(entry.subjectName, subjectPaint, maxWidth),
        left + 7f, top + 16f, subjectPaint
    )
    canvas.drawText(
        ellipsizeCustom(entry.teacherName, teacherPaint, maxWidth),
        left + 7f, top + 28f, teacherPaint
    )
    canvas.drawText(
        ellipsizeCustom("${minuteLabel(entry.startMinutes)} - ${minuteLabel(entry.endMinutes)}", timePaint, maxWidth),
        left + 7f, top + 40f, timePaint
    )
}

private fun periodOrdinal(index: Int): String = when (index) {
    0 -> "1st"
    1 -> "2nd"
    2 -> "3rd"
    else -> "${index + 1}th"
}

private fun minuteLabel(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

private fun ellipsizeCustom(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end--
    return value.take(end) + suffix
}
