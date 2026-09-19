package com.batchfee.edu.ui.fees

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val PsBg = Color(0xFF07111F)
private val PsCard = Color(0xFF0F172A)
private val PsCardAlt = Color(0xFF111827)
private val PsStroke = Color(0xFF1E293B)
private val PsCyan = Color(0xFF22D3EE)
private val PsWhite = Color(0xFFF8FAFC)
private val PsMuted = Color(0xFF94A3B8)
private val PsGreen = Color(0xFF10B981)
private val PsRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentSettingsScreen(db: AppDatabase, onBack: () -> Unit) {
    val instituteId = SessionManager.currentInstituteId.value.orEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var bkashNumber by remember { mutableStateOf("") }
    var bkashType by remember { mutableStateOf("personal") }
    var bkashActive by remember { mutableStateOf(false) }
    var nagadNumber by remember { mutableStateOf("") }
    var nagadActive by remember { mutableStateOf(false) }
    var rocketNumber by remember { mutableStateOf("") }
    var rocketActive by remember { mutableStateOf(false) }
    var bankName by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var accountNumber by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var routing by remember { mutableStateOf("") }
    var bankActive by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf("") }
    var allowPartial by remember { mutableStateOf(true) }
    var qrRef by remember { mutableStateOf<String?>(null) }
    var qrPreviewUri by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(instituteId) {
        if (instituteId.isBlank()) return@LaunchedEffect
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
            bkashNumber = bkash["number"] as? String ?: ""
            bkashType = bkash["type"] as? String ?: "personal"
            bkashActive = bkash["active"] == true
            nagadNumber = nagad["number"] as? String ?: ""
            nagadActive = nagad["active"] == true
            rocketNumber = rocket["number"] as? String ?: ""
            rocketActive = rocket["active"] == true
            bankName = bank["bankName"] as? String ?: ""
            accountName = bank["accountName"] as? String ?: ""
            accountNumber = bank["accountNumber"] as? String ?: ""
            branch = bank["branch"] as? String ?: ""
            routing = bank["routing"] as? String ?: ""
            bankActive = bank["active"] == true
            instructions = snap.getString("instructions") ?: ""
            allowPartial = snap.get("allowPartialPayments") as? Boolean ?: true
            qrRef = snap.getString("qrAssetRef")
            qrPreviewUri = qrRef?.let { FirebaseStorageImageUploadHelper.displaySource(context, it) }
        } catch (_: Exception) { }
        loaded = true
    }

    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val cached = FirebaseStorageImageUploadHelper.cacheSelectedImage(context, uri, "payment_qr")
                    qrPreviewUri = cached.toString()
                } catch (error: Exception) {
                    snackbarHostState.showSnackbar(error.message ?: "Could not load that image.")
                }
            }
        }
    }

    fun save() {
        if (instituteId.isBlank()) return
        scope.launch {
            saving = true
            try {
                val uploadedQr = if (qrPreviewUri != null) {
                    FirebaseStorageImageUploadHelper.uploadPaymentQr(
                        context, Uri.parse(qrPreviewUri!!), qrRef,
                    )
                } else qrRef
                val doc = mapOf(
                    "methods" to mapOf(
                        "bkash" to mapOf(
                            "number" to bkashNumber.trim(),
                            "type" to bkashType,
                            "active" to (bkashActive && bkashNumber.isNotBlank()),
                        ),
                        "nagad" to mapOf(
                            "number" to nagadNumber.trim(),
                            "active" to (nagadActive && nagadNumber.isNotBlank()),
                        ),
                        "rocket" to mapOf(
                            "number" to rocketNumber.trim(),
                            "active" to (rocketActive && rocketNumber.isNotBlank()),
                        ),
                        "bank" to mapOf(
                            "bankName" to bankName.trim(),
                            "accountName" to accountName.trim(),
                            "accountNumber" to accountNumber.trim(),
                            "branch" to branch.trim(),
                            "routing" to routing.trim(),
                            "active" to (bankActive && accountNumber.isNotBlank() && accountName.isNotBlank()),
                        ),
                    ),
                    "instructions" to instructions.trim(),
                    "qrAssetRef" to uploadedQr,
                    "allowPartialPayments" to allowPartial,
                    "updatedAtMs" to System.currentTimeMillis(),
                )
                FirebaseFirestore.getInstance()
                    .collection("institutes").document(instituteId)
                    .collection("payment_settings").document("config").set(doc).await()
                snackbarHostState.showSnackbar("Online payment settings saved.")
            } catch (error: Exception) {
                snackbarHostState.showSnackbar(error.message ?: "Failed to save settings.")
            }
            saving = false
        }
    }

    Scaffold(
        containerColor = PsBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Online Payments", color = PsWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PsWhite) } },
                actions = {
                    TextButton(onClick = { save() }, enabled = !saving) {
                        if (saving) CircularProgressIndicator(color = PsCyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Text("Save", color = PsCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PsBg)
            )
        }
    ) { padding ->
        if (!loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PsCyan, strokeWidth = 3.dp)
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            PsSectionCard("bKash") {
                PsTextField("bKash number", bkashNumber, { bkashNumber = it }, leading = "013…")
                PsTypeChips(listOf("personal" to "Personal", "merchant" to "Merchant"), bkashType) { bkashType = it }
                PsSwitchRow("bKash is active", bkashActive) { bkashActive = it }
            }
            PsSectionCard("Nagad") {
                PsTextField("Nagad number", nagadNumber, { nagadNumber = it }, leading = "013…")
                PsSwitchRow("Nagad is active", nagadActive) { nagadActive = it }
            }
            PsSectionCard("Rocket") {
                PsTextField("Rocket number", rocketNumber, { rocketNumber = it }, leading = "017…")
                PsSwitchRow("Rocket is active", rocketActive) { rocketActive = it }
            }
            PsSectionCard("Bank transfer") {
                PsTextField("Bank name", bankName, { bankName = it })
                PsTextField("Account name", accountName, { accountName = it })
                PsTextField("Account number", accountNumber, { accountNumber = it })
                PsTextField("Branch (optional)", branch, { branch = it })
                PsTextField("Routing number (optional)", routing, { routing = it })
                PsSwitchRow("Bank transfer is active", bankActive) { bankActive = it }
            }
            PsSectionCard("Payment instructions") {
                PsMultiLine("Instructions shown to guardians", instructions, { instructions = it })
            }
            PsSectionCard("QR code") {
                Text(
                    "Upload a payment QR so guardians can scan it from the request screen.",
                    color = PsMuted, fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                        .background(PsCardAlt).clickable { qrPicker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = qrPreviewUri
                    if (preview != null) {
                        AsyncImage(
                            model = preview,
                            contentDescription = "Payment QR",
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.QrCode, null, tint = PsMuted, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("Tap to upload QR image", color = PsMuted, fontSize = 12.sp)
                        }
                    }
                }
                if (qrPreviewUri != null) {
                    TextButton(onClick = { qrPreviewUri = null; qrRef = null }) {
                        Text("Remove QR", color = PsRed, fontSize = 12.sp)
                    }
                }
            }
            PsSectionCard("Approval rules") {
                PsSwitchRow("Allow partial payments", allowPartial) { allowPartial = it }
                Text(
                    "When off, a guardian submission that does not fully cover the selected months cannot be approved.",
                    color = PsMuted, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PsCard),
        border = BorderStroke(1.dp, PsStroke),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = PsWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PsTextField(label: String, value: String, onChange: (String) -> Unit, leading: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(64)) },
        label = { Text(label, color = PsMuted) },
        leadingIcon = if (leading.isNotBlank()) {
            { Text(leading, color = PsMuted, fontSize = 13.sp) }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PsCyan,
            unfocusedBorderColor = PsStroke,
            focusedTextColor = PsWhite,
            unfocusedTextColor = PsWhite,
            cursorColor = PsCyan,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun PsMultiLine(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(500)) },
        label = { Text(label, color = PsMuted) },
        minLines = 3,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PsCyan,
            unfocusedBorderColor = PsStroke,
            focusedTextColor = PsWhite,
            unfocusedTextColor = PsWhite,
            cursorColor = PsCyan,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PsTypeChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        options.forEach { (value, label) ->
            val active = selected == value
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (active) PsCyan.copy(alpha = 0.18f) else PsCardAlt)
                    .border(1.dp, if (active) PsCyan else PsStroke, RoundedCornerShape(10.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(label, color = if (active) PsCyan else PsMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PsWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PsGreen,
                uncheckedTrackColor = PsStroke,
            ),
        )
    }
}
