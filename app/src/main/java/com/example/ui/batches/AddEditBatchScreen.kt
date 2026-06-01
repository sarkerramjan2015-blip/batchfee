package com.example.ui.batches

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import kotlinx.coroutines.launch

// ── Colors (matching PricingScreen) ─────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBatchScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: BatchViewModel = viewModel(factory = BatchViewModelFactory(db))
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Form state
    var name by remember { mutableStateOf("") }
    var feeString by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    // Validation
    var nameError by remember { mutableStateOf(false) }
    var feeError by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Add Batch", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ── Header ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Create New Batch", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Set up a class batch with its monthly fee", color = TextMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Batch Name ──────────────────────────────────
            SectionLabel("Batch Name *")
            DarkTextField(
                value = name,
                onValueChange = { name = it; nameError = false },
                isError = nameError,
                supportingText = if (nameError) "Batch name is required" else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = "e.g. Class 10 Science"
            )

            Spacer(Modifier.height(16.dp))

            // ── Monthly Fee ─────────────────────────────────
            SectionLabel("Monthly Fee (BDT) *")
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkTextField(
                    value = feeString,
                    onValueChange = { feeString = it; feeError = false },
                    isError = feeError,
                    supportingText = if (feeError) "Amount must be greater than 0" else null,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = "e.g. 1500"
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBgAlt)
                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("BDT", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Description ─────────────────────────────────
            SectionLabel("Description (optional)")
            DarkTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                placeholder = "e.g. Evening batch, Monday through Friday"
            )

            Spacer(Modifier.height(28.dp))

            // ── Info card ───────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = SkyBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Batch ID will be auto-generated.\nStart date set to today. You can edit details later.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Save Button ─────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                    )
                    .clickable {
                        // Validate
                        nameError = name.isBlank()
                        val fee = feeString.toDoubleOrNull()
                        feeError = (fee == null || fee <= 0)

                        if (!nameError && !feeError && fee != null) {
                            viewModel.addBatch(
                                name = name.trim(),
                                feeAmount = fee,
                                description = description.trim().takeIf { it.isNotEmpty() }
                            ) {
                                // Show snackbar on success (Main thread)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Batch saved successfully")
                                }
                                onBack()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Batch", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        placeholder = { Text(placeholder, color = TextMuted.copy(alpha = 0.5f)) },
        supportingText = if (supportingText != null) {{ Text(supportingText, color = Color(0xFFEF4444), fontSize = 11.sp) }} else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = BorderSub,
            errorBorderColor = Color(0xFFEF4444),
            focusedContainerColor = CardBgAlt,
            unfocusedContainerColor = CardBgAlt,
            errorContainerColor = CardBgAlt,
            cursorColor = Cyan
        ),
        shape = RoundedCornerShape(12.dp)
    )
}
