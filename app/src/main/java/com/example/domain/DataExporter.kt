package com.batchfee.edu.domain

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.batchfee.edu.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DataExporter {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
    private val csvDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    suspend fun exportAllToCsv(context: Context, db: AppDatabase): String {
        val file = withContext(Dispatchers.IO) {
            val instId = SessionManager.currentInstituteId.value
                ?: throw IllegalStateException("No active institute is selected for export.")
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val timestamp = dateFmt.format(Date())
            val file = File(dir, "BatchFee_Export_$timestamp.csv")
            file.bufferedWriter().use { writer ->

                writer.write("=== STUDENTS ===\n")
                writer.write("Code,Name,Phone,Guardian,School,Class,Gender,Status\n")
                val students = db.studentDao().getStudentsByInstituteOnce(instId)
                students.forEach { s ->
                    writer.write("${s.studentCode},${escapeCsv(s.fullName)},${s.phone.orEmpty()}," +
                            "${escapeCsv(s.guardianName)},${escapeCsv(s.schoolName)},${escapeCsv(s.className)}," +
                            "${s.gender.orEmpty()},${s.status}\n")
                }

                writer.write("\n=== BATCHES ===\n")
                writer.write("Code,Name,Subject,Class,Teacher,MonthlyFee,Status\n")
                val batches = db.batchDao().getBatchesByInstituteOnce(instId)
                batches.forEach { b ->
                    writer.write("${b.batchCode},${escapeCsv(b.name)},${escapeCsv(b.subject)}," +
                            "${escapeCsv(b.className)},${escapeCsv(b.teacherName)},${b.monthlyFeeAmount},${b.status}\n")
                }

            writer.write("\n=== FEES ===\n")
            writer.write("Period,Student,Batch,Type,Total,Paid,Due,Status,DueDate\n")
            val fees = db.feeDao().getAllFeesOnce(instId)
            val studentMap = students.associateBy { it.id }
            val batchMap = batches.associateBy { it.id }
            fees.forEach { f ->
                val sName = studentMap[f.studentId]?.fullName ?: "N/A"
                val bName = f.batchId?.let { batchMap[it]?.name } ?: "N/A"
                writer.write("${f.feePeriod},${escapeCsv(sName)},${escapeCsv(bName)},${f.feeType}," +
                        "${f.totalAmount},${f.paidAmount},${f.dueAmount},${f.status},${csvDateFmt.format(Date(f.dueDateMs))}\n")
            }

            writer.write("\n=== PAYMENTS ===\n")
            writer.write("Receipt,Student,Amount,Method,Date,Status\n")
            val payments = db.paymentDao().getAllPaymentsOnce(instId)
            payments.forEach { p ->
                val sName = studentMap[p.studentId]?.fullName ?: "N/A"
                writer.write("${p.receiptNumber},${escapeCsv(sName)},${p.amount},${p.paymentMethod}," +
                        "${csvDateFmt.format(Date(p.paymentDateMs))},${p.status}\n")
            }

            writer.write("\n=== EXPENSES ===\n")
            writer.write("Title,Category,Amount,Date,Payment\n")
            val expenses = db.expenseDao().getExpensesByInstituteAsList(instId)
            expenses.forEach { e ->
                writer.write("${escapeCsv(e.title)},${e.category},${e.amount}," +
                        "${csvDateFmt.format(Date(e.expenseDateMs))},${e.paymentMethod.orEmpty()}\n")
            }

            writer.write("\n=== STAFF ===\n")
            writer.write("Code,Name,Role,Phone,Salary,Status\n")
            val staff = db.staffDao().getStaffByInstituteAsList(instId)
            staff.forEach { s ->
                writer.write("${s.staffCode},${escapeCsv(s.fullName)},${escapeCsv(s.roleTitle)}," +
                        "${s.phone},${s.monthlySalary},${s.status}\n")
            }

            }
            file
        }
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export BatchFee Data"))
        }
        return file.name
    }

    private fun escapeCsv(value: String?): String {
        val v = value ?: ""
        return if (v.contains(",") || v.contains("\"") || v.contains("\n")) "\"${v.replace("\"", "\"\"")}\"" else v
    }
}

