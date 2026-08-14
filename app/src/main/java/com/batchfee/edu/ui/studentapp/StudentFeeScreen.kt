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

data class FeeCardInfo(val id: String, val description: String, val monthYear: String?, val totalAmount: Double, val paidAmount: Double, val status: String)
data class PaymentReceipt(val id: String, val amount: Double, val dateMs: Long, val method: String, val receiptNumber: String?, val note: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentFeeScreen(onBack: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    var fees by remember(studentId, instituteId) { mutableStateOf<List<FeeCardInfo>>(emptyList()) }
    var receipts by remember(studentId, instituteId) { mutableStateOf<List<PaymentReceipt>>(emptyList()) }
    var totalAmount by remember(studentId) { mutableStateOf(0.0) }
    var totalPaid by remember(studentId) { mutableStateOf(0.0) }
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

    DisposableEffect(instituteId, studentId) {
        if (instituteId.isBlank() || studentId.isBlank()) {
            onDispose { }
        } else {
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()
        listeners += fs.collection("institutes").document(instituteId).collection("fees").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                val list = snap?.documents?.map { doc ->
                    FeeCardInfo(id = doc.id, description = doc.getString("description") ?: doc.getString("monthYear") ?: "Fee", monthYear = doc.getString("monthYear"), totalAmount = doc.getDouble("totalAmount") ?: 0.0, paidAmount = doc.getDouble("paidAmount") ?: 0.0, status = doc.getString("status") ?: "pending")
                }?.sortedByDescending { it.totalAmount - it.paidAmount } ?: emptyList()
                fees = list; totalAmount = list.sumOf { it.totalAmount }; totalPaid = list.sumOf { it.paidAmount }; loading = false
            }
        listeners += fs.collection("institutes").document(instituteId).collection("payments").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                receipts = snap?.documents?.map { doc ->
                    PaymentReceipt(id = doc.id, amount = doc.getDouble("amount") ?: 0.0, dateMs = (doc.get("paymentDateMs") as? Number)?.toLong() ?: 0L, method = doc.getString("paymentMethod") ?: "Cash", receiptNumber = doc.getString("receiptNumber"), note = doc.getString("note"))
                }?.sortedByDescending { it.dateMs } ?: emptyList()
            }
        onDispose { listeners.forEach { it.remove() } }
        }
    }

    val totalDue = totalAmount - totalPaid

    Scaffold(containerColor = FsBg,
        topBar = { TopAppBar(title = { Text("Fees & Receipts", color = FsWhite, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FsMuted) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = FsBg)) }
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
            Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = FsCard), border = BorderStroke(1.dp, FsStroke)) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FeeS("Total", "৳${"%,.0f".format(totalAmount)}", FsCyan)
                    FeeS("Paid", "৳${"%,.0f".format(totalPaid)}", FsGreen)
                    FeeS("Due", "৳${"%,.0f".format(totalDue)}", if (totalDue > 0) FsRed else FsGreen)
                }
            }
            if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FsCyan) }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fees.isNotEmpty()) {
                    item { Text("Fees", color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 2.dp)) }
                }
                items(fees) { fee ->
                    val due = fee.totalAmount - fee.paidAmount
                    val expanded = selectedFeeId == fee.id
                    Card(Modifier.fillMaxWidth().clickable { selectedFeeId = if (expanded) null else fee.id }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (due > 0) FsCard else FsGreen.copy(alpha = 0.08f)), border = BorderStroke(1.dp, FsStroke)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fee.description, color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                fee.monthYear?.let { Text(it, color = FsMuted, fontSize = 12.sp) }
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
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = FsCard), border = BorderStroke(1.dp, FsStroke)) {
                            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(FsGreen.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.ReceiptLong, null, tint = FsGreen, modifier = Modifier.size(18.dp)) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("৳${"%,.0f".format(r.amount)} · ${r.method}", color = FsWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(df.format(Date(r.dateMs)), color = FsMuted, fontSize = 11.sp)
                                    r.receiptNumber?.let { Text("Receipt #$it", color = FsCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                }
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

@Composable private fun FeeS(label: String, value: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp); Text(label, color = FsMuted, fontSize = 11.sp) }
