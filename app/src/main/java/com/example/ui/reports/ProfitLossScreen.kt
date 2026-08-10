package com.batchfee.edu.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

private val PLBg       = Color(0xFF07111F)
private val PLCard     = Color(0xFF0F172A)
private val PLCardAlt  = Color(0xFF111827)
private val PLStroke   = Color(0xFF1E293B)
private val PLCyan     = Color(0xFF22D3EE)
private val PLBlue     = Color(0xFF3B82F6)
private val PLGreen    = Color(0xFF22C55E)
private val PLRed      = Color(0xFFEF4444)
private val PLAmber    = Color(0xFFF59E0B)
private val PLWhite    = Color(0xFFF8FAFC)
private val PLMuted    = Color(0xFF94A3B8)
private val PLDim      = Color(0xFF64748B)

private fun fmt(n: Double) = "%,.0f".format(n)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfitLossScreen(db: AppDatabase, onBack: () -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: ProfitLossViewModel = viewModel(factory = ProfitLossViewModelFactory(db))
    val income by viewModel.totalIncome.collectAsState()
    val expense by viewModel.totalExpense.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val net = income - expense
    val margin = if (income > 0) ((net / income) * 100).coerceAtLeast(0.0) else 0.0

    Scaffold(
        containerColor = PLBg,
        topBar = {
            TopAppBar(
                title = { Text("Profit & Loss", color = PLWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PLMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PLBg)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PLCyan, strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Calculating...", color = PLMuted, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).background(PLBg),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Net Profit / Loss hero card
                item {
                    val isProfit = net >= 0
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isProfit) PLGreen.copy(alpha = 0.12f) else PLRed.copy(alpha = 0.12f)),
                        border = BorderStroke(1.5.dp, if (isProfit) PLGreen.copy(alpha = 0.4f) else PLRed.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isProfit) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown, null,
                                    tint = if (isProfit) PLGreen else PLRed, modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isProfit) "NET PROFIT" else "NET LOSS",
                                    color = if (isProfit) PLGreen else PLRed,
                                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("BDT ${fmt(net)}", color = PLWhite, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp)
                            Spacer(Modifier.height(4.dp))
                            if (income > 0) {
                                Text("${"%.1f".format(margin)}% margin", color = if (isProfit) PLGreen else PLRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Income vs Expense dual cards
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard("Total Income", "BDT ${fmt(income)}", Icons.Filled.ArrowDownward, PLGreen, Modifier.weight(1f))
                        MetricCard("Total Expenses", "BDT ${fmt(expense)}", Icons.Filled.ArrowUpward, PLRed, Modifier.weight(1f))
                    }
                }

                // Visual bar
                item {
                    val maxVal = maxOf(income, expense, 1.0)
                    val incomePct = (income / maxVal).toFloat()
                    val expensePct = (expense / maxVal).toFloat()
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = PLCard), border = BorderStroke(1.dp, PLStroke)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Income vs Expenses", color = PLWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Income", color = PLMuted, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                                Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(6.dp)).background(PLStroke)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(incomePct).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(PLGreen, PLCyan))))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(fmt(income), color = PLWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Expenses", color = PLMuted, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                                Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(6.dp)).background(PLStroke)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(expensePct).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(PLRed, PLAmber))))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(fmt(expense), color = PLWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Quick actions
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip("Add Income", Icons.Filled.Add, PLGreen, Modifier.weight(1f)) { /* navigate to collection */ }
                        ActionChip("Add Expense", Icons.Filled.Remove, PLRed, Modifier.weight(1f)) { /* navigate to add expense */ }
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier) =
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = PLCard), border = BorderStroke(1.dp, PLStroke)) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = PLMuted, fontSize = 12.sp)
            Text(value, color = PLWhite, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
    }

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) =
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
