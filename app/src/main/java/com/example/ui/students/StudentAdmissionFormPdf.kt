package com.batchfee.edu.ui.students

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ADMISSION_FORM_WIDTH = 595
private const val ADMISSION_FORM_HEIGHT = 842

/** A print-ready A4 admission form kept in app cache for secure sharing. */
internal fun generateStudentAdmissionFormPdf(
    context: Context,
    institute: InstituteEntity,
    student: StudentEntity
): File {
    val document = PdfDocument()
    val page = document.startPage(
        PdfDocument.PageInfo.Builder(ADMISSION_FORM_WIDTH, ADMISSION_FORM_HEIGHT, 1).create()
    )
    val canvas = page.canvas
    val width = ADMISSION_FORM_WIDTH.toFloat()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 59); textSize = 9.5f }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(71, 85, 105); textSize = 8.5f; isFakeBoldText = true }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    val navy = Color.rgb(8, 29, 51)
    val cyan = Color.rgb(6, 182, 212)
    val slate = Color.rgb(226, 232, 240)
    val paleCyan = Color.rgb(236, 254, 255)

    // Institute header
    fill.color = navy
    canvas.drawRect(0f, 0f, width, 142f, fill)
    drawLogo(canvas, loadBitmap(context, institute.profilePhotoUri), institute.name, 42f, 39f, 64f, navy, cyan)

    val instituteName = institute.name.ifBlank { "Institute" }
    val white = Paint(title).apply { textAlign = Paint.Align.LEFT; textSize = 18f }
    canvas.drawText(instituteName.take(46), 120f, 49f, white)
    val headerSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 243, 252); textSize = 9.5f }
    val contactLine = listOfNotNull(
        institute.instituteCode?.takeIf { it.isNotBlank() }?.let { "Code: $it" },
        institute.phone?.takeIf { it.isNotBlank() },
        institute.email?.takeIf { it.isNotBlank() }
    ).joinToString("  |  ")
    canvas.drawText(contactLine.take(76), 120f, 68f, headerSub)
    canvas.drawText((institute.address ?: "").take(78), 120f, 84f, headerSub)

    title.textSize = 17f
    canvas.drawText("STUDENT ADMISSION FORM", width / 2f, 120f, title)
    val issuedAt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
    val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(186, 230, 253); textSize = 8.5f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Generated on $issuedAt", width / 2f, 135f, meta)

    var y = 164f
    // Form no. strip
    fill.color = paleCyan
    canvas.drawRoundRect(RectF(32f, y, width - 32f, y + 34f), 6f, 6f, fill)
    label.color = Color.rgb(8, 145, 178)
    canvas.drawText("ADMISSION NO.", 46f, y + 15f, label)
    val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 12f; isFakeBoldText = true }
    canvas.drawText(student.studentCode.ifBlank { student.id }, 46f, y + 28f, codePaint)
    label.textAlign = Paint.Align.RIGHT
    canvas.drawText("ADMISSION DATE", width - 46f, y + 15f, label)
    val dateText = if (student.admissionDateMs > 0L) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(student.admissionDateMs))
    } else "N/A"
    codePaint.textAlign = Paint.Align.RIGHT; codePaint.textSize = 10f
    canvas.drawText(dateText, width - 46f, y + 28f, codePaint)
    label.textAlign = Paint.Align.LEFT; codePaint.textAlign = Paint.Align.LEFT
    y += 58f

    sectionHeading(canvas, "STUDENT INFORMATION", 32f, y, cyan)
    y += 16f
    val studentPhoto = loadBitmap(context, student.photoUri)
    drawPhoto(canvas, studentPhoto, student.fullName, width - 126f, y, 94f, 112f, navy, slate)
    y = drawField(canvas, "Student Name", student.fullName, 32f, y, 250f, text, label, stroke, slate)
    y = drawField(canvas, "Gender", student.gender ?: "N/A", 32f, y, 250f, text, label, stroke, slate)
    y = drawField(canvas, "Date of Birth", formatDate(student.dateOfBirthMs), 32f, y, 250f, text, label, stroke, slate)
    y = drawField(canvas, "Class / Group", student.className ?: "N/A", 32f, y, 250f, text, label, stroke, slate)
    y += 14f

    sectionHeading(canvas, "CONTACT & GUARDIAN INFORMATION", 32f, y, cyan)
    y += 16f
    val left = 32f; val right = 304f; val fieldWidth = 256f
    val row1 = y
    drawFieldAt(canvas, "Student Mobile", student.phone ?: "N/A", left, row1, fieldWidth, text, label, stroke, slate)
    drawFieldAt(canvas, "Email", student.email ?: "N/A", right, row1, fieldWidth, text, label, stroke, slate)
    val row2 = row1 + 42f
    drawFieldAt(canvas, "Guardian Name", student.guardianName ?: "N/A", left, row2, fieldWidth, text, label, stroke, slate)
    drawFieldAt(canvas, "Guardian Mobile", student.guardianPhone ?: "N/A", right, row2, fieldWidth, text, label, stroke, slate)
    val row3 = row2 + 42f
    drawFieldAt(canvas, "School / College", student.schoolName ?: "N/A", left, row3, fieldWidth, text, label, stroke, slate)
    drawFieldAt(canvas, "Blood Group", student.bloodGroup ?: "N/A", right, row3, fieldWidth, text, label, stroke, slate)
    y = row3 + 58f

    sectionHeading(canvas, "ADDRESS", 32f, y, cyan)
    y += 18f
    drawMultiLineField(canvas, student.address ?: "N/A", 32f, y, width - 64f, 42f, text, stroke, slate)
    y += 64f

    // Declaration and signatures
    sectionHeading(canvas, "DECLARATION", 32f, y, cyan)
    y += 20f
    val declaration = "I confirm that the information provided above is correct. I agree to follow the institute's rules and fee policy."
    drawWrappedText(canvas, declaration, 36f, y, width - 72f, text.apply { color = Color.rgb(51, 65, 85); textSize = 9.5f }, 14f)
    y += 48f
    stroke.color = Color.rgb(100, 116, 139)
    canvas.drawLine(45f, y + 18f, 218f, y + 18f, stroke)
    canvas.drawLine(width - 218f, y + 18f, width - 45f, y + 18f, stroke)
    label.color = Color.rgb(71, 85, 105); label.textAlign = Paint.Align.CENTER
    canvas.drawText("Parent / Guardian Signature", 131f, y + 33f, label)
    canvas.drawText("Authorized Signature", width - 131f, y + 33f, label)
    label.textAlign = Paint.Align.LEFT

    // Footer
    fill.color = navy
    canvas.drawRect(0f, 800f, width, ADMISSION_FORM_HEIGHT.toFloat(), fill)
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(207, 250, 254); textSize = 8f; textAlign = Paint.Align.CENTER }
    canvas.drawText("This is a computer-generated admission form from $instituteName.", width / 2f, 819f, footer)
    canvas.drawText("Please keep this document for institute records.", width / 2f, 833f, footer)

    document.finishPage(page)
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeId = student.studentCode.ifBlank { student.id }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(directory, "admission_form_$safeId.pdf")
    file.outputStream().use(document::writeTo)
    document.close()
    return file
}

internal fun openStudentPdf(context: Context, file: File, label: String): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        clipData = ClipData.newRawUri(label, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
    true
} catch (_: Exception) { false }

internal fun shareStudentPdfToWhatsApp(context: Context, file: File, label: String): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, label)
        clipData = ClipData.newRawUri(label, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val target = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { packageName ->
        intent.setPackage(packageName)
        intent.resolveActivity(context.packageManager) != null
    } ?: return false
    intent.setPackage(target)
    context.startActivity(intent)
    true
} catch (_: Exception) { false }

internal fun openStudentAdmissionFormPdf(context: Context, file: File): Boolean =
    openStudentPdf(context, file, "Student admission form")

internal fun shareStudentAdmissionFormToWhatsApp(context: Context, file: File): Boolean =
    shareStudentPdfToWhatsApp(context, file, "Student admission form")

private fun sectionHeading(canvas: Canvas, value: String, x: Float, y: Float, accent: Int) {
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 3f }
    val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 10f; isFakeBoldText = true }
    canvas.drawLine(x, y, x + 38f, y, line)
    canvas.drawText(value, x + 48f, y + 3f, heading)
}

private fun drawField(
    canvas: Canvas, field: String, value: String, x: Float, y: Float, width: Float,
    text: Paint, label: Paint, stroke: Paint, border: Int
): Float {
    drawFieldAt(canvas, field, value, x, y, width, text, label, stroke, border)
    return y + 42f
}

private fun drawFieldAt(
    canvas: Canvas, field: String, value: String, x: Float, y: Float, width: Float,
    text: Paint, label: Paint, stroke: Paint, border: Int
) {
    stroke.color = border
    canvas.drawRoundRect(RectF(x, y, x + width, y + 32f), 4f, 4f, stroke)
    label.color = Color.rgb(100, 116, 139); label.textAlign = Paint.Align.LEFT
    canvas.drawText(field.uppercase(Locale.getDefault()), x + 8f, y + 11f, label)
    text.color = Color.rgb(30, 41, 59); text.textSize = 10.5f
    canvas.drawText(value.take(39), x + 8f, y + 25f, text)
}

private fun drawMultiLineField(canvas: Canvas, value: String, x: Float, y: Float, width: Float, height: Float, text: Paint, stroke: Paint, border: Int) {
    stroke.color = border
    canvas.drawRoundRect(RectF(x, y, x + width, y + height), 4f, 4f, stroke)
    drawWrappedText(canvas, value, x + 8f, y + 15f, width - 16f, text, 13f)
}

private fun drawWrappedText(canvas: Canvas, value: String, x: Float, startY: Float, maxWidth: Float, paint: Paint, lineHeight: Float) {
    var y = startY
    value.split(Regex("\\s+")).fold("") { line, word ->
        val candidate = if (line.isBlank()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
            canvas.drawText(line, x, y, paint)
            y += lineHeight
            word
        } else candidate
    }.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, x, y, paint) }
}

internal fun drawLogo(canvas: Canvas, bitmap: Bitmap?, name: String, x: Float, y: Float, size: Float, navy: Int, cyan: Int) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    if (bitmap != null) {
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + size, y + size), fill)
    } else {
        fill.color = cyan
        canvas.drawCircle(x + size / 2f, y + size / 2f, size / 2f, fill)
        val initials = name.trim().split(Regex("\\s+")).take(2).joinToString("") { it.take(1) }.ifBlank { "I" }
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        canvas.drawText(initials.uppercase(Locale.getDefault()), x + size / 2f, y + 39f, letter)
    }
}

internal fun drawPhoto(canvas: Canvas, bitmap: Bitmap?, name: String, x: Float, y: Float, width: Float, height: Float, navy: Int, border: Int) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fill.color = Color.rgb(248, 250, 252)
    canvas.drawRect(x, y, x + width, y + height, fill)
    if (bitmap != null) canvas.drawBitmap(bitmap, null, RectF(x + 3f, y + 3f, x + width - 3f, y + height - 3f), fill)
    else {
        fill.color = Color.rgb(207, 250, 254)
        canvas.drawCircle(x + width / 2f, y + 43f, 24f, fill)
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        canvas.drawText(name.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "S" }, x + width / 2f, y + 51f, letter)
        val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 116, 139); textSize = 7f; textAlign = Paint.Align.CENTER }
        canvas.drawText("STUDENT PHOTO", x + width / 2f, y + height - 10f, hint)
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = 1f }
    canvas.drawRect(x, y, x + width, y + height, stroke)
}

internal fun loadBitmap(context: Context, source: String?): Bitmap? {
    if (source.isNullOrBlank()) return null
    return try {
        when {
            source.startsWith("http://") || source.startsWith("https://") -> URL(source).openConnection().apply {
                connectTimeout = 4_000; readTimeout = 4_000
            }.getInputStream().use(BitmapFactory::decodeStream)
            source.startsWith("content://") || source.startsWith("file://") -> context.contentResolver.openInputStream(Uri.parse(source))?.use(BitmapFactory::decodeStream)
            else -> BitmapFactory.decodeFile(source)
        }
    } catch (_: Exception) { null }
}

private fun formatDate(value: Long?): String = if (value != null && value > 0L) {
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(value))
} else "N/A"
