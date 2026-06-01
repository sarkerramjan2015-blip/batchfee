package com.example.ui.fees

import androidx.compose.foundation.layout.*
// Removed dead LazyColumn/items imports — no lazy composable was actually used.
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeeDashboardScreen(db: AppDatabase, onBack: () -> Unit, onNavigateDueFees: () -> Unit, onCreateFee: () -> Unit) {
    val viewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    val totalCollected by viewModel.totalCollected.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fee Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // FIX: Added verticalScroll so the column content is scrollable.
        // Previously had no scroll at all; also removed unused LazyColumn/items imports.
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Total Collected", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("BDT $totalCollected", style = MaterialTheme.typography.headlineLarge)
                }
            }
            
            Button(onClick = onCreateFee, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Create Fee")
            }
            
            Button(onClick = onNavigateDueFees, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("View Due Fees")
            }
            // More quick actions...
        }
    }
}
