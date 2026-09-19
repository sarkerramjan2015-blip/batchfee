package com.batchfee.edu.ui.expenses

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.ExpenseEntity
import com.batchfee.edu.ui.components.FeatureGuard
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

// ── Theme ────────────────────────────────────────────────────
private val BgColor = Color(0xFF0F0F14)
private val CardBg = Color(0xFF1A1A24)
private val BorderSub = Color(0xFF2A2A38)
private val TextWhite = Color(0xFFF0F0F0)
private val TextMuted = Color(0xFF8888A0)
private val AccentGreen = Color(0xFF4ADE80)
private val AccentRed = Color(0xFFF87171)
private val AccentAmber = Color(0xFFFBBF24)
private val AccentCyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val SurfaceSub = Color(0xFF14141E)
private val AccentViolet = Color(0xFFA855F7)

private data class CategoryMeta(val icon: ImageVector, val color: Color)
private val categoryMeta = mapOf(
    "Rent" to CategoryMeta(Icons.Filled.MeetingRoom, Color(0xFF60A5FA)),
    "Electricity" to CategoryMeta(Icons.Filled.Bolt, ElectricBlue),
    "Internet" to CategoryMeta(Icons.Filled.Wifi, AccentGreen),
    "Staff Salary" to CategoryMeta(Icons.Filled.People, AccentViolet),
    "Marketing" to CategoryMeta(Icons.Filled.Campaign, Color(0xFFF472B6)),
    "Stationery" to CategoryMeta(Icons.Filled.MenuBook, AccentCyan),
    "Other" to CategoryMeta(Icons.Filled.ReceiptLong, TextMuted)
)
private val allCategories = listOf("Rent", "Electricity", "Internet", "Staff Salary", "Marketing", "Stationery", "Other")
private val paymentMethods = listOf("Cash", "bKash", "Nagad", "Bank Transfer")

private fun amountColor(amount: Double) = Color(0xFFFF6B6B)
private fun shortDateFormat(ms: Long): String = SimpleDateFormat("MMM dd, yyyy '·' hh:mm a", Locale.getDefault()).format(Date(ms))
private fun dayDateFormat(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

// ── Expense List Screen ──────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onEditExpense: (String) -> Unit,
    onNavigateToPricing: () -> Unit
) {
    val viewModel: ExpenseViewModel = viewModel(factory = ExpenseViewModelFactory(db))
    val expenses by viewModel.expenses.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val context = LocalContext.current
    var actionExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Expenses", color = TextWhite, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                actions = {
                    if (expenses.isNotEmpty()) {
                        IconButton(onClick = onNavigateToPricing) {
                            Icon(Icons.Filled.Analytics, "Profit & Loss", tint = AccentCyan)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddExpense,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(ElectricBlue, AccentCyan)))
            ) { Icon(Icons.Filled.Add, "Add Expense", tint = Color.White) }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Section header ──
            item {
                Text(
                    "All Expenses · ${expenses.size} records",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // ── List ──
            if (expenses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.ReceiptLong, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No expenses recorded yet.", color = TextMuted, fontSize = 14.sp)
                            Text("Tap + to add your first expense.", color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(expenses, key = { it.id }) { expense ->
                    ExpenseCard(expense, onClick = { actionExpense = expense })
                }
            }

            // ── Summary cards (at bottom) ──
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BorderSub, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))
                Text("Summary", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard("Today", summary.todayExpense, AccentCyan, Icons.Filled.Today, Modifier.weight(1f))
                    SummaryCard("This Month", summary.monthExpense, AccentCyan, Icons.Filled.CalendarMonth, Modifier.weight(1f))
                }
            }
            item {
                SummaryLargeCard("Lifetime Expense", summary.lifetimeExpense, AccentRed, Icons.Filled.DateRange)
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Tap a card to edit or delete that expense.
    actionExpense?.let { expense ->
        val category = categoryMeta[expense.category] ?: categoryMeta.getValue("Other")
        AlertDialog(
            onDismissRequest = { if (!deleting) actionExpense = null },
            containerColor = CardBg,
            tonalElevation = 8.dp,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(category.color.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(category.icon, null, tint = category.color, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Expense details", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            expense.title,
                            color = TextWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        color = AccentRed.copy(alpha = 0.09f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.22f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                            Text("AMOUNT", color = AccentRed.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text("BDT ${"%,.0f".format(expense.amount)}", color = TextWhite, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailPill(Icons.Filled.Category, expense.category, category.color, Modifier.weight(1f))
                        DetailPill(Icons.Filled.CalendarMonth, dayDateFormat(expense.expenseDateMs), AccentCyan, Modifier.weight(1f))
                    }
                    expense.paymentMethod?.takeIf { it.isNotBlank() }?.let { method ->
                        DetailPill(Icons.Filled.AccountBalanceWallet, method, AccentGreen, Modifier.fillMaxWidth())
                    }
                    expense.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(description, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            actionExpense = null
                            onEditExpense(expense.id)
                        },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Edit expense", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.62f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Delete expense", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { actionExpense = null },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = TextMuted, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {}
        )
    }

    if (showDeleteConfirm && actionExpense != null) {
        val expense = actionExpense!!
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirm = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp),
            title = { Text("Delete Expense?", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will permanently remove \"${expense.title}\" (BDT ${"%,.0f".format(expense.amount)}) from this institute on all devices. This cannot be undone.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (deleting) return@Button
                        deleting = true
                        viewModel.deleteExpense(
                            expense,
                            onSuccess = {
                                deleting = false
                                showDeleteConfirm = false
                                actionExpense = null
                                Toast.makeText(context, "Expense deleted", Toast.LENGTH_SHORT).show()
                            },
                            onError = { msg ->
                                deleting = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    enabled = !deleting,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    if (deleting) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }, enabled = !deleting) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun DetailPill(
    icon: ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = SurfaceSub,
        border = BorderStroke(1.dp, BorderSub),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SummaryCard(label: String, amount: Double, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("BDT ${"%,.0f".format(amount)}", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SummaryLargeCard(label: String, amount: Double, color: Color, icon: ImageVector) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, color = TextMuted, fontSize = 12.sp)
                Text("BDT ${"%,.0f".format(amount)}", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExpenseCard(expense: ExpenseEntity, onClick: () -> Unit) {
    val meta = categoryMeta[expense.category] ?: categoryMeta["Other"]!!
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(meta.color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(meta.icon, null, tint = meta.color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(expense.title, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(expense.category, color = TextMuted, fontSize = 12.sp)
                    if (expense.paymentMethod != null) {
                        Text(" · ", color = TextMuted, fontSize = 12.sp)
                        Text(expense.paymentMethod, color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("BDT ${"%,.0f".format(expense.amount)}", color = amountColor(expense.amount), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(dayDateFormat(expense.expenseDateMs), color = TextMuted, fontSize = 11.sp)
            }
        }
        if (expense.description != null) {
            Text(
                expense.description, color = TextMuted.copy(alpha = 0.7f), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 68.dp, end = 14.dp, bottom = 12.dp)
            )
        }
    }
}

// ── Add / Edit Expense Screen ─────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditExpenseScreen(db: AppDatabase, expenseId: String?, onBack: () -> Unit) {
    val viewModel: ExpenseViewModel = viewModel(factory = ExpenseViewModelFactory(db))
    val isEdit = expenseId != null
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(isEdit) }
    val pendingExpenseId = remember { UUID.randomUUID().toString() }

    var paymentMethod by remember { mutableStateOf("") }
    var expenseDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current

    // Prefill the form when editing an existing expense.
    LaunchedEffect(expenseId) {
        if (expenseId == null) return@LaunchedEffect
        val existing = viewModel.getExpense(expenseId)
        if (existing == null) {
            Toast.makeText(context, "Expense not found.", Toast.LENGTH_SHORT).show()
            onBack()
            return@LaunchedEffect
        }
        title = existing.title
        category = existing.category
        amount = if (existing.amount % 1.0 == 0.0) existing.amount.toLong().toString() else "%.2f".format(existing.amount)
        paymentMethod = existing.paymentMethod.orEmpty()
        expenseDateMs = existing.expenseDateMs
        description = existing.description.orEmpty()
        isLoading = false
    }

    val dateStr = remember(expenseDateMs) { SimpleDateFormat("dd MMM, yyyy", Locale.getDefault()).format(Date(expenseDateMs)) }
    val isFormValid = title.isNotBlank() && category.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0

    BackHandler(enabled = isSaving || isLoading) { }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit Expense" else "Add Expense", color = TextWhite, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving && !isLoading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Amount ──
            item {
                FieldLabel("Amount (BDT)")
                OutlinedTextField(
                    value = amount, onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal.matches(Regex("^\\d*\\.?\\d{0,2}$"))) amount = newVal
                    },
                    placeholder = { Text("0.00", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            // ── Title ──
            item {
                FieldLabel("Expense Title")
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    placeholder = { Text("e.g. Office Rent Jan", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
            }

            // ── Category ──
            item {
                FieldLabel("Category")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allCategories) { cat ->
                        val meta = categoryMeta[cat] ?: categoryMeta["Other"]!!
                        val selected = category == cat
                        FilterChip(
                            selected = selected,
                            onClick = { category = cat },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(meta.icon, null, tint = if (selected) Color.White else meta.color, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(cat, color = if (selected) Color.White else TextWhite)
                                }
                            },
                            leadingIcon = null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = meta.color.copy(alpha = 0.25f),
                                containerColor = CardBg
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = if (selected) meta.color else BorderSub,
                                selectedBorderColor = meta.color,
                                enabled = true, selected = selected
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            // ── Date picker ──
            item {
                FieldLabel("Date")
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val cal = Calendar.getInstance(); cal.timeInMillis = expenseDateMs
                        DatePickerDialog(context, { _, y, m, d ->
                            val c = Calendar.getInstance(); c.set(y, m, d); expenseDateMs = c.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarToday, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(dateStr, color = TextWhite, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted)
                    }
                }
            }

            // ── Payment method ──
            item {
                FieldLabel("Payment Method")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paymentMethods) { pm ->
                        val selected = paymentMethod == pm
                        FilterChip(
                            selected = selected,
                            onClick = { paymentMethod = if (selected) "" else pm },
                            label = { Text(pm, color = if (selected) Color.White else TextWhite) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricBlue.copy(alpha = 0.3f), containerColor = CardBg),
                            border = FilterChipDefaults.filterChipBorder(borderColor = if (selected) ElectricBlue else BorderSub, selectedBorderColor = ElectricBlue, enabled = true, selected = selected),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            // ── Note ──
            item {
                FieldLabel("Note (optional)")
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    placeholder = { Text("Any extra detail about this expense", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    minLines = 2
                )
            }

            // ── Save ──
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isSaving) return@Button
                        val value = amount.toDoubleOrNull() ?: 0.0
                        if (value <= 0) {
                            Toast.makeText(context, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSaving = true
                        val onSaved = {
                            Toast.makeText(
                                context,
                                if (isEdit) "Expense updated!" else "Expense saved!",
                                Toast.LENGTH_SHORT
                            ).show()
                            onBack()
                        }
                        if (isEdit) {
                            viewModel.updateExpense(
                                expenseId!!, title, category, value, expenseDateMs, paymentMethod, description,
                                onSuccess = onSaved,
                                onError = { msg ->
                                    isSaving = false
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            viewModel.addExpense(title, category, value, expenseDateMs, paymentMethod, description,
                                expenseId = pendingExpenseId,
                                onSuccess = onSaved,
                                onError = { msg ->
                                    isSaving = false
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = isFormValid && !isSaving && !isLoading,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, disabledContainerColor = BorderSub)
                ) {
                    if (isSaving || isLoading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            isSaving -> "Saving..."
                            isLoading -> "Loading..."
                            isEdit -> "Save Changes"
                            else -> "Save Expense"
                        },
                        color = if (isFormValid) Color.White else TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
    focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderSub,
    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
    cursorColor = AccentCyan
)

