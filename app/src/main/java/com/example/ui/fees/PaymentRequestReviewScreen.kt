package com.batchfee.edu.ui.fees

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.batchfee.edu.data.repository.GroupedMonthlyCollectionAllocation
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.OnlinePaymentAllocationResolver
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PrBg = Color(0xFF07111F)
private val PrCard = Color(0xFF0F172A)
private val PrCardAlt = Color(0xFF111827)
private val PrStroke = Color(0xFF1E293B)
private val PrCyan = Color(0xFF22D3EE)
private val PrBlue = Color(0xFF3B82F6)
private val PrWhite = Color(0xFFF8FAFC)
private val PrMuted = Color(0xFF94A3B8)
private val PrGreen = Color(0xFF10B981)
private val PrRed = Color(0xFFEF4444)
private val PrAmber = Color(0xFFF59E0B)
private val PrViolet = Color(0xFF8B5CF6)

private data class PaymentRequestRow(
    val id: String,
    val instituteId: String,
    val studentId: String,
    val studentName: String,
    val amount: Double,
    val months: List<String>,
    val transactionId: String,
    val method: String,
    val senderNumber: String?,
    val paymentDateMs: Long?,
    val note: String?,
    val screenshotRef: String?,
    val status: String,
    val submittedAtMs: Long?,
    val reviewedAtMs: Long?,
    val reviewNote: String?,
    val receiptNumber: String?,
    val creditApplied: Double?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentRequestReviewScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onNavigateReceipt: (String) -> Unit
) {
    val currentInstituteId by SessionManager.currentInstituteId.collectAsStateWithLifecycle()
    val instituteId = currentInstituteId.orEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val repository = remember { FeeCollectionRepository(db) }

    var requests by remember { mutableStateOf<List<PaymentRequestRow>>(emptyList()) }
    var reviewed by remember { mutableStateOf<List<PaymentRequestRow>>(emptyList()) }
    var showReviewed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PaymentRequestRow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var allowPartial by remember { mutableStateOf(true) }

    DisposableEffect(instituteId) {
        if (instituteId.isBlank()) { onDispose { }; return@DisposableEffect onDispose { } }
        val collection = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId).collection("payment_requests")
        val pendingListener: ListenerRegistration = collection
            .whereEqualTo("status", "pending")
            .orderBy("submittedAtMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                requests = snap?.documents?.mapNotNull { it.toRow() }.orEmpty()
                loading = false
            }
        val reviewedListener: ListenerRegistration = collection
            .whereIn("status", listOf("approved", "rejected", "correction_requested", "cancelled"))
            .orderBy("reviewedAtMs", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snap, _ ->
                reviewed = snap?.documents?.mapNotNull { it.toRow() }.orEmpty()
            }
        onDispose { pendingListener.remove(); reviewedListener.remove() }
    }

    LaunchedEffect(instituteId) {
        if (instituteId.isBlank()) return@LaunchedEffect
        try {
            val snap = FirebaseFirestore.getInstance()
                .collection("institutes").document(instituteId)
                .collection("payment_settings").document("config").get().await()
            allowPartial = snap.get("allowPartialPayments") as? Boolean ?: true
        } catch (_: Exception) { }
    }

    Scaffold(
        containerColor = PrBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Payment Requests", color = PrWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PrWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrBg)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(false to "Pending (${requests.size})", true to "Reviewed").forEach { (value, label) ->
                    val active = showReviewed == value
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (active) PrCyan else PrCard)
                            .border(1.dp, if (active) PrCyan else PrStroke, RoundedCornerShape(10.dp))
                            .clickable { showReviewed = value }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(label, color = if (active) PrBg else PrMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrCyan, strokeWidth = 3.dp)
                }
            } else {
                val list = if (showReviewed) reviewed else requests
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (showReviewed) "No reviewed requests yet." else "No pending payment requests.",
                            color = PrMuted, fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                        items(list, key = { it.id }) { request ->
                            RequestCard(request, showReviewed) { selected = request }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    selected?.let { request ->
        PaymentRequestDetailDialog(
            request = request,
            instituteId = instituteId,
            allowPartial = allowPartial,
            repository = repository,
            onDismiss = { selected = null },
            onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
            onApproved = { paymentId ->
                selected = null
                onNavigateReceipt(paymentId)
            },
        )
    }
}

private fun com.google.firebase.firestore.DocumentSnapshot.toRow(): PaymentRequestRow? {
    val amount = (get("amount") as? Number)?.toDouble() ?: return null
    return PaymentRequestRow(
        id = id,
        instituteId = getString("instituteId").orEmpty(),
        studentId = getString("studentId").orEmpty(),
        studentName = getString("studentName") ?: "Student",
        amount = amount,
        months = (get("months") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
        transactionId = getString("transactionId").orEmpty(),
        method = getString("method") ?: "bkash",
        senderNumber = getString("senderNumber"),
        paymentDateMs = (get("paymentDateMs") as? Number)?.toLong(),
        note = getString("note"),
        screenshotRef = getString("screenshotRef"),
        status = getString("status") ?: "pending",
        submittedAtMs = (get("submittedAtMs") as? Number)?.toLong(),
        reviewedAtMs = (get("reviewedAtMs") as? Number)?.toLong(),
        reviewNote = getString("reviewNote"),
        receiptNumber = getString("receiptNumber"),
        creditApplied = (get("creditApplied") as? Number)?.toDouble()
    )
}

@Composable
private fun RequestCard(request: PaymentRequestRow, reviewed: Boolean, onClick: () -> Unit) {
    val statusColor = when (request.status) {
        "approved" -> PrGreen
        "rejected" -> PrRed
        "correction_requested" -> PrViolet
        "cancelled" -> PrMuted
        else -> PrAmber
    }
    val statusLabel = when (request.status) {
        "approved" -> "Approved"
        "rejected" -> "Rejected"
        "correction_requested" -> "Needs correction"
        "cancelled" -> "Cancelled"
        else -> "Pending review"
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PrCard),
        border = BorderStroke(1.dp, if (request.status == "pending") PrAmber.copy(alpha = 0.45f) else PrStroke)
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(request.studentName, color = PrWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("ID: ${request.studentId}", color = PrMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("৳${"%.0f".format(request.amount)}", color = PrCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(request.months.joinToString(" · "), color = PrWhite, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${request.method.uppercase()} • TxID ${request.transactionId}",
                color = PrMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val time = request.submittedAtMs
            if (time != null) {
                Text(
                    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(time)),
                    color = PrMuted, fontSize = 10.sp,
                )
            }
            if (reviewed && request.reviewNote != null) {
                Spacer(Modifier.height(4.dp))
                Text(request.reviewNote, color = PrAmber, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PaymentRequestDetailDialog(
    request: PaymentRequestRow,
    instituteId: String,
    allowPartial: Boolean,
    repository: FeeCollectionRepository,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onApproved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var monthDues by remember { mutableStateOf<List<OnlinePaymentAllocationResolver.MonthAllocation>>(emptyList()) }
    var duplicateWarning by remember { mutableStateOf(false) }
    var screenshotUrl by remember { mutableStateOf<String?>(null) }
    var approveAmount by remember { mutableStateOf(request.amount.toString()) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var showCorrectionDialog by remember { mutableStateOf(false) }
    var confirmApprove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    LaunchedEffect(request.id, instituteId) {
        val cloud = FirebaseFirestore.getInstance()
        val instituteRef = cloud.collection("institutes").document(instituteId)
        try {
            monthDues = OnlinePaymentAllocationResolver.resolveMonths(instituteId, request.studentId, request.months)
        } catch (_: Exception) { }
        try {
            val dups = instituteRef.collection("payment_requests")
                .whereEqualTo("transactionId", request.transactionId)
                .whereEqualTo("method", request.method)
                .get(com.google.firebase.firestore.Source.SERVER).await()
            duplicateWarning = dups.documents.any { doc ->
                doc.id != request.id && doc.getString("status") in setOf("approved", "pending")
            }
        } catch (_: Exception) { }
        val ref = request.screenshotRef
        if (!ref.isNullOrBlank()) {
            screenshotUrl = FirebaseStorageImageUploadHelper.resolveForDirectRead(context, ref)
        }
    }

    fun runReview(decision: String, note: String? = null) {
        scope.launch {
            busy = true
            try {
                if (decision == "approve") {
                    val amount = approveAmount.toDoubleOrNull()
                    if (amount == null || amount <= 0.0) {
                        onError("Enter a valid approval amount.")
                        return@launch
                    }
                    val allocations = monthDues.map { month ->
                        GroupedMonthlyCollectionAllocation(
                            feeId = month.feeId,
                            batchId = month.batchId,
                            feePeriod = month.feePeriod,
                            feeType = month.feeType,
                            sourceId = null,
                            dueDateMs = month.dueDateMs,
                            baseAmount = month.baseAmount,
                            discountAmount = month.discountAmount,
                            lateFeeAmount = month.lateFeeAmount,
                            amount = month.dueAmount
                        )
                    }
                    if (allocations.isEmpty()) {
                        onError("The selected months have no remaining due. Refresh and try again.")
                        return@launch
                    }
                    val result = repository.reviewPaymentRequest(
                        instituteId = instituteId,
                        requestId = request.id,
                        decision = "approve",
                        allocations = allocations,
                        approvedAmount = amount,
                        reviewNote = null,
                        receiptText = "Online payment approved: ${request.method.uppercase()} ${request.transactionId}."
                    )
                    val paymentId = result.payments.firstOrNull()?.id
                    if (paymentId == null) {
                        onError("Approval completed but no payment record was returned. Refresh to confirm.")
                        return@launch
                    }
                    onApproved(paymentId)
                } else {
                    repository.reviewPaymentRequest(
                        instituteId = instituteId,
                        requestId = request.id,
                        decision = decision,
                        reviewNote = note,
                    )
                    onDismiss()
                }
            } catch (error: Exception) {
                val message = error.message ?: "Review failed."
                if (error is com.batchfee.edu.data.repository.FinancialOperationRejectedException) {
                    FirebaseFailureReporter.report(error, operation = "payment request review")
                }
                onError(message)
            } finally {
                // Validation returns above must never leave the review dialog
                // permanently locked in its loading state.
                busy = false
            }
        }
    }

    val totalDue = monthDues.sumOf { it.dueAmount }
    val enteredAmount = approveAmount.toDoubleOrNull() ?: 0.0
    val partialBlocked = !allowPartial && enteredAmount + 0.001 < totalDue

    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PrCard),
            border = BorderStroke(1.dp, PrStroke)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(request.studentName, color = PrWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("ID: ${request.studentId}", color = PrMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = PrRed) }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = PrStroke)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (request.status != "pending") {
                        val color = when (request.status) {
                            "approved" -> PrGreen
                            "rejected" -> PrRed
                            "correction_requested" -> PrViolet
                            else -> PrMuted
                        }
                        Text("Status: ${request.status.replace('_', ' ')}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        request.reviewNote?.let { Text("Review note: $it", color = PrAmber, fontSize = 12.sp) }
                        request.receiptNumber?.let { Text("Receipt: $it", color = PrCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                    }

                    PrDetailRow("Submitted amount", "৳${"%.2f".format(request.amount)}")
                    PrDetailRow("Payment method", request.method.uppercase())
                    PrDetailRow("Transaction ID", request.transactionId)
                    PrDetailRow("Sender number", request.senderNumber ?: "—")
                    request.paymentDateMs?.let { PrDetailRow("Payment date", df.format(Date(it))) }
                    PrDetailRow("Note", request.note?.takeIf { it.isNotBlank() } ?: "—")

                    Spacer(Modifier.height(8.dp))
                    Text("Selected months", color = PrWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    if (monthDues.isEmpty()) {
                        Text("Due amounts are being loaded…", color = PrMuted, fontSize = 11.sp)
                    }
                    monthDues.forEach { month ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = PrCardAlt)
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(month.feePeriod, color = PrWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("Due ৳${"%.2f".format(month.dueAmount)}", color = PrAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (monthDues.isNotEmpty()) {
                        PrDetailRow("Total due for selection", "৳${"%.2f".format(totalDue)}")
                    }

                    if (duplicateWarning) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PrRed.copy(alpha = 0.12f)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = PrRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Possible duplicate: this transaction ID was already used for this method.",
                                color = PrRed, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (!request.screenshotRef.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Payment proof", color = PrWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val url = screenshotUrl
                        Box(
                            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(PrCardAlt),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (url != null) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = "Payment screenshot",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                CircularProgressIndicator(color = PrCyan, strokeWidth = 2.dp)
                            }
                        }
                    }

                    if (request.status == "pending") {
                        Spacer(Modifier.height(12.dp))
                        Text("Approval amount", color = PrWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = approveAmount,
                            onValueChange = { approveAmount = it.filter { c -> c.isDigit() || c == '.' } },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrCyan, unfocusedBorderColor = PrStroke,
                                focusedTextColor = PrWhite, unfocusedTextColor = PrWhite, cursorColor = PrCyan,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (partialBlocked) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Partial payments are disabled in Online Payment settings.",
                                color = PrRed, fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (request.status == "pending" && !busy) {
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(PrGreen, PrCyan)))
                            .clickable(enabled = !partialBlocked && enteredAmount > 0.0) { confirmApprove = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Approve Payment", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(PrViolet.copy(alpha = 0.15f)).border(1.dp, PrViolet.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { showCorrectionDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Request Correction", color = PrViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(PrRed.copy(alpha = 0.15f)).border(1.dp, PrRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { showRejectDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Reject", color = PrRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (busy) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrCyan, strokeWidth = 2.5.dp)
                    }
                }
            }
        }
    }

    if (confirmApprove) {
        AlertDialog(
            onDismissRequest = { confirmApprove = false },
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(PrGreen.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle, null, tint = PrGreen, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Approve this payment?", color = PrWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            } },
            text = {
                Column {
                    Surface(shape = RoundedCornerShape(12.dp), color = PrCardAlt) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${request.studentName} • ${request.studentId}", color = PrWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Amount: ৳${"%.2f".format(enteredAmount)}", color = PrCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("${request.method.uppercase()} • ${request.transactionId}", color = PrMuted, fontSize = 12.sp)
                            Text("Months: ${request.months.joinToString(", ")}", color = PrMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This creates the payment, marks the selected months paid and generates a receipt. It cannot be undone without a payment reversal.",
                        color = PrMuted, fontSize = 12.sp,
                    )
                    if (duplicateWarning) {
                        Spacer(Modifier.height(8.dp))
                        Text("Warning: a duplicate transaction ID was detected.", color = PrRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmApprove = false; runReview("approve") },
                    colors = ButtonDefaults.buttonColors(containerColor = PrGreen),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Yes, Approve Payment", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmApprove = false }) { Text("Keep Pending", color = PrMuted) }
            },
            containerColor = PrCard,
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showRejectDialog) {
        PrNoteDialog(
            title = "Reject request",
            accent = PrRed,
            confirmLabel = "Yes, Reject",
            onDismiss = { showRejectDialog = false },
            onConfirm = { note -> showRejectDialog = false; runReview("reject", note) },
        )
    }
    if (showCorrectionDialog) {
        PrNoteDialog(
            title = "Request correction",
            accent = PrViolet,
            confirmLabel = "Send Back",
            onDismiss = { showCorrectionDialog = false },
            onConfirm = { note -> showCorrectionDialog = false; runReview("correction", note) },
        )
    }
}

@Composable
private fun PrNoteDialog(
    title: String,
    accent: Color,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = PrWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("The guardian will see this note in the app.", color = PrMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    placeholder = { Text("Explain why…", color = PrMuted) },
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = PrStroke,
                        focusedTextColor = PrWhite, unfocusedTextColor = PrWhite, cursorColor = accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (note.trim().length >= 3) onConfirm(note.trim()) },
                enabled = note.trim().length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(10.dp),
            ) { Text(confirmLabel, color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PrMuted) } },
        containerColor = PrCard,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun PrDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = PrMuted, fontSize = 12.sp, modifier = Modifier.width(130.dp))
        Text(value, color = PrWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}
