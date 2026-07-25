package com.batchfee.edu.ui.expenses

import android.app.DatePickerDialog
import android.widget.Toast
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
import com.batchfee.edu.ui.components.FeatureGuard
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

// â”€â”€ Theme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
    "Electricity" to CategoryMeta(Icons.Filled.Bolt, AccentAmber),
    "Internet" to CategoryMeta(Icons.Filled.Wifi, AccentGreen),
    "Staff Salary" to CategoryMeta(Icons.Filled.People, AccentViolet),
    "Marketing" to CategoryMeta(Icons.Filled.Campaign, Color(0xFFF472B6)),
    "Stationery" to CategoryMeta(Icons.Filled.MenuBook, AccentCyan),
    "Other" to CategoryMeta(Icons.Filled.ReceiptLong, TextMuted)
)
private val allCategories = listOf("Rent", "Electricity", "Internet", "Staff Salary", "Marketing", "Stationery", "Other")
private val paymentMethods = listOf("Cash", "bKash", "Nagad", "Bank Transfer")

private fun amountColor(amount: Double) = Color(0xFFFF6B6B)
private fun shortDateFormat(ms: Long): String = SimpleDateFormat("MMM dd, yyyy 'Â·' hh:mm a", Locale.getDefault()).format(Date(ms))
private fun dayDateFormat(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

// â”€â”€ Expense List Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(db: AppDatabase, onBack: () -> Unit, onAddExpense: () -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: ExpenseViewModel = viewModel(factory = ExpenseViewModelFactory(db))
    val expenses by viewModel.expenses.collectAsState()
    val summary by viewModel.summary.collectAsState()

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

            // â”€â”€ Section header â”€â”€
            item {
                Text(
                    "All Expenses Â· ${expenses.size} records",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // â”€â”€ List â”€â”€
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
                    ExpenseCard(expense)
                }
            }

            // â”€â”€ Summary cards (at bottom) â”€â”€
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BorderSub, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))
                Text("Summary", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard("Today", summary.todayExpense, AccentAmber, Icons.Filled.Today, Modifier.weight(1f))
                    SummaryCard("This Month", summary.monthExpense, AccentCyan, Icons.Filled.CalendarMonth, Modifier.weight(1f))
                }
            }
            item {
                SummaryLargeCard("Lifetime Expense", summary.lifetimeExpense, AccentRed, Icons.Filled.DateRange)
            }

            item { Spacer(Modifier.height(80.dp)) }
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
private fun ExpenseCard(expense: com.batchfee.edu.data.models.ExpenseEntity) {
    val meta = categoryMeta[expense.category] ?: categoryMeta["Other"]!!
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        Text(" Â· ", color = TextMuted, fontSize = 12.sp)
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

// â”€â”€ Add / Edit Expense Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditExpenseScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ExpenseViewModel = viewModel(factory = ExpenseViewModelFactory(db))
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }

    var paymentMethod by remember { mutableStateOf("") }
    var expenseDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current

    val dateStr = remember(expenseDateMs) { SimpleDateFormat("dd MMM, yyyy", Locale.getDefault()).format(Date(expenseDateMs)) }
    val isFormValid = title.isNotBlank() && category.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Add Expense", color = TextWhite, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
                navigationIcon = {
                    IconButton(onClick = onBack) {
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

            // â”€â”€ Amount â”€â”€
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

            // â”€â”€ Title â”€â”€
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

            // â”€â”€ Category â”€â”€
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

            // â”€â”€ Date picker â”€â”€
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

            // â”€â”€ Payment method â”€â”€
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

            // â”€â”€ Save â”€â”€
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val value = amount.toDoubleOrNull() ?: 0.0
                        if (value <= 0) {
                            Toast.makeText(context, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        viewModel.addExpense(title, category, value, expenseDateMs, paymentMethod, null,
                            onSuccess = {
                                Toast.makeText(context, "Expense saved!", Toast.LENGTH_SHORT).show()
                                onBack()
                            },
                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = isFormValid,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, disabledContainerColor = BorderSub)
                ) {
                    Text("Save Expense", color = if (isFormValid) Color.White else TextMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

