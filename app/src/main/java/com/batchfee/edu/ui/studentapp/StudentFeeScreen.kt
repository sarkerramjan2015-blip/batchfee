package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.*

private val FsBg     = Color(0xFF07111F)
private val FsCard   = Color(0xFF0F172A)
private val FsStroke = Color(0xFF1E293B)
private val FsCyan   = Color(0xFF22D3EE)
private val FsGreen  = Color(0xFF22C55E)
private val FsRed    = Color(0xFFEF4444)
private val FsWhite  = Color(0xFFF8FAFC)
private val FsMuted  = Color(0xFF94A3B8)
private val FsDim    = Color(0xFF64748B)

data class FeeCardInfo(
    val id: String,
    val description: String,
    val monthYear: String?,
    val dueDateMs: Long?,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double,
    val status: String,
    val batchId: String? = null,
    val feeType: String? = null
)
data class PaymentReceipt(val id: String, val amount: Double, val dateMs: Long, val method: String, val receiptNumber: String?, val note: String?)

private data class StudentBillingEnrollment(
    val batchId: String,
    val status: String,
    val joinedAtMs: Long,
    val leftAtMs: Long?,
    val firstMonthFeePeriod: String?,
    val firstMonthFeeAmount: Double?,
    val customMonthlyFeeAmount: Double?,
    val customFeeEffectiveFromPeriod: String?
)

private data class StudentBillingBatch(
    val id: String,
    val name: String,
    val monthlyFeeAmount: Double,
    val admissionFeeAmount: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentFeeScreen(onBack: () -> Unit, onOpenDocuments: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    var fees by remember(studentId, instituteId) { mutableStateOf<List<FeeCardInfo>>(emptyList()) }
    var actualFees by remember(studentId, instituteId) { mutableStateOf<List<FeeCardInfo>>(emptyList()) }
    var billingEnrollments by remember(studentId, instituteId) { mutableStateOf<List<StudentBillingEnrollment>>(emptyList()) }
    var billingBatches by remember(studentId, instituteId) { mutableStateOf<Map<String, StudentBillingBatch>>(emptyMap()) }
    var receipts by remember(studentId, instituteId) { mutableStateOf<List<PaymentReceipt>>(emptyList()) }
    var totalAmount by remember(studentId) { mutableStateOf(0.0) }
    var totalPaid by remember(studentId) { mutableStateOf(0.0) }
    var totalDue by remember(studentId) { mutableStateOf(0.0) }
    var loading by remember(studentId, instituteId) { mutableStateOf(true) }
    var selectedFeeId by remember(studentId) { mutableStateOf<String?>(null) }
    var syncError by remember(studentId, instituteId) { mutableStateOf<String?>(null) }
    val df = remember { SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault()) }

    fun reportListenerError(error: FirebaseFirestoreException?) {
        if (error != null) {
            loading = false
            syncError = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Live access is no longer available. Please sign in again."
            } else {
                "Live updates are paused. Check your connection."
            }
        }
    }

    LaunchedEffect(actualFees, billingEnrollments, billingBatches) {
        // The current month's monthly fee is not overdue. Keep one-time fees
        // (including exam fees) immediately visible, and retain paid monthly
        // history, but do not present an unpaid running-month fee as due.
        val visibleActualFees = actualFees.filterNot { fee ->
            MonthlyDueCalculator.isMonthlyFeeType(fee.feeType.orEmpty()) &&
                fee.dueAmount > 0.0 &&
                !MonthlyDueCalculator.isMonthlyInstallmentDue(
                    fee.feeType.orEmpty(),
                    fee.monthYear.orEmpty()
                )
        }
        val actualMonthlyKeys = visibleActualFees
            .filter { MonthlyDueCalculator.isMonthlyFeeType(it.feeType.orEmpty()) }
            .map { "${it.batchId.orEmpty()}|${it.monthYear.orEmpty()}" }
            .toSet()
        val virtualMonthly = billingEnrollments.flatMap { enrollment ->
            val batch = billingBatches[enrollment.batchId] ?: return@flatMap emptyList()
            val existingMonthly = actualFees
                .filter {
                    it.batchId == batch.id &&
                        MonthlyDueCalculator.isMonthlyFeeType(it.feeType.orEmpty())
                }
                .map { it.toLedgerFee(instituteId, studentId) }
            MonthlyDueCalculator.computeMonthlyOutstandingItems(
                admissionDateMs = enrollment.joinedAtMs,
                monthlyFeeAmount = batch.monthlyFeeAmount,
                batchId = batch.id,
                batchName = batch.name,
                existingMonthlyFees = existingMonthly,
                firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                firstMonthFeeAmount = enrollment.firstMonthFeeAmount,
                customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                customFeeEffectiveFromPeriod = enrollment.customFeeEffectiveFromPeriod,
                billingEndedAtMs = enrollment.leftAtMs
            ).mapNotNull { item ->
                val key = "${batch.id}|${item.period}"
                if (key in actualMonthlyKeys) null else FeeCardInfo(
                    id = "virtual:${batch.id}:${item.period}",
                    description = "Monthly fee",
                    monthYear = item.period,
                    dueDateMs = null,
                    totalAmount = item.monthlyFeeAmount,
                    paidAmount = item.paidAmount,
                    dueAmount = item.outstanding,
                    status = if (item.paidAmount > 0.0) "partially_paid" else "unpaid",
                    batchId = batch.id,
                    feeType = "monthly_fee"
                )
            }
        }
        val virtualAdmission = billingEnrollments
            .filter { it.status.equals("active", ignoreCase = true) }
            .mapNotNull { enrollment ->
                val batch = billingBatches[enrollment.batchId] ?: return@mapNotNull null
                val alreadyCreated = actualFees.any {
                    it.batchId == batch.id && it.feeType.equals("admission_fee", ignoreCase = true)
                }
                if (batch.admissionFeeAmount <= 0.0 || alreadyCreated) null else FeeCardInfo(
                    id = "virtual:admission:${batch.id}",
                    description = "Admission fee",
                    monthYear = "Admission",
                    dueDateMs = null,
                    totalAmount = batch.admissionFeeAmount,
                    paidAmount = 0.0,
                    dueAmount = batch.admissionFeeAmount,
                    status = "unpaid",
                    batchId = batch.id,
                    feeType = "admission_fee"
                )
            }
        val merged = (visibleActualFees + virtualMonthly + virtualAdmission)
            .sortedWith(compareByDescending<FeeCardInfo> { it.dueAmount }.thenByDescending { it.totalAmount })
        fees = merged
        totalAmount = merged.sumOf { it.totalAmount }
        totalPaid = merged.sumOf { it.paidAmount }
        totalDue = merged.sumOf { it.dueAmount }
    }

    DisposableEffect(instituteId, studentId) {
        if (instituteId.isBlank() || studentId.isBlank()) {
            onDispose { }
        } else {
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()
        listeners += fs.collection("institutes").document(instituteId).collection("batch_students")
            .whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                billingEnrollments = snap?.documents?.mapNotNull { doc ->
                    doc.getString("batchId")?.takeIf { it.isNotBlank() }?.let { batchId ->
                        StudentBillingEnrollment(
                            batchId = batchId,
                            status = doc.getString("status") ?: "active",
                            joinedAtMs = (doc.get("joinedAtMs") as? Number)?.toLong() ?: 0L,
                            leftAtMs = (doc.get("leftAtMs") as? Number)?.toLong(),
                            firstMonthFeePeriod = doc.getString("firstMonthFeePeriod"),
                            firstMonthFeeAmount = (doc.get("firstMonthFeeAmount") as? Number)?.toDouble(),
                            customMonthlyFeeAmount = (doc.get("customMonthlyFeeAmount") as? Number)?.toDouble(),
                            customFeeEffectiveFromPeriod = doc.getString("customFeeEffectiveFromPeriod")
                        )
                    }
                }.orEmpty()
            }
        listeners += fs.collection("institutes").document(instituteId).collection("batches")
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                billingBatches = snap?.documents?.mapNotNull { doc ->
                    val id = doc.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    id to StudentBillingBatch(
                        id = id,
                        name = doc.getString("name") ?: "Batch",
                        monthlyFeeAmount = doc.getDouble("monthlyFeeAmount") ?: 0.0,
                        admissionFeeAmount = doc.getDouble("admissionFeeAmount") ?: 0.0
                    )
                }?.toMap().orEmpty()
            }
        listeners += fs.collection("institutes").document(instituteId).collection("fees").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                val list = snap?.documents?.map { doc ->
                    val feeType = doc.getString("feeType")
                    val amount = doc.getDouble("totalAmount") ?: 0.0
                    val paid = doc.getDouble("paidAmount") ?: 0.0
                    FeeCardInfo(
                        id = doc.id,
                        description = studentFeeTitle(feeType),
                        monthYear = doc.getString("feePeriod") ?: doc.getString("monthYear"),
                        dueDateMs = (doc.get("dueDateMs") as? Number)?.toLong(),
                        totalAmount = amount,
                        paidAmount = paid,
                        dueAmount = (doc.getDouble("dueAmount")
                            ?: if (doc.getString("status") == "cancelled") 0.0 else (amount - paid)).coerceAtLeast(0.0),
                        status = doc.getString("status") ?: "pending",
                        batchId = doc.getString("batchId"),
                        feeType = feeType
                    )
                }?.filter { it.status != "cancelled" } ?: emptyList()
                actualFees = list
                loading = false
            }
        listeners += fs.collection("institutes").document(instituteId).collection("payments").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                receipts = snap?.documents
                    ?.filter { it.getString("status") == "completed" }
                    ?.map { doc ->
                    PaymentReceipt(id = doc.id, amount = doc.getDouble("amount") ?: 0.0, dateMs = (doc.get("paymentDateMs") as? Number)?.toLong() ?: 0L, method = doc.getString("paymentMethod") ?: "Cash", receiptNumber = doc.getString("receiptNumber"), note = doc.getString("note"))
                }?.sortedByDescending { it.dateMs } ?: emptyList()
            }
        onDispose { listeners.forEach { it.remove() } }
        }
    }

    Scaffold(containerColor = FsBg,
        topBar = {
            TopAppBar(
                title = { Text("Fees & Receipts", color = FsWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FsMuted) } },
                actions = { IconButton(onOpenDocuments) { Icon(Icons.Filled.FolderShared, "Open receipt documents", tint = FsCyan) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FsBg)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(FsBg)) {
            syncError?.let { message ->
                Surface(color = FsRed.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SyncProblem, null, tint = FsRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(message, color = FsRed, fontSize = 12.sp)
                    }
                }
            }
            Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = FsCard), border = BorderStroke(1.dp, FsStroke)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your fee overview", color = FsWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(if (totalDue > 0) "Payment due" else "All clear", color = if (totalDue > 0) FsRed else FsGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(13.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FeeS("Total", "৳${"%,.0f".format(totalAmount)}", FsCyan)
                    FeeS("Paid", "৳${"%,.0f".format(totalPaid)}", FsGreen)
                    FeeS("Due", "৳${"%,.0f".format(totalDue)}", if (totalDue > 0) FsRed else FsGreen)
                    }
                }
            }
            if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FsCyan) }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fees.isNotEmpty()) {
                    item { Text("Fees", color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 2.dp)) }
                }
                items(fees) { fee ->
                    val due = fee.dueAmount
                    val expanded = selectedFeeId == fee.id
                    Card(Modifier.fillMaxWidth().clickable { selectedFeeId = if (expanded) null else fee.id }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (due > 0) FsCard else FsGreen.copy(alpha = 0.08f)), border = BorderStroke(1.dp, FsStroke)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fee.description, color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                fee.monthYear?.let { Text(it, color = FsMuted, fontSize = 12.sp) }
                                fee.dueDateMs?.takeIf { it > 0L }?.let {
                                    Text("Due ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it))}", color = FsDim, fontSize = 11.sp)
                                }
                                if (due > 0) Text("Due: ৳${"%,.0f".format(due)}", color = FsRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                else Text("Paid ✓", color = FsGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("৳${"%,.0f".format(fee.totalAmount)}", color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.ChevronRight, null, tint = FsDim, modifier = Modifier.size(20.dp))
                        }
                        if (expanded && fee.paidAmount > 0) {
                            HorizontalDivider(color = FsStroke, modifier = Modifier.padding(horizontal = 16.dp))
                            Column(Modifier.padding(16.dp)) {
                                Text("Payment Details", color = FsCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("Amount Paid: ৳${"%,.0f".format(fee.paidAmount)}", color = FsWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (receipts.isNotEmpty()) {
                    item { Spacer(Modifier.height(6.dp)); Text("Payment History", color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 2.dp)) }
                    items(receipts) { r ->
                        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDocuments), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = FsCard), border = BorderStroke(1.dp, FsStroke)) {
                            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(FsGreen.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.ReceiptLong, null, tint = FsGreen, modifier = Modifier.size(18.dp)) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("৳${"%,.0f".format(r.amount)} · ${r.method}", color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(df.format(Date(r.dateMs)), color = FsMuted, fontSize = 11.sp)
                                    r.receiptNumber?.let { Text("Receipt #$it", color = FsCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                }
                                Icon(Icons.Filled.PictureAsPdf, "Open receipt", tint = FsCyan, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (fees.isEmpty() && receipts.isEmpty()) { item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("No fees or payments yet.", color = FsMuted) } } }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

private fun FeeCardInfo.toLedgerFee(instituteId: String, studentId: String) = FeeEntity(
    id = id,
    instituteId = instituteId,
    studentId = studentId,
    batchId = batchId,
    feePeriod = monthYear.orEmpty(),
    feeType = feeType ?: "monthly_fee",
    dueDateMs = dueDateMs ?: 0L,
    baseAmount = totalAmount,
    discountAmount = 0.0,
    lateFeeAmount = 0.0,
    totalAmount = totalAmount,
    paidAmount = paidAmount,
    dueAmount = dueAmount,
    status = status,
    note = null,
    createdAtMs = 0L,
    updatedAtMs = 0L,
    cancelledAtMs = null
)

@Composable private fun FeeS(label: String, value: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp); Text(label, color = FsMuted, fontSize = 11.sp) }

private fun studentFeeTitle(type: String?): String = when (type?.lowercase(Locale.US)) {
    "monthly_fee", "monthly" -> "Monthly fee"
    "admission_fee", "admission" -> "Admission fee"
    "advance_fee", "advance" -> "Advance fee"
    "exam_fee", "exam" -> "Exam fee"
    else -> type?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() } ?: "Institute fee"
}
