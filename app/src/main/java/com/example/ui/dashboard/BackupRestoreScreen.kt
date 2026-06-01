package com.example.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onBack: () -> Unit, onNavigateToPricing: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Local Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { /* Placeholder */ }, modifier = Modifier.fillMaxWidth()) {
                Text("Export Database Summary (JSON)")
            }
            Spacer(Modifier.height(16.dp))
            
            Text("Restore Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { /* Placeholder */ }, modifier = Modifier.fillMaxWidth(), enabled = false) {
                Text("Import Backup File")
            }
            Text("Note: Import will overwrite all current data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            
            Spacer(Modifier.height(32.dp))
            Text("Cloud Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Automatic cloud synchronization will be available in a future update.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
