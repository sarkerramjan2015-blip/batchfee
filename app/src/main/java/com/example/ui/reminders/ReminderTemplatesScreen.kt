package com.example.ui.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun ReminderTemplatesScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ReminderTemplateViewModel = viewModel(factory = ReminderTemplateViewModelFactory(db))
    val templates by viewModel.templates.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Reminders") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (templates.isEmpty()) {
                Text("No templates found. Automated reminders will appear here when configured.")
            } else {
                LazyColumn {
                    items(templates) { t ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(t.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(t.messageTemplate, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
