package com.batchfee.edu.domain

import android.app.Activity
import android.content.ClipData
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
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val description: String,
) {
    EXCEL("Excel workbook", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Best for filtering and accounts"),
    PDF("PDF report", "pdf", "application/pdf", "Best for viewing and printing"),
    WORD("Word document", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Best for editable sharing"),
}

enum class ExportSection(val label: String, val description: String) {
    STUDENTS("Students", "Student profiles and contacts"),
    BATCHES("Batches", "Classes, schedules and fees"),
    FEES("Fees", "Charges, paid amounts and dues"),
    PAYMENTS("Payments", "Receipts and collections"),
    EXPENSES("Expenses", "Recorded institute expenses"),
    STAFF("Staff", "Staff profiles and salary rates"),
    ATTENDANCE("Attendance", "Student attendance history"),
    SALARIES("Salaries", "Salary records and payments"),
    EXAMS("Exams", "Exam schedules and settings"),
}

data class ExportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)

data class ExportOutcome(
    val fileName: String,
    val sectionCount: Int,
    val recordCount: Int,
)

/** Read-only current-institute export. It never changes cloud or local data. */
object DataExporter {
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val displayDateTimeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    suspend fun exportData(
        context: Context,
        db: AppDatabase,
        sections: Set<ExportSection>,
        format: ExportFormat,
    ): ExportOutcome {
        require(sections.isNotEmpty()) { "Select at least one section to export." }
        val (file, tables) = withContext(Dispatchers.IO) {
            val instituteId = SessionManager.currentInstituteId.value
                ?: throw IllegalStateException("No active institute is selected for export.")
            val tables = buildTables(db, instituteId, sections)
            val directory = File(context.cacheDir, "exports").apply {
                if (!exists() && !mkdirs()) error("Could not prepare secure export storage.")
            }
            val name = "BatchFee_Export_${fileDateFormat.format(Date())}.${format.extension}"
            val finalFile = File(directory, name)
            val temporaryFile = File(directory, "$name.part")
            temporaryFile.delete()
            try {
                when (format) {
                    ExportFormat.EXCEL -> writeXlsx(temporaryFile, tables)
                    ExportFormat.WORD -> writeDocx(temporaryFile, tables)
                    ExportFormat.PDF -> writePdf(temporaryFile, tables)
                }
                check(temporaryFile.isFile && temporaryFile.length() > 0L) { "The export file could not be created." }
                if (finalFile.exists() && !finalFile.delete()) error("Could not replace the previous export file.")
                check(temporaryFile.renameTo(finalFile)) { "Could not finalize the export file." }
                finalFile to tables
            } catch (error: Exception) {
                temporaryFile.delete()
                throw error
            }
        }
        shareExport(context, file, format)
        return ExportOutcome(file.name, tables.size, tables.sumOf { it.rows.size })
    }

    private fun shareExport(context: Context, file: File, format: ExportFormat) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri("BatchFee export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(share, "Share BatchFee export"))
    }

    private suspend fun buildTables(
        db: AppDatabase,
        instituteId: String,
        sections: Set<ExportSection>,
    ): List<ExportTable> {
        val ordered = ExportSection.entries.filter { it in sections }
        val needsStudents = sections.any { it in setOf(ExportSection.STUDENTS, ExportSection.FEES, ExportSection.PAYMENTS, ExportSection.ATTENDANCE) }
        val needsBatches = sections.any { it in setOf(ExportSection.BATCHES, ExportSection.FEES, ExportSection.ATTENDANCE, ExportSection.EXAMS) }
        val students = if (needsStudents) db.studentDao().getStudentsByInstituteOnce(instituteId) else emptyList()
        val batches = if (needsBatches) db.batchDao().getBatchesByInstituteOnce(instituteId) else emptyList()
        val fees = if (ExportSection.FEES in sections) db.feeDao().getAllFeesOnce(instituteId) else emptyList()
        val payments = if (ExportSection.PAYMENTS in sections) db.paymentDao().getAllPaymentsOnce(instituteId) else emptyList()
        val expenses = if (ExportSection.EXPENSES in sections) db.expenseDao().getExpensesByInstituteAsList(instituteId) else emptyList()
        val staff = if (sections.any { it == ExportSection.STAFF || it == ExportSection.SALARIES }) db.staffDao().getStaffByInstituteAsList(instituteId) else emptyList()
        val attendance = if (ExportSection.ATTENDANCE in sections) db.attendanceDao().getAttendanceByInstituteOnce(instituteId) else emptyList()
        val salaries = if (ExportSection.SALARIES in sections) db.salaryDao().getSalariesByInstituteOnce(instituteId) else emptyList()
        val exams = if (ExportSection.EXAMS in sections) db.examDao().getExamsByInstituteOnce(instituteId) else emptyList()
        val studentMap = students.associateBy { it.id }
        val batchMap = batches.associateBy { it.id }
        val staffMap = staff.associateBy { it.id }

        return ordered.map { section ->
            when (section) {
                ExportSection.STUDENTS -> ExportTable("Students", listOf("Student code", "Name", "Phone", "Guardian", "Guardian phone", "School", "Class", "Gender", "Admission date", "Status"), students.map { s -> listOf(s.studentCode, s.fullName, s.phone.orEmpty(), s.guardianName.orEmpty(), s.guardianPhone.orEmpty(), s.schoolName.orEmpty(), s.className.orEmpty(), s.gender.orEmpty(), formatDate(s.admissionDateMs), s.status) })
                ExportSection.BATCHES -> ExportTable("Batches", listOf("Batch code", "Name", "Subject", "Class", "Teacher", "Billing type", "Monthly fee", "Admission fee", "Course fee", "Schedule", "Status"), batches.map { b -> listOf(b.batchCode, b.name, b.subject.orEmpty(), b.className.orEmpty(), b.teacherName.orEmpty(), b.billingMode, money(b.monthlyFeeAmount), money(b.admissionFeeAmount), money(b.courseFeeAmount), listOfNotNull(b.scheduleDays?.takeIf(String::isNotBlank), b.startTime?.takeIf(String::isNotBlank), b.endTime?.takeIf(String::isNotBlank)).joinToString(" · "), b.status) })
                ExportSection.FEES -> ExportTable("Fees", listOf("Period", "Student", "Batch", "Type", "Base", "Discount", "Late fee", "Total", "Paid", "Due", "Status", "Due date"), fees.map { f -> listOf(f.feePeriod, studentMap[f.studentId]?.fullName ?: "Unknown student", f.batchId?.let { batchMap[it]?.name }.orEmpty(), f.feeType, money(f.baseAmount), money(f.discountAmount), money(f.lateFeeAmount), money(f.totalAmount), money(f.paidAmount), money(f.dueAmount), f.status, formatDate(f.dueDateMs)) })
                ExportSection.PAYMENTS -> ExportTable("Payments", listOf("Receipt", "Student", "Amount", "Method", "Transaction ID", "Date", "Status", "Note"), payments.map { p -> listOf(p.receiptNumber, studentMap[p.studentId]?.fullName ?: "Unknown student", money(p.amount), p.paymentMethod, p.transactionId.orEmpty(), formatDateTime(p.paymentDateMs), p.status, p.note.orEmpty()) })
                ExportSection.EXPENSES -> ExportTable("Expenses", listOf("Title", "Category", "Amount", "Date", "Payment method"), expenses.map { e -> listOf(e.title, e.category, money(e.amount), formatDate(e.expenseDateMs), e.paymentMethod.orEmpty()) })
                ExportSection.STAFF -> ExportTable("Staff", listOf("Staff code", "Name", "Role", "Phone", "Monthly salary", "Status"), staff.map { s -> listOf(s.staffCode, s.fullName, s.roleTitle.orEmpty(), s.phone.orEmpty(), money(s.monthlySalary), s.status) })
                ExportSection.ATTENDANCE -> ExportTable("Attendance", listOf("Date", "Student", "Batch", "Status", "Late by (minutes)", "Arrival time", "Note"), attendance.map { a -> listOf(formatDate(a.attendanceDateMs), studentMap[a.studentId]?.fullName ?: "Unknown student", batchMap[a.batchId]?.name ?: "Unknown batch", a.status, a.lateByMinutes?.toString().orEmpty(), a.arrivalTimeMs?.let(::formatDateTime).orEmpty(), a.note.orEmpty()) })
                ExportSection.SALARIES -> ExportTable("Salaries", listOf("Slip", "Staff", "Month", "Basic", "Bonus", "Deduction", "Advance", "Net", "Paid", "Status", "Paid date"), salaries.map { s -> listOf(s.salarySlipNumber, staffMap[s.staffId]?.fullName ?: "Unknown staff", s.salaryMonth, money(s.basicSalary), money(s.bonusAmount), money(s.deductionAmount), money(s.advanceAmount), money(s.netSalary), money(s.paidAmount), s.status, s.paymentDateMs?.let(::formatDate).orEmpty()) })
                ExportSection.EXAMS -> ExportTable("Exams", listOf("Exam", "Batch", "Subject", "Date", "Total marks", "Pass marks", "Exam fee", "Teacher", "Status"), exams.map { e -> listOf(e.examName, batchMap[e.batchId]?.name ?: "Unknown batch", e.subject.orEmpty(), formatDate(e.examDateMs), money(e.totalMarks), money(e.passingMarks), money(e.examFeeAmount), e.teacherName.orEmpty(), e.status) })
            }
        }
    }

    private fun formatDate(value: Long): String = displayDateFormat.format(Date(value))
    private fun formatDateTime(value: Long): String = displayDateTimeFormat.format(Date(value))
    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun xmlEscape(value: String): String = value.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun columnName(index: Int): String {
        var value = index
        val name = StringBuilder()
        while (value >= 0) { name.insert(0, ('A' + (value % 26))); value = value / 26 - 1 }
        return name.toString()
    }

    private fun xlsxSheetName(title: String, index: Int): String = title.replace(Regex("[\\\\/*?:\\[\\]]"), " ").trim().take(28).ifBlank { "Export" } + "_${index + 1}"

    // A real multi-sheet workbook. Every value stays textual so phone numbers,
    // IDs and receipt numbers never lose leading zeros or become scientific notation.
    private fun writeXlsx(file: File, tables: List<ExportTable>) {
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun entry(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            val sheetNames = tables.mapIndexed { index, table -> xlsxSheetName(table.title, index) }
            val overrides = tables.indices.joinToString("") { index -> "<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>$overrides</Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val sheetsXml = sheetNames.mapIndexed { index, name -> "<sheet name=\"${xmlEscape(name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>" }.joinToString("")
            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$sheetsXml</sheets></workbook>""")
            val sheetRels = tables.indices.joinToString("") { index -> "<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>" }
            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$sheetRels<Relationship Id="rId${tables.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="3"><font><sz val="11"/><name val="Aptos"/></font><font><b/><sz val="14"/><color rgb="FF0F172A"/><name val="Aptos Display"/></font><font><b/><color rgb="FFFFFFFF"/><name val="Aptos"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE0F2FE"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0E7490"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="1" borderId="0" xfId="0"/><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center"/></xf></cellXfs></styleSheet>""")

            tables.forEachIndexed { sheetIndex, table ->
                val width = table.headers.size.coerceAtLeast(1)
                val rows = StringBuilder()
                fun addRow(index: Int, cells: List<String>, style: Int) {
                    rows.append("<row r=\"").append(index).append("\">")
                    cells.take(width).forEachIndexed { column, value -> rows.append("<c r=\"").append(columnName(column)).append(index).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xmlEscape(value)).append("</t></is></c>") }
                    rows.append("</row>")
                }
                addRow(1, listOf(table.title), 1); addRow(2, table.headers, 2)
                if (table.rows.isEmpty()) addRow(3, listOf("No records found"), 0) else table.rows.forEachIndexed { index, cells -> addRow(index + 3, cells, 0) }
                val lastRow = if (table.rows.isEmpty()) 3 else table.rows.size + 2
                val columns = (0 until width).joinToString("") { index -> "<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${if (index == 0) 22 else 18}\" customWidth=\"1\"/>" }
                val lastColumn = columnName(width - 1)
                entry("xl/worksheets/sheet${sheetIndex + 1}.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:${lastColumn}${lastRow}"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="2" topLeftCell="A3" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>$columns</cols><sheetData>$rows</sheetData><autoFilter ref="A2:${lastColumn}${lastRow}"/></worksheet>""")
            }
        }
    }

    private fun writeDocx(file: File, tables: List<ExportTable>) {
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun entry(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            fun cell(value: String, bold: Boolean) = "<w:tc><w:tcPr><w:tcW w:w=\"1800\" w:type=\"dxa\"/></w:tcPr><w:p><w:r>${if (bold) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space=\"preserve\">${xmlEscape(value)}</w:t></w:r></w:p></w:tc>"
            fun row(values: List<String>, bold: Boolean) = "<w:tr>${values.joinToString("") { cell(it, bold) }}</w:tr>"
            val body = buildString {
                append("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr><w:t>BatchFee Institute Data Export</w:t></w:r></w:p><w:p><w:r><w:t>Generated ${xmlEscape(displayDateTimeFormat.format(Date()))}</w:t></w:r></w:p>")
                tables.forEach { table ->
                    append("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr><w:t>${xmlEscape(table.title)}</w:t></w:r></w:p><w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH w:val=\"single\" w:sz=\"2\"/><w:insideV w:val=\"single\" w:sz=\"2\"/></w:tblBorders></w:tblPr>")
                    append(row(table.headers, true)); if (table.rows.isEmpty()) append(row(listOf("No records found"), false)) else table.rows.forEach { append(row(it, false)) }; append("</w:tbl><w:p/>")
                }
                append("<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr>")
            }
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>""")
        }
    }

    private fun writePdf(file: File, tables: List<ExportTable>) {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(842, 595, 1).create()
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 7.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 59); textSize = 7.5f }
            val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 116, 139); textSize = 8f }
            var pageNumber = 0; var page: PdfDocument.Page? = null; var canvas: android.graphics.Canvas? = null; var y = 0f
            fun startPage() { pageNumber += 1; page = document.startPage(pageInfo); canvas = page!!.canvas; canvas!!.drawColor(Color.WHITE); canvas!!.drawRect(0f, 0f, 842f, 50f, Paint().apply { color = Color.rgb(8, 47, 73) }); canvas!!.drawText("BatchFee Institute Data Export", 28f, 31f, titlePaint); canvas!!.drawText("Generated ${displayDateTimeFormat.format(Date())}", 28f, 44f, Paint(mutedPaint).apply { color = Color.rgb(207, 250, 254); textSize = 8f }); y = 76f }
            fun finishPage() { val active = canvas ?: return; active.drawText("BatchFee · confidential institute record · Page $pageNumber", 421f, 574f, Paint(mutedPaint).apply { textAlign = Paint.Align.CENTER }); document.finishPage(requireNotNull(page)); page = null; canvas = null }
            fun ensure(height: Float) { if (canvas == null) startPage(); if (y + height > 552f) { finishPage(); startPage() } }
            fun truncate(text: String, max: Int) = if (text.length <= max) text else text.take((max - 1).coerceAtLeast(1)) + "…"
            tables.forEach { table ->
                val columns = table.headers.size.coerceAtLeast(1); val columnWidth = 786f / columns
                fun header() { ensure(20f); val active = requireNotNull(canvas); active.drawRect(28f, y, 814f, y + 18f, Paint().apply { color = Color.rgb(14, 116, 144) }); table.headers.forEachIndexed { index, value -> active.drawText(truncate(value, 20), 32f + index * columnWidth, y + 12f, headerPaint) }; y += 18f }
                ensure(28f); requireNotNull(canvas).drawText(table.title, 28f, y, sectionPaint); y += 9f; header()
                val rows = if (table.rows.isEmpty()) listOf(listOf("No records found")) else table.rows
                rows.forEachIndexed { index, cells ->
                    val pageBefore = pageNumber
                    ensure(17f)
                    if (pageNumber != pageBefore) header()
                    val active = requireNotNull(canvas)
                    if (index % 2 == 0) active.drawRect(28f, y, 814f, y + 16f, Paint().apply { color = Color.rgb(241, 245, 249) })
                    cells.take(columns).forEachIndexed { column, value -> active.drawText(truncate(value, 24), 32f + column * columnWidth, y + 11f, cellPaint) }
                    y += 16f
                }; y += 14f
            }
            if (canvas == null) startPage(); finishPage(); FileOutputStream(file).use(document::writeTo)
        } finally { document.close() }
    }
}
