package com.batchfee.edu.ui.studentapp

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.StudentSessionManager
import com.batchfee.edu.ui.students.generateProfessionalIdCardPdf
import com.batchfee.edu.ui.students.generateStudentAdmissionFormPdf
import com.batchfee.edu.ui.students.openStudentPdf
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DsBg = Color(0xFF07111F)
private val DsCard = Color(0xFF0F172A)
private val DsStroke = Color(0xFF1E293B)
private val DsCyan = Color(0xFF22D3EE)
private val DsBlue = Color(0xFF3B82F6)
private val DsGreen = Color(0xFF22C55E)
private val DsAmber = Color(0xFFF59E0B)
private val DsRed = Color(0xFFEF4444)
private val DsWhite = Color(0xFFF8FAFC)
private val DsMuted = Color(0xFF94A3B8)

/** Immutable, student-readable receipt data. A student never writes these records. */
internal data class StudentReceiptDocument(
    val id: String,
    val feeId: String,
    val receiptNumber: String,
    val dateMs: Long,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double,
    val paymentMethod: String,
    val feeLabel: String
)

internal data class StudentResultDocument(
    val id: String,
    val title: String,
    val subject: String?,
    val dateMs: Long?,
    val obtainedMarks: Double,
    val totalMarks: Double,
    val grade: String?,
    val position: Int?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDocumentsScreen(onBack: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    var student by remember(studentId) { mutableStateOf<StudentEntity?>(null) }
    var institute by remember(instituteId) { mutableStateOf<InstituteEntity?>(null) }
    var receipts by remember(studentId, instituteId) { mutableStateOf(emptyList<StudentReceiptDocument>()) }
    var feesById by remember(studentId, instituteId) { mutableStateOf(emptyMap<String, StudentFeeDocument>()) }
    var rawResults by remember(studentId, instituteId) { mutableStateOf(emptyList<RawStudentResult>()) }
    var exams by remember(instituteId) { mutableStateOf(emptyMap<String, StudentExamDocument>()) }
    var loading by remember(studentId, instituteId) { mutableStateOf(true) }
    var message by remember(studentId, instituteId) { mutableStateOf<String?>(null) }
    var workingLabel by remember { mutableStateOf<String?>(null) }

    fun listenerError(error: FirebaseFirestoreException?) {
        if (error == null) return
        loading = false
        message = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            "Your document access has changed. Please sign in again."
        } else {
            "Documents will refresh when the connection returns."
        }
    }

    DisposableEffect(instituteId, studentId) {
        if (instituteId.isBlank() || studentId.isBlank()) {
            onDispose { }
        } else {
            val firestore = FirebaseFirestore.getInstance()
            val listeners = mutableListOf<ListenerRegistration>()
            val instituteRef = firestore.collection("institutes").document(instituteId)

            listeners += instituteRef.collection("students").document(studentId)
                .addSnapshotListener { snapshot, error ->
                    listenerError(error)
                    if (error == null) {
                        student = snapshot?.takeIf { it.exists() }?.toStudentDocument(instituteId)
                        loading = false
                    }
                }
            listeners += instituteRef.addSnapshotListener { snapshot, error ->
                listenerError(error)
                if (error == null && snapshot != null && snapshot.exists()) {
                    institute = snapshot.toInstituteDocument()
                }
            }
            // Receipt records are written by the trusted payment ledger. Reading this
            // collection gives the student the same final amount/reference the owner sees.
            listeners += instituteRef.collection("fees").whereEqualTo("studentId", studentId)
                .addSnapshotListener { snapshot, error ->
                    listenerError(error)
                    if (error == null) {
                        feesById = snapshot?.documents.orEmpty().associate { doc ->
                            doc.id to StudentFeeDocument(
                                type = doc.getString("feeType"),
                                period = doc.getString("feePeriod") ?: doc.getString("monthYear")
                            )
                        }
                    }
                }
            listeners += instituteRef.collection("receipts").whereEqualTo("studentId", studentId)
                .addSnapshotListener { snapshot, error ->
                    listenerError(error)
                    if (error == null) {
                        receipts = snapshot?.documents.orEmpty().map { doc ->
                            StudentReceiptDocument(
                                id = doc.id,
                                feeId = doc.getString("feeId") ?: "",
                                receiptNumber = doc.getString("receiptNumber") ?: doc.id,
                                dateMs = (doc.get("receiptDateMs") as? Number)?.toLong() ?: 0L,
                                totalAmount = (doc.get("totalAmount") as? Number)?.toDouble() ?: 0.0,
                                paidAmount = (doc.get("paidAmount") as? Number)?.toDouble() ?: 0.0,
                                dueAmount = (doc.get("dueAmount") as? Number)?.toDouble() ?: 0.0,
                                paymentMethod = doc.getString("paymentMethod") ?: "Cash",
                                feeLabel = doc.getString("feeLabel") ?: doc.getString("feePeriod") ?: "Fee payment"
                            )
                        }.sortedByDescending { it.dateMs }
                    }
                }
            listeners += instituteRef.collection("results").whereEqualTo("studentId", studentId)
                .addSnapshotListener { snapshot, error ->
                    listenerError(error)
                    if (error == null) {
                        rawResults = snapshot?.documents.orEmpty()
                            .filter { it.getBoolean("published") == true }
                            .map { doc -> RawStudentResult.from(doc) }
                    }
                }
            listeners += instituteRef.collection("exams").addSnapshotListener { snapshot, error ->
                listenerError(error)
                if (error == null) {
                    exams = snapshot?.documents.orEmpty().associate { doc ->
                        doc.id to StudentExamDocument(
                            title = doc.getString("examName") ?: "Published result",
                            subject = doc.getString("subject"),
                            dateMs = (doc.get("examDateMs") as? Number)?.toLong(),
                            totalMarks = (doc.get("totalMarks") as? Number)?.toDouble() ?: 0.0
                        )
                    }
                }
            }
            onDispose { listeners.forEach { it.remove() } }
        }
    }

    val results = rawResults.map { raw ->
        val exam = raw.examId?.let(exams::get)
        StudentResultDocument(
            id = raw.id,
            title = exam?.title ?: raw.title.ifBlank { "Published result" },
            subject = exam?.subject ?: raw.subject,
            dateMs = exam?.dateMs ?: raw.dateMs,
            obtainedMarks = raw.obtainedMarks,
            totalMarks = exam?.totalMarks?.takeIf { it > 0.0 } ?: raw.totalMarks,
            grade = raw.grade,
            position = raw.position
        )
    }.sortedByDescending { it.dateMs ?: 0L }
    val receiptCards = receipts.map { receipt ->
        receipt.copy(feeLabel = feesById[receipt.feeId]?.label ?: receipt.feeLabel)
    }

    fun openDocument(label: String, producer: suspend () -> File) {
        scope.launch {
            workingLabel = label
            runCatching { producer() }
                .onSuccess { file ->
                    if (!openStudentPdf(context, file, label)) {
                        message = "No PDF viewer is installed on this device."
                    }
                }
                .onFailure { message = "Could not prepare $label. Please try again." }
            workingLabel = null
        }
    }

    fun shareDocument(label: String, producer: suspend () -> File) {
        scope.launch {
            workingLabel = label
            runCatching { producer() }
                .onSuccess { file -> shareStudentDocument(context, file, label) }
                .onFailure { message = "Could not prepare $label. Please try again." }
            workingLabel = null
        }
    }

    Scaffold(
        containerColor = DsBg,
        topBar = {
            TopAppBar(
                title = { Text("My Documents", color = DsWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DsMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DsBg)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(DsBg)
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("Your verified documents", color = DsWhite, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.height(4.dp))
            Text("Always updated from ${institute?.name ?: "your institute"}", color = DsMuted, fontSize = 13.sp)
            message?.let {
                Spacer(Modifier.height(14.dp))
                Surface(color = DsAmber.copy(alpha = .12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = DsAmber, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(18.dp))

            if (loading || student == null || institute == null) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = DsCyan)
                }
            } else {
                val currentStudent = student!!
                val currentInstitute = institute!!
                DocumentTile(
                    title = "Admission Form",
                    description = "Your official admission information",
                    icon = Icons.Filled.Description,
                    tint = DsCyan,
                    working = workingLabel == "Admission form",
                    onOpen = { openDocument("Admission form") { generateStudentAdmissionFormPdf(context, currentInstitute, currentStudent) } },
                    onShare = { shareDocument("Admission form") { generateStudentAdmissionFormPdf(context, currentInstitute, currentStudent) } }
                )
                Spacer(Modifier.height(12.dp))
                DocumentTile(
                    title = "Student ID Card",
                    description = "Your current identity card",
                    icon = Icons.Filled.Badge,
                    tint = DsBlue,
                    working = workingLabel == "Student ID card",
                    onOpen = { openDocument("Student ID card") { generateProfessionalIdCardPdf(context, currentStudent, currentInstitute, dateFormat.format(Date()), "Active") } },
                    onShare = { shareDocument("Student ID card") { generateProfessionalIdCardPdf(context, currentStudent, currentInstitute, dateFormat.format(Date()), "Active") } }
                )

                Spacer(Modifier.height(24.dp))
                Text("Payment Receipts", color = DsWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (receiptCards.isEmpty()) {
                    EmptyDocumentState("Your payment receipts will appear here.", Icons.Filled.ReceiptLong)
                } else receiptCards.forEach { receipt ->
                    DocumentTile(
                        title = "BDT ${formatStudentMoney(receipt.paidAmount)}",
                        description = "${receipt.feeLabel} • ${dateFormat.format(Date(receipt.dateMs))}",
                        icon = Icons.Filled.ReceiptLong,
                        tint = DsGreen,
                        working = workingLabel == "Payment receipt",
                        onOpen = { openDocument("Payment receipt") { generateStudentReceiptPdf(context, currentInstitute, currentStudent, receipt) } },
                        onShare = { shareDocument("Payment receipt") { generateStudentReceiptPdf(context, currentInstitute, currentStudent, receipt) } }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(14.dp))
                Text("Published Result Cards", color = DsWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (results.isEmpty()) {
                    EmptyDocumentState("Result cards appear after your institute publishes a result.", Icons.Filled.EmojiEvents)
                } else results.forEach { result ->
                    DocumentTile(
                        title = result.title,
                        description = listOfNotNull(result.grade?.let { "Grade $it" }, result.dateMs?.let { dateFormat.format(Date(it)) }).joinToString(" • "),
                        icon = Icons.Filled.EmojiEvents,
                        tint = DsAmber,
                        working = workingLabel == "Result card",
                        onOpen = { openDocument("Result card") { generateStudentResultCardPdf(context, currentInstitute, currentStudent, result) } },
                        onShare = { shareDocument("Result card") { generateStudentResultCardPdf(context, currentInstitute, currentStudent, result) } }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DocumentTile(
    title: String,
    description: String,
    icon: ImageVector,
    tint: Color,
    working: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DsCard),
        border = BorderStroke(1.dp, DsStroke)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = tint.copy(alpha = .14f), shape = RoundedCornerShape(13.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(11.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = DsWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (description.isNotBlank()) Text(description, color = DsMuted, fontSize = 11.sp, maxLines = 2)
            }
            if (working) CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            else {
                IconButton(onClick = onOpen) { Icon(Icons.Filled.PictureAsPdf, "Open PDF", tint = tint) }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share PDF", tint = DsMuted) }
            }
        }
    }
}

@Composable
private fun EmptyDocumentState(text: String, icon: ImageVector) {
    Surface(color = DsCard, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, DsStroke), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = DsMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = DsMuted, fontSize = 12.sp)
        }
    }
}

private data class RawStudentResult(
    val id: String,
    val examId: String?,
    val title: String,
    val subject: String?,
    val dateMs: Long?,
    val obtainedMarks: Double,
    val totalMarks: Double,
    val grade: String?,
    val position: Int?
) {
    companion object {
        fun from(document: DocumentSnapshot) = RawStudentResult(
            id = document.id,
            examId = document.getString("examId"),
            title = document.getString("examName") ?: "",
            subject = document.getString("subject"),
            dateMs = (document.get("examDateMs") as? Number)?.toLong(),
            obtainedMarks = (document.get("marksObtained") as? Number)?.toDouble()
                ?: (document.get("obtainedMarks") as? Number)?.toDouble() ?: 0.0,
            totalMarks = (document.get("totalMarks") as? Number)?.toDouble() ?: 0.0,
            grade = document.getString("grade"),
            position = (document.get("position") as? Number)?.toInt()
        )
    }
}

private data class StudentExamDocument(val title: String, val subject: String?, val dateMs: Long?, val totalMarks: Double)

private data class StudentFeeDocument(val type: String?, val period: String?) {
    val label: String
        get() {
            val typeLabel = when (type?.lowercase(Locale.US)) {
                "monthly_fee", "monthly" -> "Monthly fee"
                "admission_fee", "admission" -> "Admission fee"
                "advance_fee", "advance" -> "Advance fee"
                "exam_fee", "exam" -> "Exam fee"
                else -> type?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Fee payment"
            }
            return period?.takeIf { it.isNotBlank() }?.let { "$typeLabel - $it" } ?: typeLabel
        }
    }

private fun DocumentSnapshot.toStudentDocument(instituteId: String) = StudentEntity(
    id = id,
    instituteId = instituteId,
    studentCode = getString("studentCode") ?: id,
    fullName = getString("fullName") ?: "Student",
    photoUri = getString("photoUri"),
    gender = getString("gender"),
    dateOfBirthMs = (get("dateOfBirthMs") as? Number)?.toLong(),
    phone = getString("phone"),
    email = getString("email"),
    address = getString("address"),
    schoolName = getString("schoolName"),
    className = getString("className"),
    guardianName = getString("guardianName"),
    guardianPhone = getString("guardianPhone"),
    guardianEmail = getString("guardianEmail"),
    emergencyContact = getString("emergencyContact"),
    bloodGroup = getString("bloodGroup"),
    admissionDateMs = (get("admissionDateMs") as? Number)?.toLong() ?: 0L,
    status = getString("status") ?: "active",
    notes = getString("notes"),
    createdAtMs = (get("createdAtMs") as? Number)?.toLong() ?: 0L,
    updatedAtMs = (get("updatedAtMs") as? Number)?.toLong() ?: 0L,
    archivedAtMs = (get("archivedAtMs") as? Number)?.toLong(),
    isAppAccessEnabled = getBoolean("isAppAccessEnabled") == true
)

private fun DocumentSnapshot.toInstituteDocument() = InstituteEntity(
    id = id,
    name = getString("name") ?: getString("instituteName") ?: "Institute",
    currentPlanId = getString("currentPlanId") ?: "",
    subscriptionStatus = getString("subscriptionStatus") ?: "",
    trialStartDateMs = (get("trialStartDateMs") as? Number)?.toLong() ?: 0L,
    trialEndDateMs = (get("trialEndDateMs") as? Number)?.toLong() ?: 0L,
    currentPeriodEndMs = (get("currentPeriodEndMs") as? Number)?.toLong() ?: 0L,
    createdAtMs = (get("createdAtMs") as? Number)?.toLong() ?: 0L,
    phone = getString("phone"),
    address = getString("address"),
    whatsappNumber = getString("whatsappNumber"),
    profilePhotoUri = getString("profilePhotoUri"),
    ownerName = getString("ownerName"),
    email = getString("email"),
    instituteCode = getString("instituteCode")
)

private fun shareStudentDocument(context: Context, file: File, label: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, label)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share $label"))
}
