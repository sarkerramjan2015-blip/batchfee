package com.example.ui.reports

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.ui.components.FeatureGuard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfitLossScreen(db: AppDatabase, onBack: () -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: ProfitLossViewModel = viewModel(factory = ProfitLossViewModelFactory(db))
    val income by viewModel.totalIncome.collectAsState()
    val expense by viewModel.totalExpense.collectAsState()

    val net = income - expense

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Profit & Loss Summary") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total Income (Fees)", style = MaterialTheme.typography.titleMedium)
                        Text("BDT $income", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total Expenses", style = MaterialTheme.typography.titleMedium)
                        Text("BDT $expense", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (net >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(if (net >= 0) "Net Profit" else "Net Loss", style = MaterialTheme.typography.titleLarge)
                        Text("BDT $net", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
}
