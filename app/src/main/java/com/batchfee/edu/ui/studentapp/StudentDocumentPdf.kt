package com.batchfee.edu.ui.studentapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.ui.students.drawLogo
import com.batchfee.edu.ui.students.drawPhoto
import com.batchfee.edu.ui.students.loadBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Student-facing PDF exports. Each document is rendered from the authenticated
 * student's Firestore records, so the file is always a current copy and cannot
 * expose another student's data.
 */
internal suspend fun generateStudentReceiptPdf(
    context: Context,
    institute: InstituteEntity,
    student: StudentEntity,
    receipt: StudentReceiptDocument
): File {
    val document = PdfDocument()
    val pageWidth = 440
    val pageHeight = 610
    val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
    val canvas = page.canvas
    val navy = Color.rgb(7, 38, 72)
    val blue = Color.rgb(37, 99, 235)
    val cyan = Color.rgb(34, 211, 238)
    val green = Color.rgb(16, 185, 129)
    val red = Color.rgb(239, 68, 68)
    val ink = Color.rgb(15, 23, 42)
    val muted = Color.rgb(100, 116, 139)
    val line = Color.rgb(226, 232, 240)
    val pale = Color.rgb(248, 250, 252)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = line }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f; isFakeBoldText = true }
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 11f }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 9f; isFakeBoldText = true }
    val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 12f; isFakeBoldText = true }

    canvas.drawColor(Color.WHITE)
    fill.color = navy
    canvas.drawRoundRect(RectF(16f, 14f, 424f, 140f), 18f, 18f, fill)
    fill.color = Color.argb(40, 255, 255, 255)
    canvas.drawCircle(387f, 42f, 48f, fill)
    canvas.drawCircle(354f, 102f, 30f, fill)
    fill.color = cyan
    canvas.drawCircle(405f, 125f, 13f, fill)
    val logo = loadBitmap(context, institute.profilePhotoUri)
    drawLogo(canvas, logo, institute.name, 32f, 32f, 48f, navy, cyan)
    canvas.drawText(pdfFit(institute.name.uppercase(Locale.getDefault()), title, 205f), 94f, 56f, title)
    val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(186, 230, 253); textSize = 9f; isFakeBoldText = true }
    canvas.drawText("OFFICIAL PAYMENT RECEIPT", 94f, 75f, sub)
    val right = Paint(sub).apply { textAlign = Paint.Align.RIGHT }
    canvas.drawText("RECEIPT #${pdfFit(receipt.receiptNumber, right, 130f)}", 406f, 52f, right)
    canvas.drawText(formatStudentPdfDate(receipt.dateMs), 406f, 72f, right)
    institute.phone?.takeIf { it.isNotBlank() }?.let { canvas.drawText("Contact: $it", 406f, 92f, right) }
    logo?.recycle()

    var y = 160f
    pdfCard(canvas, fill, stroke, 26f, y, 414f, 82f)
    value.textSize = 15f
    canvas.drawText(pdfFit(student.fullName, value, 290f), 44f, y + 28f, value)
    body.color = muted; body.textSize = 10f
    canvas.drawText("${student.studentCode}  •  ${student.phone ?: student.guardianPhone ?: ""}", 44f, y + 47f, body)
    canvas.drawText("${student.className ?: "Student"}  •  ${receipt.feeLabel}", 44f, y + 65f, body)
    y += 98f

    pdfCard(canvas, fill, stroke, 26f, y, 414f, 130f)
    pdfSection(canvas, "FEE DETAILS", 44f, y + 19f, blue, label)
    pdfRow(canvas, "Fee amount", "BDT ${formatStudentMoney(receipt.totalAmount)}", 44f, y + 45f, 396f, label, value, ink)
    pdfRow(canvas, "Paid", "BDT ${formatStudentMoney(receipt.paidAmount)}", 44f, y + 70f, 396f, label, value, green)
    pdfRow(canvas, "Remaining due", "BDT ${formatStudentMoney(receipt.dueAmount)}", 44f, y + 95f, 396f, label, value, if (receipt.dueAmount > 0.0) red else green)
    y += 146f

    pdfCard(canvas, fill, stroke, 26f, y, 414f, 89f)
    pdfSection(canvas, "PAYMENT CONFIRMATION", 44f, y + 20f, blue, label)
    value.color = blue; value.textSize = 23f; value.textAlign = Paint.Align.RIGHT
    canvas.drawText("BDT ${formatStudentMoney(receipt.paidAmount)}", 396f, y + 46f, value)
    value.textAlign = Paint.Align.LEFT
    pdfRow(canvas, "Method", receipt.paymentMethod.ifBlank { "Cash" }, 44f, y + 70f, 396f, label, value, ink)
    y += 105f

    stroke.color = line
    canvas.drawLine(40f, y + 4f, 190f, y + 4f, stroke)
    canvas.drawLine(250f, y + 4f, 400f, y + 4f, stroke)
    val centered = Paint(label).apply { textAlign = Paint.Align.CENTER }
    canvas.drawText("Received by", 115f, y + 22f, centered)
    canvas.drawText("Authorised signature", 325f, y + 22f, centered)

    fill.color = navy
    canvas.drawRoundRect(RectF(16f, 544f, 424f, 594f), 14f, 14f, fill)
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(207, 250, 254); textSize = 9f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Student copy • ${institute.name}", 220f, 565f, footer)
    canvas.drawText("This receipt is generated from the institute payment ledger.", 220f, 580f, footer)

    document.finishPage(page)
    val output = exportFile(context, "receipt_${safePdfId(receipt.receiptNumber)}.pdf")
    output.outputStream().use(document::writeTo)
    document.close()
    return output
}

internal suspend fun generateStudentResultCardPdf(
    context: Context,
    institute: InstituteEntity,
    student: StudentEntity,
    result: StudentResultDocument
): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(544, 343, 1).create())
    val canvas = page.canvas
    val navy = Color.rgb(7, 38, 72)
    val blue = Color.rgb(37, 99, 235)
    val cyan = Color.rgb(34, 211, 238)
    val violet = Color.rgb(124, 58, 237)
    val green = Color.rgb(22, 163, 74)
    val ink = Color.rgb(15, 23, 42)
    val muted = Color.rgb(100, 116, 139)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f; isFakeBoldText = true }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(191, 219, 254); textSize = 8f; isFakeBoldText = true }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 8f; isFakeBoldText = true }
    val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 13f; isFakeBoldText = true }

    canvas.drawColor(Color.WHITE)
    fill.color = Color.rgb(239, 246, 255)
    canvas.drawCircle(500f, 280f, 110f, fill)
    fill.color = navy
    canvas.drawRect(0f, 0f, 544f, 81f, fill)
    fill.color = blue
    val slant = android.graphics.Path().apply { moveTo(333f, 0f); lineTo(544f, 0f); lineTo(544f, 81f); lineTo(280f, 81f); close() }
    canvas.drawPath(slant, fill)
    val logo = loadBitmap(context, institute.profilePhotoUri)
    drawLogo(canvas, logo, institute.name, 17f, 18f, 42f, navy, cyan)
    canvas.drawText(pdfFit(institute.name.uppercase(Locale.getDefault()), title, 255f), 72f, 39f, title)
    institute.phone?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, 72f, 55f, small) }
    val topRight = Paint(small).apply { textAlign = Paint.Align.RIGHT; color = Color.WHITE }
    canvas.drawText("STUDENT RESULT CARD", 526f, 39f, topRight)
    canvas.drawText(result.dateMs?.let(::formatStudentPdfDate) ?: "Published", 526f, 55f, topRight)
    logo?.recycle()

    val photo = loadBitmap(context, student.photoUri)
    drawPhoto(canvas, photo, student.fullName, 23f, 105f, 92f, 118f, navy, Color.rgb(203, 213, 225))
    photo?.recycle()
    canvas.drawText(pdfFit(student.fullName, value.apply { textSize = 19f }, 310f), 143f, 127f, value)
    value.textSize = 10f; value.color = muted
    canvas.drawText("ID  •  ${student.studentCode}", 143f, 147f, value)
    canvas.drawText("Class  •  ${student.className ?: "N/A"}", 143f, 164f, value)
    value.color = ink; value.textSize = 16f
    canvas.drawText(pdfFit(result.title, value, 350f), 143f, 198f, value)
    result.subject?.takeIf { it.isNotBlank() }?.let { subject ->
        value.color = cyan; value.textSize = 10f
        canvas.drawText(subject, 143f, 216f, value)
    }

    val percentage = if (result.totalMarks > 0) result.obtainedMarks / result.totalMarks * 100 else 0.0
    val metrics: List<Pair<String, String>> = listOf(
        "MARKS" to (if (result.totalMarks > 0) "${formatStudentNumber(result.obtainedMarks)} / ${formatStudentNumber(result.totalMarks)}" else formatStudentNumber(result.obtainedMarks)),
        "GRADE" to (result.grade ?: "—"),
        "POSITION" to (result.position?.let { "#$it" } ?: "-")
    )
    metrics.forEachIndexed { index, (metricLabel, metricValue) ->
        val left = 28f + index * 168f
        fill.color = if (index == 1) Color.rgb(245, 243, 255) else Color.rgb(248, 250, 252)
        canvas.drawRoundRect(RectF(left, 248f, left + 151f, 309f), 12f, 12f, fill)
        canvas.drawText(metricLabel, left + 14f, 267f, label)
        value.color = if (index == 1) violet else if (index == 0) green else ink
        value.textSize = 18f
        canvas.drawText(pdfFit(metricValue, value, 125f), left + 14f, 292f, value)
    }
    val pct = Paint(label).apply { textAlign = Paint.Align.RIGHT; color = if (percentage >= 40) green else Color.rgb(239, 68, 68) }
    canvas.drawText("${formatStudentNumber(percentage)}%", 520f, 233f, pct)

    document.finishPage(page)
    val output = exportFile(context, "result_card_${safePdfId(result.id)}.pdf")
    output.outputStream().use(document::writeTo)
    document.close()
    return output
}

internal fun formatStudentMoney(amount: Double): String = "%,.0f".format(Locale.US, amount)

private fun formatStudentNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.US, value)

private fun formatStudentPdfDate(value: Long): String = if (value <= 0L) "—" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(value))

private fun pdfCard(canvas: Canvas, fill: Paint, stroke: Paint, left: Float, top: Float, right: Float, height: Float) {
    fill.color = Color.WHITE
    canvas.drawRoundRect(RectF(left, top, right, top + height), 14f, 14f, fill)
    canvas.drawRoundRect(RectF(left, top, right, top + height), 14f, 14f, stroke)
}

private fun pdfSection(canvas: Canvas, text: String, x: Float, y: Float, color: Int, paint: Paint) {
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 2.5f }
    canvas.drawLine(x, y - 3f, x + 28f, y - 3f, line)
    paint.color = color
    canvas.drawText(text, x + 38f, y, paint)
}

private fun pdfRow(canvas: Canvas, key: String, content: String, x: Float, y: Float, right: Float, label: Paint, value: Paint, valueColor: Int) {
    label.color = Color.rgb(100, 116, 139); label.textAlign = Paint.Align.LEFT
    canvas.drawText(key, x, y, label)
    value.color = valueColor; value.textSize = 11f; value.textAlign = Paint.Align.RIGHT
    canvas.drawText(pdfFit(content, value, right - x - 100f), right, y, value)
    value.textAlign = Paint.Align.LEFT
}

private fun pdfFit(text: String, paint: Paint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    val suffix = "..."
    var end = text.length
    while (end > 0 && paint.measureText(text.take(end) + suffix) > width) end--
    return text.take(end) + suffix
}

private fun exportFile(context: Context, name: String): File = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, name) }

private fun safePdfId(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
