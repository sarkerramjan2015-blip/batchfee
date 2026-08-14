package com.batchfee.edu.ui.students

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import com.batchfee.edu.data.models.AttendanceEntity
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.StudentEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal suspend fun generateStudentReportPdf(
    context: Context,
    institute: InstituteEntity,
    student: StudentEntity,
    batches: List<BatchEntity>,
    attendance: List<AttendanceEntity>,
    payments: List<PaymentEntity>,
    totalPaid: Double,
    totalDue: Double
): File = generateStudentDocumentPdf(
    context, institute, student, "STUDENT PERFORMANCE REPORT", "Student Report",
    batches, attendance, payments, emptyList(), totalPaid, totalDue, isFeeSummary = false
)

internal suspend fun generateStudentFeeSummaryPdf(
    context: Context,
    institute: InstituteEntity,
    student: StudentEntity,
    batches: List<BatchEntity>,
    fees: List<FeeEntity>,
    payments: List<PaymentEntity>,
    totalPaid: Double,
    totalDue: Double
): File = generateStudentDocumentPdf(
    context, institute, student, "STUDENT FEE SUMMARY", "Fee Summary",
    batches, emptyList(), payments, fees, totalPaid, totalDue, isFeeSummary = true
)

private suspend fun generateStudentDocumentPdf(
    context: Context,
    institute: InstituteEntity,
    student: StudentEntity,
    heading: String,
    documentLabel: String,
    batches: List<BatchEntity>,
    attendance: List<AttendanceEntity>,
    payments: List<PaymentEntity>,
    fees: List<FeeEntity>,
    totalPaid: Double,
    totalDue: Double,
    isFeeSummary: Boolean
): File {
    val width = 595f
    val height = 842f
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(width.toInt(), height.toInt(), 1).create())
    val canvas = page.canvas
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 9.5f }
    val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 116, 139); textSize = 8.5f }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 10f; isFakeBoldText = true }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240); strokeWidth = 1f }
    val navy = Color.rgb(8, 29, 51)
    val cyan = Color.rgb(6, 182, 212)
    val blue = Color.rgb(37, 99, 235)
    val green = Color.rgb(22, 163, 74)
    val amber = Color.rgb(217, 119, 6)

    fill.shader = LinearGradient(0f, 0f, width, 155f, navy, blue, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, 155f, fill)
    fill.shader = null
    drawLogo(canvas, loadBitmap(context, institute.profilePhotoUri), institute.name, 36f, 30f, 62f, navy, cyan)
    val headerName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f; isFakeBoldText = true }
    canvas.drawText(institute.name.ifBlank { "Institute" }.take(45), 116f, 53f, headerName)
    val contact = listOfNotNull(institute.instituteCode?.takeIf { it.isNotBlank() }, institute.phone?.takeIf { it.isNotBlank() })
        .joinToString("  |  ")
    val headerSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(207, 250, 254); textSize = 9f }
    canvas.drawText(contact, 116f, 70f, headerSub)
    canvas.drawText((institute.address ?: "").take(72), 116f, 84f, headerSub)
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(heading, width / 2f, 122f, headingPaint)
    val generated = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(186, 230, 253); textSize = 8.5f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Generated $generated", width / 2f, 138f, meta)

    var y = 178f
    // Student identity card
    fill.color = Color.rgb(248, 250, 252)
    canvas.drawRoundRect(RectF(32f, y, width - 32f, y + 96f), 10f, 10f, fill)
    val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(207, 250, 254); style = Paint.Style.STROKE; strokeWidth = 1f }
    canvas.drawRoundRect(RectF(32f, y, width - 32f, y + 96f), 10f, 10f, cardStroke)
    drawPhoto(canvas, loadBitmap(context, student.photoUri), student.fullName, 48f, y + 11f, 60f, 72f, navy, Color.rgb(203, 213, 225))
    bold.textSize = 16f
    canvas.drawText(student.fullName.take(38), 124f, y + 30f, bold)
    muted.textSize = 9f
    canvas.drawText("Student ID: ${student.studentCode.ifBlank { student.id }}", 124f, y + 47f, muted)
    canvas.drawText("Class: ${student.className ?: "N/A"}   |   Phone: ${student.phone ?: "N/A"}", 124f, y + 62f, muted)
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; textSize = 9f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    canvas.drawText(student.status.uppercase(Locale.getDefault()), width - 48f, y + 31f, statusPaint)
    muted.textAlign = Paint.Align.RIGHT
    canvas.drawText("Admission: ${formatStudentPdfDate(student.admissionDateMs)}", width - 48f, y + 62f, muted)
    muted.textAlign = Paint.Align.LEFT
    y += 117f

    drawMetricCards(canvas, y, totalPaid, totalDue, batches.size, attendance, fees, isFeeSummary, fill, muted, bold, cyan, green, amber)
    y += 80f

    if (!isFeeSummary) {
        studentPdfSection(canvas, "ATTENDANCE & BATCH OVERVIEW", 32f, y, cyan)
        y += 22f
        val present = attendance.count { it.status.equals("present", true) }
        val absent = attendance.count { it.status.equals("absent", true) }
        val attendanceLine = if (attendance.isEmpty()) "No attendance has been recorded yet." else "Recorded: ${attendance.size}   Present: $present   Absent: $absent"
        fill.color = Color.rgb(236, 254, 255)
        canvas.drawRoundRect(RectF(32f, y, width - 32f, y + 31f), 6f, 6f, fill)
        bold.textSize = 10f; bold.color = Color.rgb(8, 145, 178)
        canvas.drawText(attendanceLine, 46f, y + 20f, bold)
        bold.color = Color.rgb(15, 23, 42)
        y += 52f
        studentPdfSection(canvas, "ENROLLED BATCHES", 32f, y, cyan)
        y += 20f
        drawBatchRows(canvas, batches, y, width, fill, text, muted, line)
        y += if (batches.isEmpty()) 34f else minOf(batches.size, 4) * 27f + 16f
        studentPdfSection(canvas, "RECENT FEE COLLECTIONS", 32f, y, cyan)
        y += 20f
        drawPaymentRows(canvas, payments, y, width, fill, text, muted, line)
    } else {
        studentPdfSection(canvas, "FEE LEDGER", 32f, y, cyan)
        y += 20f
        drawFeeRows(canvas, fees, y, width, fill, text, muted, line, green, amber)
        y += minOf(fees.filter { it.cancelledAtMs == null }.size, 8) * 27f + 22f
        studentPdfSection(canvas, "RECENT PAYMENTS", 32f, y, cyan)
        y += 20f
        drawPaymentRows(canvas, payments, y, width, fill, text, muted, line)
    }

    fill.color = navy
    canvas.drawRect(0f, 800f, width, height, fill)
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(207, 250, 254); textSize = 8f; textAlign = Paint.Align.CENTER }
    canvas.drawText("$documentLabel - ${institute.name.ifBlank { "Institute" }}", width / 2f, 819f, footer)
    canvas.drawText("Computer-generated document. Keep for institute records.", width / 2f, 833f, footer)

    document.finishPage(page)
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val id = student.studentCode.ifBlank { student.id }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val prefix = if (isFeeSummary) "fee_summary" else "student_report"
    val file = File(directory, "${prefix}_$id.pdf")
    file.outputStream().use(document::writeTo)
    document.close()
    return file
}

private fun drawMetricCards(
    canvas: Canvas, y: Float, paid: Double, due: Double, batchCount: Int, attendance: List<AttendanceEntity>, fees: List<FeeEntity>, isFeeSummary: Boolean,
    fill: Paint, muted: Paint, bold: Paint, cyan: Int, green: Int, amber: Int
) {
    val items = if (!isFeeSummary) listOf(
        "BATCHES" to batchCount.toString(),
        "ATTENDANCE" to attendance.size.toString(),
        "PRESENT" to attendance.count { it.status.equals("present", true) }.toString(),
        "DUE" to "BDT ${studentPdfMoney(due)}"
    ) else listOf(
        "TOTAL PAID" to "BDT ${studentPdfMoney(paid)}",
        "PENDING DUE" to "BDT ${studentPdfMoney(due)}",
        "FEE ITEMS" to fees.count { it.cancelledAtMs == null }.toString(),
        "PAYMENT STATUS" to if (due <= 0.0) "CLEAR" else "DUE"
    )
    val colors = listOf(cyan, green, Color.rgb(99, 102, 241), amber)
    items.forEachIndexed { index, (label, value) ->
        val x = 32f + index * 136f
        fill.color = Color.rgb(248, 250, 252)
        canvas.drawRoundRect(RectF(x, y, x + 123f, y + 59f), 8f, 8f, fill)
        fill.color = colors[index]
        canvas.drawRoundRect(RectF(x, y, x + 4f, y + 59f), 4f, 4f, fill)
        muted.textSize = 7.5f; muted.color = Color.rgb(100, 116, 139)
        canvas.drawText(label, x + 12f, y + 19f, muted)
        bold.textSize = 11f; bold.color = Color.rgb(15, 23, 42)
        canvas.drawText(value.take(19), x + 12f, y + 40f, bold)
    }
}

private fun studentPdfSection(canvas: Canvas, title: String, x: Float, y: Float, accent: Int) {
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 3f }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 10f; isFakeBoldText = true }
    canvas.drawLine(x, y, x + 36f, y, line)
    canvas.drawText(title, x + 46f, y + 3f, label)
}

private fun drawBatchRows(canvas: Canvas, batches: List<BatchEntity>, y: Float, width: Float, fill: Paint, text: Paint, muted: Paint, line: Paint) {
    if (batches.isEmpty()) {
        muted.textSize = 9f; canvas.drawText("No batch assigned", 42f, y + 14f, muted); return
    }
    batches.take(4).forEachIndexed { index, batch ->
        val rowY = y + index * 27f
        if (index % 2 == 0) { fill.color = Color.rgb(248, 250, 252); canvas.drawRect(32f, rowY, width - 32f, rowY + 24f, fill) }
        text.textSize = 9f; canvas.drawText(batch.name.take(39), 42f, rowY + 15f, text)
        muted.textAlign = Paint.Align.RIGHT; canvas.drawText((batch.subject ?: batch.className ?: "Active").take(24), width - 42f, rowY + 15f, muted); muted.textAlign = Paint.Align.LEFT
        canvas.drawLine(32f, rowY + 25f, width - 32f, rowY + 25f, line)
    }
}

private fun drawPaymentRows(canvas: Canvas, payments: List<PaymentEntity>, y: Float, width: Float, fill: Paint, text: Paint, muted: Paint, line: Paint) {
    if (payments.isEmpty()) { muted.textSize = 9f; canvas.drawText("No payment collected yet", 42f, y + 14f, muted); return }
    payments.sortedByDescending { it.paymentDateMs }.take(4).forEachIndexed { index, payment ->
        val rowY = y + index * 27f
        if (index % 2 == 0) { fill.color = Color.rgb(248, 250, 252); canvas.drawRect(32f, rowY, width - 32f, rowY + 24f, fill) }
        text.textSize = 9f; canvas.drawText(payment.receiptNumber.ifBlank { "Payment" }.take(27), 42f, rowY + 15f, text)
        muted.textAlign = Paint.Align.CENTER; canvas.drawText(formatStudentPdfDate(payment.paymentDateMs), width / 2f, rowY + 15f, muted)
        val amount = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 163, 74); textSize = 9f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        canvas.drawText("BDT ${studentPdfMoney(payment.amount)}", width - 42f, rowY + 15f, amount); muted.textAlign = Paint.Align.LEFT
        canvas.drawLine(32f, rowY + 25f, width - 32f, rowY + 25f, line)
    }
}

private fun drawFeeRows(canvas: Canvas, fees: List<FeeEntity>, y: Float, width: Float, fill: Paint, text: Paint, muted: Paint, line: Paint, green: Int, amber: Int) {
    val activeFees = fees.filter { it.cancelledAtMs == null }.sortedByDescending { it.updatedAtMs }.take(8)
    if (activeFees.isEmpty()) { muted.textSize = 9f; canvas.drawText("No fee ledger entry found", 42f, y + 14f, muted); return }
    activeFees.forEachIndexed { index, fee ->
        val rowY = y + index * 27f
        if (index % 2 == 0) { fill.color = Color.rgb(248, 250, 252); canvas.drawRect(32f, rowY, width - 32f, rowY + 24f, fill) }
        text.textSize = 9f; canvas.drawText(fee.feePeriod.take(23), 42f, rowY + 15f, text)
        muted.textAlign = Paint.Align.CENTER; canvas.drawText("Paid ${studentPdfMoney(fee.paidAmount)}", width / 2f, rowY + 15f, muted)
        val due = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (fee.dueAmount > 0) amber else green; textSize = 9f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        canvas.drawText(if (fee.dueAmount > 0) "Due ${studentPdfMoney(fee.dueAmount)}" else "Paid", width - 42f, rowY + 15f, due); muted.textAlign = Paint.Align.LEFT
        canvas.drawLine(32f, rowY + 25f, width - 32f, rowY + 25f, line)
    }
}

private fun studentPdfMoney(value: Double): String = "%,.0f".format(Locale.US, value)
private fun formatStudentPdfDate(value: Long): String = if (value > 0) SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(value)) else "N/A"
