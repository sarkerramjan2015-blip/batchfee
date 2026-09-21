package com.batchfee.edu.ui.studentapp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.domain.OnlinePaymentAllocationResolver
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SpBg = Color(0xFF07111F)
private val SpCard = Color(0xFF0F172A)
private val SpCardAlt = Color(0xFF111827)
private val SpStroke = Color(0xFF1E293B)
private val SpCyan = Color(0xFF22D3EE)
private val SpWhite = Color(0xFFF8FAFC)
private val SpMuted = Color(0xFF94A3B8)
private val SpGreen = Color(0xFF22C55E)
private val SpRed = Color(0xFFEF4444)
private val SpAmber = Color(0xFFF59E0B)
private val SpViolet = Color(0xFF8B5CF6)
private val WaGreen = Color(0xFF25D366)

private data class ActivePaymentMethods(
    val bkashNumber: String? = null,
    val bkashType: String? = null,
    val nagadNumber: String? = null,
    val rocketNumber: String? = null,
    val bankName: String? = null,
    val accountName: String? = null,
    val accountNumber: String? = null,
    val branch: String? = null,
    val routing: String? = null,
    val instructions: String? = null,
    val qrAssetRef: String? = null,
    val allowPartialPayments: Boolean = true,
) {
    val activeCodes: List<String> = buildList {
        if (bkashNumber != null) add("bkash")
        if (nagadNumber != null) add("nagad")
        if (rocketNumber != null) add("rocket")
        if (accountNumber != null && accountName != null) add("bank")
    }
}

private data class MyRequestRow(
    val id: String,
    val amount: Double,
    val months: List<String>,
    val transactionId: String,
    val method: String,
    val status: String,
    val submittedAtMs: Long?,
    val reviewNote: String?,
    val receiptNumber: String?,
    val senderNumber: String,
    val paymentDateMs: Long?,
    val note: String?,
    val screenshotRef: String?,
)

private data class DuePeriodRow(
    val period: String,
    val dueAmount: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentPaymentRequestScreen(preselectedMonth: String?, onBack: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var dueMonths by remember { mutableStateOf<List<OnlinePaymentAllocationResolver.MonthAllocation>>(emptyList()) }
    var settings by remember { mutableStateOf<ActivePaymentMethods?>(null) }
    var myRequests by remember { mutableStateOf<List<MyRequestRow>>(emptyList()) }
    var studentName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var requestSyncError by remember { mutableStateOf<String?>(null) }
    var qrUrl by remember { mutableStateOf<String?>(null) }

    var selectedMonths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf<String?>(null) }
    var transactionId by remember { mutableStateOf("") }
    var senderNumber by remember { mutableStateOf("") }
    var paymentDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    var screenshotUri by remember { mutableStateOf<String?>(null) }
    var screenshotRef by remember { mutableStateOf<String?>(null) }
    var editingRequestId by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(studentId, instituteId) {
        if (studentId.isBlank() || instituteId.isBlank()) {
            loadingError = "Your student session is no longer available. Please sign in again."
            loading = false
            return@LaunchedEffect
        }
        try {
            dueMonths = OnlinePaymentAllocationResolver.dueMonths(instituteId, studentId)
        } catch (_: Exception) {
            loadingError = "Could not load your current dues. Check your connection and try again."
        }
        try {
            val snap = FirebaseFirestore.getInstance()
                .collection("institutes").document(instituteId)
                .collection("payment_settings").document("config").get().await()
            @Suppress("UNCHECKED_CAST")
            val methods = snap.get("methods") as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val bkash = methods["bkash"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val nagad = methods["nagad"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val rocket = methods["rocket"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val bank = methods["bank"] as? Map<String, Any?> ?: emptyMap()
            val bkashActive = bkash["active"] == true
            val nagadActive = nagad["active"] == true
            val rocketActive = rocket["active"] == true
            val bankActive = bank["active"] == true
            settings = ActivePaymentMethods(
                bkashNumber = (bkash["number"] as? String)?.trim()
                    ?.takeIf { bkashActive && it.isNotBlank() },
                bkashType = bkash["type"] as? String,
                nagadNumber = (nagad["number"] as? String)?.trim()
                    ?.takeIf { nagadActive && it.isNotBlank() },
                rocketNumber = (rocket["number"] as? String)?.trim()
                    ?.takeIf { rocketActive && it.isNotBlank() },
                bankName = bank["bankName"] as? String,
                accountName = (bank["accountName"] as? String)?.trim()
                    ?.takeIf { bankActive && it.isNotBlank() },
                accountNumber = (bank["accountNumber"] as? String)?.trim()
                    ?.takeIf { bankActive && it.isNotBlank() },
                branch = bank["branch"] as? String,
                routing = bank["routing"] as? String,
                instructions = snap.getString("instructions"),
                qrAssetRef = snap.getString("qrAssetRef"),
                allowPartialPayments = snap.getBoolean("allowPartialPayments") ?: true,
            )
            settings?.qrAssetRef?.let { ref ->
                qrUrl = FirebaseStorageImageUploadHelper.resolveForDirectRead(context, ref)
            }
        } catch (_: Exception) {
            loadingError = loadingError
                ?: "Could not load the institute's payment methods. Try again shortly."
        }
        try {
            val studentSnap = FirebaseFirestore.getInstance()
                .collection("institutes").document(instituteId)
                .collection("students").document(studentId).get().await()
            studentName = studentSnap.getString("fullName") ?: ""
        } catch (_: Exception) { }
        loading = false
    }

    DisposableEffect(studentId, instituteId) {
        if (studentId.isBlank() || instituteId.isBlank()) { onDispose { }; return@DisposableEffect onDispose { } }
        val listener: ListenerRegistration = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId)
            .collection("payment_requests").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    requestSyncError = "Payment request history could not be refreshed."
                    return@addSnapshotListener
                }
                requestSyncError = null
                myRequests = snap?.documents?.mapNotNull { doc ->
                    MyRequestRow(
                        id = doc.id,
                        amount = (doc.get("amount") as? Number)?.toDouble() ?: 0.0,
                        months = (doc.get("months") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                        transactionId = doc.getString("transactionId").orEmpty(),
                        method = doc.getString("method") ?: "bkash",
                        status = doc.getString("status") ?: "pending",
                        submittedAtMs = (doc.get("submittedAtMs") as? Number)?.toLong(),
                        reviewNote = doc.getString("reviewNote"),
                        receiptNumber = doc.getString("receiptNumber"),
                        senderNumber = doc.getString("senderNumber").orEmpty(),
                        paymentDateMs = (doc.get("paymentDateMs") as? Number)?.toLong(),
                        note = doc.getString("note"),
                        screenshotRef = doc.getString("screenshotRef"),
                    )
                }.orEmpty().sortedByDescending { it.submittedAtMs ?: 0L }
            }
        onDispose { listener.remove() }
    }

    LaunchedEffect(preselectedMonth, dueMonths) {
        if (preselectedMonth != null && selectedMonths.isEmpty()) {
            dueMonths.firstOrNull { it.feePeriod.equals(preselectedMonth, ignoreCase = true) }
                ?.let { selectedMonths = setOf(it.feePeriod) }
        }
    }

    val selectedDue = dueMonths
        .filter { allocation -> selectedMonths.any { it.equals(allocation.feePeriod, ignoreCase = true) } }
        .sumOf { it.dueAmount }
    val duePeriodRows = remember(dueMonths) {
        dueMonths
            .groupBy { it.feePeriod.trim().lowercase(Locale.US) }
            .values
            .map { allocations ->
                DuePeriodRow(
                    period = allocations.first().feePeriod,
                    dueAmount = allocations.sumOf { it.dueAmount },
                )
            }
    }

    LaunchedEffect(selectedMonths, selectedDue, settings?.allowPartialPayments) {
        if (settings?.allowPartialPayments == false && selectedMonths.isNotEmpty()) {
            amount = if (selectedDue % 1.0 == 0.0) {
                "%.0f".format(Locale.US, selectedDue)
            } else {
                "%.2f".format(Locale.US, selectedDue)
            }
        }
    }

    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    screenshotUri = FirebaseStorageImageUploadHelper
                        .cacheSelectedImage(context, uri, "payment_proof").toString()
                } catch (error: Exception) {
                    snackbarHostState.showSnackbar(error.message ?: "Could not load that image.")
                }
            }
        }
    }

    fun startEdit(request: MyRequestRow) {
        editingRequestId = request.id
        selectedMonths = request.months.toSet()
        amount = "%.0f".format(Locale.US, request.amount)
        method = request.method
        transactionId = request.transactionId
        senderNumber = request.senderNumber
        paymentDate = request.paymentDateMs ?: System.currentTimeMillis()
        note = request.note.orEmpty()
        screenshotUri = null
        screenshotRef = request.screenshotRef
    }

    fun submit() {
        if (instituteId.isBlank() || studentId.isBlank()) return
        val amountValue = amount.toDoubleOrNull()
        if (amountValue == null || amountValue <= 0.0) {
            scope.launch { snackbarHostState.showSnackbar("Enter the amount you sent.") }
            return
        }
        if (selectedMonths.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("Select at least one month.") }
            return
        }
        val selectedAllocationCount = dueMonths.count { allocation ->
            selectedMonths.any { it.equals(allocation.feePeriod, ignoreCase = true) }
        }
        if (selectedAllocationCount == 0 || selectedDue <= 0.0) {
            scope.launch { snackbarHostState.showSnackbar("The selected months no longer have an outstanding due.") }
            return
        }
        if (selectedAllocationCount > 24) {
            scope.launch { snackbarHostState.showSnackbar("Select fewer months and submit another request for the rest.") }
            return
        }
        if (settings?.allowPartialPayments == false && amountValue + 0.001 < selectedDue) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Partial payments are disabled. Send the full selected due amount."
                )
            }
            return
        }
        val activeMethods = settings?.activeCodes.orEmpty()
        if (method == null || method !in activeMethods) {
            scope.launch { snackbarHostState.showSnackbar("Select a payment method.") }
            return
        }
        if (transactionId.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Enter the transaction ID.") }
            return
        }
        scope.launch {
            submitting = true
            try {
                val proof = if (screenshotUri != null) {
                    FirebaseStorageImageUploadHelper.uploadPaymentProof(
                        context, Uri.parse(screenshotUri!!), studentId, instituteId, screenshotRef,
                    )
                } else screenshotRef
                val now = System.currentTimeMillis()
                val months = selectedMonths.sortedBy { period -> dueMonths.indexOfFirst { it.feePeriod == period } }
                val editablePayload = mapOf(
                    "amount" to amountValue,
                    "months" to months,
                    "transactionId" to transactionId.trim(),
                    "method" to method!!,
                    "senderNumber" to senderNumber.trim(),
                    "paymentDateMs" to paymentDate,
                    "note" to note.trim().takeIf { it.isNotBlank() },
                    "screenshotRef" to proof,
                    "status" to "pending",
                    "updatedAtMs" to now,
                )
                val collection = FirebaseFirestore.getInstance()
                    .collection("institutes").document(instituteId)
                    .collection("payment_requests")
                if (editingRequestId != null) {
                    // The original submission time and identity are immutable.
                    // Updating only correction-safe fields matches Firestore rules.
                    collection.document(editingRequestId!!).update(editablePayload).await()
                } else {
                    collection.add(
                        editablePayload + mapOf(
                            "instituteId" to instituteId,
                            "studentId" to studentId,
                            "studentName" to studentName,
                            "submittedAtMs" to now,
                        )
                    ).await()
                }
                snackbarHostState.showSnackbar(if (editingRequestId != null) "Payment request resubmitted." else "Payment request submitted. The institute will review it shortly.")
                editingRequestId = null
                selectedMonths = emptySet()
                amount = ""
                method = null
                transactionId = ""
                senderNumber = ""
                note = ""
                screenshotUri = null
                screenshotRef = null
            } catch (error: Exception) {
                snackbarHostState.showSnackbar(error.message ?: "Could not submit the request.")
            }
            submitting = false
        }
    }

    Scaffold(
        containerColor = SpBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Pay Online", color = SpWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SpMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpBg)
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpCyan)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            loadingError?.let { message ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SpCard),
                        border = BorderStroke(1.dp, SpRed.copy(alpha = 0.45f)),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CloudOff, null, tint = SpRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(message, color = SpRed, fontSize = 12.sp)
                        }
                    }
                }
            }
            val active = settings
            if (active == null || active.activeCodes.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SpCard), border = BorderStroke(1.dp, SpAmber.copy(alpha = 0.4f))) {
                        Text(
                            "Online payment is not enabled by your institute yet. Please pay at the institute office.",
                            color = SpAmber, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpCard), border = BorderStroke(1.dp, SpStroke)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Send money to", color = SpWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            active.bkashNumber?.let { SpMethodRow("bKash (${active.bkashType ?: "personal"})", it, "bkash") }
                            active.nagadNumber?.let { SpMethodRow("Nagad", it, "nagad") }
                            active.rocketNumber?.let { SpMethodRow("Rocket", it, "rocket") }
                            if (active.accountNumber != null && active.accountName != null) {
                                SpMethodRow("${active.bankName ?: "Bank"} • ${active.accountName}", active.accountNumber, "bank")
                            }
                            active.instructions?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = SpMuted, fontSize = 11.sp)
                            }
                            qrUrl?.let {
                                Spacer(Modifier.height(10.dp))
                                AsyncImage(
                                    model = it,
                                    contentDescription = "Payment QR",
                                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpCard), border = BorderStroke(1.dp, SpStroke)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Select months", color = SpWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            if (dueMonths.isEmpty()) {
                                Text("No monthly due is available to pay online.", color = SpMuted, fontSize = 12.sp)
                            }
                            duePeriodRows.forEach { month ->
                                val selected = month.period in selectedMonths
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) SpCyan.copy(alpha = 0.14f) else SpCardAlt)
                                        .border(1.dp, if (selected) SpCyan.copy(alpha = 0.6f) else SpStroke, RoundedCornerShape(10.dp))
                                        .clickable {
                                            selectedMonths = if (selected) selectedMonths - month.period else selectedMonths + month.period
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(month.period, color = SpWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text("৳${"%.0f".format(month.dueAmount)}", color = SpAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                        null, tint = if (selected) SpCyan else SpMuted, modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                            }
                            if (dueMonths.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("Selected due: ৳${"%.0f".format(selectedDue)}", color = SpCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpCard), border = BorderStroke(1.dp, SpStroke)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Payment details", color = SpWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 4.dp),
                            ) {
                                active.activeCodes.forEach { code ->
                                    val selected = method == code
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) SpCyan.copy(alpha = 0.18f) else SpCardAlt)
                                            .border(1.dp, if (selected) SpCyan else SpStroke, RoundedCornerShape(10.dp))
                                            .clickable { method = code }
                                            .padding(horizontal = 14.dp, vertical = 7.dp),
                                    ) {
                                        Text(code.uppercase(), color = if (selected) SpCyan else SpMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            SpField("Amount sent (৳)", amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, decimal = true)
                            if (!active.allowPartialPayments) {
                                Text(
                                    "Full payment is required for the selected months.",
                                    color = SpAmber,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            SpField("Transaction ID", transactionId, { transactionId = it })
                            SpField("Sender number", senderNumber, { senderNumber = it })
                            SpField("Note (optional)", note, { note = it })
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Payment date: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(paymentDate))}",
                                    color = SpMuted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { paymentDate = System.currentTimeMillis() }) { Text("Now", color = SpCyan, fontSize = 12.sp) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Payment screenshot (optional)", color = SpWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(SpCardAlt)
                                    .clickable { screenshotPicker.launch("image/*") },
                                contentAlignment = Alignment.Center,
                            ) {
                                val preview = screenshotUri
                                if (preview != null) {
                                    AsyncImage(
                                        model = preview,
                                        contentDescription = "Screenshot",
                                        modifier = Modifier.fillMaxSize().padding(8.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Filled.AddPhotoAlternate, null, tint = SpMuted, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.height(4.dp))
                                        Text("Tap to attach the payment screenshot", color = SpMuted, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (screenshotUri != null) {
                                TextButton(onClick = { screenshotUri = null; screenshotRef = null }) { Text("Remove screenshot", color = SpRed, fontSize = 12.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), SpCyan)))
                                    .clickable(enabled = !submitting) { submit() },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (submitting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                                else Text(
                                    if (editingRequestId != null) "Resubmit Request" else "Submit Payment Request",
                                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            if (myRequests.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("My payment requests", color = SpWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                items(myRequests, key = { it.id }) { request ->
                    val color = when (request.status) {
                        "approved" -> SpGreen
                        "rejected" -> SpRed
                        "correction_requested" -> SpViolet
                        "cancelled" -> SpMuted
                        else -> SpAmber
                    }
                    val label = when (request.status) {
                        "approved" -> "Approved"
                        "rejected" -> "Rejected"
                        "correction_requested" -> "Needs correction"
                        "cancelled" -> "Cancelled"
                        else -> "Pending"
                    }
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SpCard), border = BorderStroke(1.dp, SpStroke)) {
                        Column(Modifier.padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("৳${"%.0f".format(request.amount)} • ${request.months.joinToString(", ")}", color = SpWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${request.method.uppercase()} • ${request.transactionId}", color = SpMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            request.reviewNote?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = SpAmber, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            request.receiptNumber?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("Receipt #$it", color = SpCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (request.status == "correction_requested") {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp))
                                        .background(SpViolet.copy(alpha = 0.15f)).border(1.dp, SpViolet.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .clickable { startEdit(request) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Edit & Resubmit", color = SpViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            requestSyncError?.let { message ->
                item { Text(message, color = SpAmber, fontSize = 11.sp) }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SpMethodRow(label: String, value: String, code: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = when (code) {
            "bkash" -> Color(0xFFE2136E)
            "nagad" -> Color(0xFFF6921E)
            "rocket" -> Color(0xFF8C3494)
            else -> SpCyan
        }
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(code.take(1).uppercase(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = SpMuted, fontSize = 10.sp)
            Text(value, color = SpWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SpField(label: String, value: String, onChange: (String) -> Unit, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(128)) },
        label = { Text(label, color = SpMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SpCyan, unfocusedBorderColor = SpStroke,
            focusedTextColor = SpWhite, unfocusedTextColor = SpWhite, cursorColor = SpCyan,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}
