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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ReportsViewModel = viewModel(factory = ReportsViewModelFactory(db))
    val students by viewModel.studentCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Reports") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Institute Reports", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Enrolled Students: $students", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("More detailed analytics and PDF exports are coming soon.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
