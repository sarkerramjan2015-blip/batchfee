package com.example.ui.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.domain.SessionManager

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val AccentRed     = Color(0xFFEF4444)
private val AccentAmber   = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalaryDashboardScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onNavigateToPricing: () -> Unit
) {
    val viewModel: SalaryViewModel = viewModel(factory = SalaryViewModelFactory(db))
    val salaries by viewModel.salaries.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Salary Management", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                FloatingActionButton(
                    onClick = onGenerate,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Generate", tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("${salaries.size} salaries", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (salaries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No salaries generated yet.", color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(salaries, key = { it.id }) { s ->
                        val isPaid = s.status == "paid"
                        val statusColor = if (isPaid) WAGreen else AccentAmber
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .shadow(3.dp, RoundedCornerShape(12.dp), spotColor = statusColor.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, BorderSub)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${s.salaryMonth}", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(statusColor.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(s.status.uppercase(), color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Net: BDT ${s.netSalary}", color = TextMuted, fontSize = 14.sp)
                                if (!isPaid && isAdmin) {
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                                            .clickable { viewModel.markAsPaid(s.id, "cash") },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Mark Paid", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateSalaryScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: SalaryViewModel = viewModel(factory = SalaryViewModelFactory(db))
    val activeStaff by viewModel.activeStaff.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }

    var selectedStaffId by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf("") }
    var basic by remember { mutableStateOf("0") }
    var bonus by remember { mutableStateOf("0") }
    var deduction by remember { mutableStateOf("0") }
    var advance by remember { mutableStateOf("0") }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Generate Salary", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (!isAdmin) {
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("Only admins can generate salaries.", color = TextMuted, fontSize = 14.sp)
                }
                return@Scaffold
            }

            SectionLabel("Select Staff Member")
            Spacer(Modifier.height(4.dp))
            LazyRow {
                items(activeStaff) { s ->
                    FilterChip(
                        selected = selectedStaffId == s.id,
                        onClick = { selectedStaffId = s.id; basic = s.monthlySalary.toString() },
                        label = { Text(s.fullName, fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                            selectedLabelColor = Cyan
                        )
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            SectionLabel("Month")
            OutlinedTextField(
                value = month,
                onValueChange = { month = it },
                placeholder = { Text("e.g. May 2026", color = TextMuted.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Basic Salary")
            OutlinedTextField(
                value = basic, onValueChange = { basic = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Bonus")
            OutlinedTextField(
                value = bonus, onValueChange = { bonus = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Deduction")
            OutlinedTextField(
                value = deduction, onValueChange = { deduction = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Advance")
            OutlinedTextField(
                value = advance, onValueChange = { advance = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(14.dp))
            val net = (basic.toDoubleOrNull() ?: 0.0) + (bonus.toDoubleOrNull() ?: 0.0) - (deduction.toDoubleOrNull() ?: 0.0) - (advance.toDoubleOrNull() ?: 0.0)
            Text("Net Salary: BDT ${net.toLong()}", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(16.dp))
            val canGenerate = selectedStaffId != null && month.isNotBlank() && net >= 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (canGenerate) Modifier.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                        else Modifier.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                    ),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        if (selectedStaffId != null && month.isNotBlank() && net >= 0) {
                            viewModel.generateSalary(selectedStaffId!!, month,
                                basic.toDoubleOrNull() ?: 0.0, bonus.toDoubleOrNull() ?: 0.0,
                                deduction.toDoubleOrNull() ?: 0.0, advance.toDoubleOrNull() ?: 0.0,
                                onSuccess = onBack)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = canGenerate,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canGenerate) Color.White else TextMuted)
                ) {
                    Text("Generate Salary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue, unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBgAlt, unfocusedContainerColor = CardBgAlt, cursorColor = Cyan,
    focusedLabelColor = Cyan, unfocusedLabelColor = TextMuted
)
