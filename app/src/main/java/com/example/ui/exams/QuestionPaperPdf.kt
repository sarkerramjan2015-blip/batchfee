package com.batchfee.edu.ui.exams

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.repository.ReviewableQuestion
import com.batchfee.edu.ui.students.drawLogo
import com.batchfee.edu.ui.students.loadBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class QuestionPaperSize(val label: String, val width: Int, val height: Int) {
    A4("A4", 595, 842),
    LEGAL("Legal", 612, 1008),
}

internal enum class QuestionPaperMargin(val label: String, val points: Float) {
    COMPACT("Narrow", 26f),
    STANDARD("Normal", 38f),
    RELAXED("Wide", 52f),
}

internal enum class QuestionPaperFontSize(val label: String, val points: Float) {
    COMPACT("Compact", 10.5f),
    STANDARD("Standard", 12f),
    LARGE("Large", 14f),
}

internal data class QuestionPaperSetup(
    val examName: String,
    val className: String,
    val subject: String,
    val chapter: String,
    val totalMarks: Int,
    val durationMinutes: Int,
    val paperSize: QuestionPaperSize = QuestionPaperSize.A4,
    val margin: QuestionPaperMargin = QuestionPaperMargin.STANDARD,
    val fontSize: QuestionPaperFontSize = QuestionPaperFontSize.STANDARD,
    val includeAnswerKey: Boolean = false,
)

/**
 * Generates an institute-owned question paper. The visual document intentionally
 * contains no BatchFee name, logo or watermark; tenant branding is the only branding.
 */
internal suspend fun generateQuestionPaperPdf(
    context: Context,
    institute: InstituteEntity,
    setup: QuestionPaperSetup,
    questions: List<ReviewableQuestion>,
): File = withContext(Dispatchers.IO) {
    require(institute.name.isNotBlank()) { "Your institute name is required before creating a question paper." }
    require(questions.isNotEmpty()) { "Select at least one question for the paper." }
    val document = PdfDocument()
    val logo = loadBitmap(context, institute.profilePhotoUri)
    val pageSize = setup.paperSize
    val margin = setup.margin.points
    val contentWidth = pageSize.width - (margin * 2)
    val questionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 42)
        textSize = setup.fontSize.points
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val questionBold = Paint(questionText).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(71, 85, 105); textSize = setup.fontSize.points * .78f }
    val generatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    val allQuestions = questions.filter { it.selected }
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = 0f

    fun startPage(sectionLabel: String) {
        page?.let(document::finishPage)
        pageNumber += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(pageSize.width, pageSize.height, pageNumber).create())
        canvas = page!!.canvas
        val target = canvas!!
        target.drawColor(Color.WHITE)
        drawQuestionPaperWatermark(target, institute.name, pageSize.width.toFloat(), pageSize.height.toFloat())
        drawQuestionPaperHeader(
            canvas = target,
            institute = institute,
            logo = logo,
            title = setup.examName,
            sectionLabel = sectionLabel,
            pageWidth = pageSize.width.toFloat(),
            margin = margin,
        )
        drawQuestionPaperFooter(
            canvas = target,
            instituteName = institute.name,
            generatedAt = generatedAt,
            pageNumber = pageNumber,
            pageWidth = pageSize.width.toFloat(),
            pageHeight = pageSize.height.toFloat(),
            margin = margin,
        )
        y = margin + 104f
        if (sectionLabel == "QUESTION PAPER") {
            val info = buildList {
                if (setup.className.isNotBlank()) add("Class: ${setup.className}")
                if (setup.subject.isNotBlank()) add("Subject: ${setup.subject}")
                if (setup.chapter.isNotBlank()) add("Chapter: ${setup.chapter}")
                if (setup.totalMarks > 0) add("Total marks: ${setup.totalMarks}")
                if (setup.durationMinutes > 0) add("Time: ${setup.durationMinutes} min")
            }
            val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 59); textSize = 8.5f }
            val infoBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 249, 255) }
            val infoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(186, 230, 253); style = Paint.Style.STROKE; strokeWidth = 1f }
            target.drawRoundRect(RectF(margin, y, pageSize.width - margin, y + 38f), 7f, 7f, infoBox)
            target.drawRoundRect(RectF(margin, y, pageSize.width - margin, y + 38f), 7f, 7f, infoStroke)
            drawQuestionPaperWrapped(target, info.joinToString("   |   "), margin + 11f, y + 15f, contentWidth - 22f, infoPaint, 11f, 2)
            y += 54f
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 145, 178); strokeWidth = 2f }
            target.drawLine(margin, y, margin + 44f, y, rule)
            val instruction = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 9f; typeface = Typeface.DEFAULT_BOLD }
            target.drawText("Answer all questions.", margin + 54f, y + 3f, instruction)
            y += 24f
        } else {
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(71, 85, 105); textSize = 9f }
            target.drawText("For teacher use - do not distribute with the question paper.", margin, y, note)
            y += 24f
        }
    }

    fun finish() {
        page?.let(document::finishPage)
        page = null
    }

    try {
        startPage("QUESTION PAPER")
        allQuestions.forEachIndexed { index, question ->
            val estimated = questionPaperQuestionHeight(question, index + 1, contentWidth, questionText, questionBold)
            if (y + estimated > pageSize.height - margin - 28f) startPage("QUESTION PAPER")
            val target = canvas ?: error("PDF page is unavailable.")
            y = drawQuestionPaperQuestion(target, y, index + 1, question, contentWidth, margin, questionText, questionBold)
            y += setup.fontSize.points * .85f
        }
        if (setup.includeAnswerKey) {
            startPage("ANSWER KEY")
            allQuestions.forEachIndexed { index, question ->
                val answer = "${index + 1}. ${question.correctAnswer}"
                val explanation = question.explanation.trim()
                val estimated = questionPaperLineCount(answer, contentWidth, questionBold) * (setup.fontSize.points + 4f) +
                    if (explanation.isBlank()) 7f else questionPaperLineCount(explanation, contentWidth - 12f, questionText) * (setup.fontSize.points + 3f) + 14f
                if (y + estimated > pageSize.height - margin - 28f) startPage("ANSWER KEY")
                val target = canvas ?: error("PDF page is unavailable.")
                y = drawQuestionPaperWrapped(target, answer, margin, y, contentWidth, questionBold, setup.fontSize.points + 4f, 4)
                if (explanation.isNotBlank()) {
                    y = drawQuestionPaperWrapped(target, "Explanation: $explanation", margin + 12f, y + 2f, contentWidth - 12f, questionText, setup.fontSize.points + 3f, 5)
                }
                y += 8f
            }
        }
        finish()
        val directory = File(context.cacheDir, "question_papers").apply { mkdirs() }
        val safeName = setup.examName.ifBlank { "question_paper" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50).ifBlank { "question_paper" }
        val file = File(directory, "${safeName}_${System.currentTimeMillis()}.pdf")
        file.outputStream().use(document::writeTo)
        file
    } finally {
        document.close()
    }
}

internal fun downloadQuestionPaperPdf(context: Context, file: File, displayName: String): Uri {
    require(file.exists()) { "Question paper PDF is no longer available. Generate it again." }
    val name = displayName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60).ifBlank { "question_paper" } + ".pdf"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Question Papers")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the download file.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Could not save the PDF.")
            uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

internal fun printQuestionPaperPdf(context: Context, file: File, documentName: String): Boolean = try {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return false
    manager.print(
        documentName,
        QuestionPaperPrintAdapter(file, documentName),
        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
    )
    true
} catch (_: Exception) {
    false
}

private fun drawQuestionPaperHeader(
    canvas: Canvas,
    institute: InstituteEntity,
    logo: android.graphics.Bitmap?,
    title: String,
    sectionLabel: String,
    pageWidth: Float,
    margin: Float,
) {
    val navy = Color.rgb(8, 29, 51)
    val cyan = Color.rgb(6, 182, 212)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy }
    canvas.drawRect(0f, 0f, pageWidth, margin + 56f, fill)
    drawLogo(canvas, logo, institute.name, margin, margin - 14f, 44f, navy, cyan)
    val institutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    canvas.drawText(questionPaperEllipsize(institute.name, institutePaint, pageWidth * .48f), margin + 56f, margin + 10f, institutePaint)
    val detail = listOfNotNull(
        institute.instituteCode?.takeIf { it.isNotBlank() },
        institute.phone?.takeIf { it.isNotBlank() },
    ).joinToString("  |  ")
    val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 243, 252); textSize = 7.5f }
    canvas.drawText(questionPaperEllipsize(detail, detailPaint, pageWidth * .48f), margin + 56f, margin + 25f, detailPaint)
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    canvas.drawText(questionPaperEllipsize(title.ifBlank { "Question Paper" }, titlePaint, pageWidth * .36f), pageWidth - margin, margin + 8f, titlePaint)
    val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 243, 252); textSize = 8f; textAlign = Paint.Align.RIGHT }
    canvas.drawText(sectionLabel, pageWidth - margin, margin + 25f, sectionPaint)
}

private fun drawQuestionPaperWatermark(canvas: Canvas, instituteName: String, pageWidth: Float, pageHeight: Float) {
    val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(226, 232, 240)
        alpha = 92
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    canvas.save()
    canvas.rotate(-35f, pageWidth / 2f, pageHeight / 2f)
    canvas.drawText(questionPaperEllipsize(instituteName, watermark, pageWidth * .76f), pageWidth / 2f, pageHeight / 2f, watermark)
    canvas.restore()
}

private fun drawQuestionPaperFooter(
    canvas: Canvas,
    instituteName: String,
    generatedAt: String,
    pageNumber: Int,
    pageWidth: Float,
    pageHeight: Float,
    margin: Float,
) {
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240); strokeWidth = 1f }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 116, 139); textSize = 7.5f }
    canvas.drawLine(margin, pageHeight - margin + 5f, pageWidth - margin, pageHeight - margin + 5f, line)
    canvas.drawText(questionPaperEllipsize("$instituteName  |  Generated $generatedAt", text, pageWidth * .68f), margin, pageHeight - margin + 18f, text)
    text.textAlign = Paint.Align.RIGHT
    canvas.drawText("Page $pageNumber", pageWidth - margin, pageHeight - margin + 18f, text)
}

private fun drawQuestionPaperQuestion(
    canvas: Canvas,
    startY: Float,
    index: Int,
    question: ReviewableQuestion,
    contentWidth: Float,
    margin: Float,
    body: Paint,
    bold: Paint,
): Float {
    var y = drawQuestionPaperWrapped(canvas, "$index. ${question.questionText.trim()}", margin, startY, contentWidth, bold, body.textSize + 4f, 20)
    if (question.options.isNotEmpty()) {
        question.options.forEachIndexed { optionIndex, option ->
            val label = "${('A'.code + optionIndex).toChar()}. ${option.trim()}"
            y = drawQuestionPaperWrapped(canvas, label, margin + 16f, y + 1f, contentWidth - 16f, body, body.textSize + 3f, 6)
        }
    }
    val marks = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 145, 178); textSize = body.textSize * .78f; textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD }
    canvas.drawText("[${question.marks}]", margin + contentWidth, startY + body.textSize, marks)
    return y
}

private fun questionPaperQuestionHeight(question: ReviewableQuestion, index: Int, contentWidth: Float, body: Paint, bold: Paint): Float {
    var height = questionPaperLineCount("$index. ${question.questionText.trim()}", contentWidth, bold) * (body.textSize + 4f)
    question.options.forEach { option -> height += questionPaperLineCount(option.trim(), contentWidth - 16f, body) * (body.textSize + 3f) }
    return height + body.textSize * 1.8f
}

private fun questionPaperLineCount(value: String, width: Float, paint: Paint): Int {
    if (value.isBlank()) return 1
    var count = 0
    var line = ""
    value.split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isBlank()) word else "$line $word"
        if (paint.measureText(candidate) > width && line.isNotBlank()) {
            count += 1
            line = word
        } else line = candidate
    }
    return (count + if (line.isBlank()) 0 else 1).coerceAtLeast(1)
}

private fun drawQuestionPaperWrapped(
    canvas: Canvas,
    value: String,
    x: Float,
    startY: Float,
    maxWidth: Float,
    paint: Paint,
    lineHeight: Float,
    maxLines: Int,
): Float {
    var y = startY
    var line = ""
    var drawn = 0
    val words = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isEmpty()) return y + lineHeight
    for ((wordIndex, word) in words.withIndex()) {
        val candidate = if (line.isBlank()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
            canvas.drawText(line, x, y, paint)
            drawn += 1
            y += lineHeight
            if (drawn >= maxLines) return y
            line = word
        } else line = candidate
        if (wordIndex == words.lastIndex && line.isNotBlank()) {
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
    }
    return y
}

private fun questionPaperEllipsize(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end -= 1
    return value.take(end) + suffix
}

private class QuestionPaperPrintAdapter(
    private val file: File,
    private val documentName: String,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("$documentName.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build(),
            oldAttributes != newAttributes,
        )
    }

    override fun onWrite(
        pages: Array<android.print.PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
    ) {
        if (cancellationSignal?.isCanceled == true) return callback.onWriteCancelled()
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use(input::copyTo) }
            callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message)
        }
    }
}
