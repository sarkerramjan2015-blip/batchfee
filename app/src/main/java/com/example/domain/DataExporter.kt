package com.batchfee.edu.domain

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.batchfee.edu.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    EXCEL("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF", "pdf", "application/pdf"),
    WORD("Word (Docs)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
}

enum class ExportSection(val label: String) {
    STUDENTS("Students"),
    BATCHES("Batches"),
    FEES("Fees"),
    PAYMENTS("Payments"),
    EXPENSES("Expenses"),
    STAFF("Staff"),
}

data class ExportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)

object DataExporter {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
    private val cellDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    suspend fun exportData(
        context: Context,
        db: AppDatabase,
        sections: Set<ExportSection>,
        format: ExportFormat
    ): String {
        require(sections.isNotEmpty()) { "Select at least one section to export." }
        val file = withContext(Dispatchers.IO) {
            val instId = SessionManager.currentInstituteId.value
                ?: throw IllegalStateException("No active institute is selected for export.")
            val tables = buildTables(db, instId, sections)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val generated = File(dir, "BatchFee_Export_${dateFmt.format(java.util.Date())}.${format.extension}")
            when (format) {
                ExportFormat.EXCEL -> writeXlsx(generated, tables)
                ExportFormat.WORD -> writeDocx(generated, tables)
                ExportFormat.PDF -> writePdf(generated, tables)
            }
            generated
        }
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export BatchFee Data (${format.label})"))
        }
        return file.name
    }

    private suspend fun buildTables(db: AppDatabase, instId: String, sections: Set<ExportSection>): List<ExportTable> {
        val ordered = ExportSection.entries.filter { it in sections }
        val students = if (ExportSection.STUDENTS in sections) db.studentDao().getStudentsByInstituteOnce(instId) else emptyList()
        val batches = if (ExportSection.BATCHES in sections) db.batchDao().getBatchesByInstituteOnce(instId) else emptyList()
        val fees = if (ExportSection.FEES in sections) db.feeDao().getAllFeesOnce(instId) else emptyList()
        val payments = if (ExportSection.PAYMENTS in sections) db.paymentDao().getAllPaymentsOnce(instId) else emptyList()
        val expenses = if (ExportSection.EXPENSES in sections) db.expenseDao().getExpensesByInstituteAsList(instId) else emptyList()
        val staff = if (ExportSection.STAFF in sections) db.staffDao().getStaffByInstituteAsList(instId) else emptyList()
        val studentMap = students.associateBy { it.id }
        val batchMap = batches.associateBy { it.id }

        return ordered.mapNotNull { section ->
            when (section) {
                ExportSection.STUDENTS -> ExportTable(
                    "Students",
                    listOf("Code", "Name", "Phone", "Guardian", "School", "Class", "Gender", "Status"),
                    students.map { s ->
                        listOf(s.studentCode, s.fullName, s.phone.orEmpty(), s.guardianName.orEmpty(), s.schoolName.orEmpty(), s.className.orEmpty(), s.gender.orEmpty(), s.status)
                    }
                )
                ExportSection.BATCHES -> ExportTable(
                    "Batches",
                    listOf("Code", "Name", "Subject", "Class", "Teacher", "MonthlyFee", "Status"),
                    batches.map { b ->
                        listOf(b.batchCode, b.name, b.subject.orEmpty(), b.className.orEmpty(), b.teacherName.orEmpty(), b.monthlyFeeAmount.toString(), b.status)
                    }
                )
                ExportSection.FEES -> ExportTable(
                    "Fees",
                    listOf("Period", "Student", "Batch", "Type", "Total", "Paid", "Due", "Status", "DueDate"),
                    fees.map { f ->
                        listOf(
                            f.feePeriod,
                            studentMap[f.studentId]?.fullName ?: "N/A",
                            f.batchId?.let { batchMap[it]?.name } ?: "N/A",
                            f.feeType,
                            f.totalAmount.toString(),
                            f.paidAmount.toString(),
                            f.dueAmount.toString(),
                            f.status,
                            cellDateFmt.format(java.util.Date(f.dueDateMs)),
                        )
                    }
                )
                ExportSection.PAYMENTS -> ExportTable(
                    "Payments",
                    listOf("Receipt", "Student", "Amount", "Method", "Date", "Status"),
                    payments.map { p ->
                        listOf(
                            p.receiptNumber,
                            studentMap[p.studentId]?.fullName ?: "N/A",
                            p.amount.toString(),
                            p.paymentMethod,
                            cellDateFmt.format(java.util.Date(p.paymentDateMs)),
                            p.status,
                        )
                    }
                )
                ExportSection.EXPENSES -> ExportTable(
                    "Expenses",
                    listOf("Title", "Category", "Amount", "Date", "Payment"),
                    expenses.map { e ->
                        listOf(e.title, e.category, e.amount.toString(), cellDateFmt.format(java.util.Date(e.expenseDateMs)), e.paymentMethod.orEmpty())
                    }
                )
                ExportSection.STAFF -> ExportTable(
                    "Staff",
                    listOf("Code", "Name", "Role", "Phone", "Salary", "Status"),
                    staff.map { s ->
                        listOf(s.staffCode, s.fullName, s.roleTitle.orEmpty(), s.phone.orEmpty(), s.monthlySalary.toString(), s.status)
                    }
                )
            }
        }
    }

    // ── Excel (.xlsx) — native OpenXML in a zip, no external library ────────

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun columnName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun writeXlsx(file: File, tables: List<ExportTable>) {
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="BatchFee Export" sheetId="1" r:id="rId1"/></sheets>
</workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")

            val sheet = StringBuilder()
            var rowIndex = 1
            val usedColumns = tables.maxOfOrNull { it.headers.size } ?: 1
            fun row(cells: List<String>, bold: Boolean) {
                sheet.append("<row r=\"").append(rowIndex).append("\">")
                cells.forEachIndexed { index, cell ->
                    val ref = columnName(index) + rowIndex
                    val numeric = cell.toDoubleOrNull() != null && cell.isNotBlank()
                    if (numeric) {
                        sheet.append("<c r=\"").append(ref).append("\"><v>").append(xmlEscape(cell)).append("</v></c>")
                    } else {
                        sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(xmlEscape(cell)).append("</t></is></c>")
                    }
                }
                sheet.append("</row>")
                rowIndex++
            }
            tables.forEach { table ->
                row(listOf(table.title) + List(usedColumns - 1) { "" }, bold = true)
                row(table.headers + List(usedColumns - table.headers.size) { "" }, bold = true)
                table.rows.forEach { cells -> row(cells + List(usedColumns - cells.size) { "" }, bold = false) }
                rowIndex++ // one blank row between sections
            }
            val lastCell = columnName(usedColumns - 1) + (rowIndex - 1)
            entry("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<dimension ref="A1:$lastCell"/>
<sheetData>${sheet}</sheetData>
</worksheet>""")
        }
    }

    // ── Word (.docx) — native OpenXML in a zip, no external library ─────────

    private fun writeDocx(file: File, tables: List<ExportTable>) {
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""")
            val body = StringBuilder()
            body.append("<w:body>")
            fun paragraph(text: String, bold: Boolean) {
                val run = if (bold) "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
                else "<w:r><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
                body.append("<w:p>").append(run).append("</w:p>")
            }
            tables.forEach { table ->
                paragraph(table.title, bold = true)
                paragraph(table.headers.joinToString("  |  "), bold = true)
                table.rows.forEach { cells -> paragraph(cells.joinToString("  |  "), bold = false) }
                paragraph("", bold = false)
            }
            body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>")
            body.append("</w:body>")
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
${body}
</w:document>""")
        }
    }

    // ── PDF — Android PdfDocument with a simple table layout ───────────────

    private fun writePdf(file: File, tables: List<ExportTable>) {
        val document = PdfDocument()
        try {
            var page = document.startPage(pageInfo())
            var pageCanvas = page.canvas
            var y = 48f
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9f
            }

            fun newPageIfNeeded(height: Float) {
                if (y + height > 730f) {
                    document.finishPage(page)
                    page = document.startPage(pageInfo())
                    pageCanvas = page.canvas
                    y = 48f
                }
            }

            tables.forEach { table ->
                val columns = table.headers.size.coerceAtLeast(1)
                val colWidth = 540f / columns
                newPageIfNeeded(34f)
                pageCanvas.drawText(table.title, 30f, y, titlePaint)
                y += 18f
                fun drawRow(cells: List<String>, paint: Paint) {
                    cells.take(columns).forEachIndexed { index, cell ->
                        val display = if (cell.length > 28) cell.take(27) + "…" else cell
                        pageCanvas.drawText(display, 30f + index * colWidth, y, paint)
                    }
                    y += 16f
                }
                newPageIfNeeded(16f)
                drawRow(table.headers, headerPaint)
                table.rows.forEach { cells ->
                    newPageIfNeeded(16f)
                    drawRow(cells, cellPaint)
                }
                y += 10f
            }
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun pageInfo(): PdfDocument.PageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
}
