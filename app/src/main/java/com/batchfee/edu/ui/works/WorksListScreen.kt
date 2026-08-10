package com.batchfee.edu.ui.works

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorksListScreen(db: AppDatabase, onBack: () -> Unit, onAddWork: () -> Unit) {
    val viewModel: WorksViewModel = viewModel(factory = WorksViewModelFactory(db))
    val works by viewModel.works.collectAsState()
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Homework & Assignments") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onAddWork) { Icon(Icons.Filled.Add, "Add") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddWork) {
                Icon(Icons.Filled.Add, "Add Work")
            }
        }
    ) { padding ->
        if (works.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No work assigned yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(works) { work ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (work.type == "HOMEWORK") Icons.Filled.Home else Icons.Filled.Assignment,
                                    null,
                                    tint = if (work.type == "HOMEWORK") Color(0xFF3B82F6) else Color(0xFFF59E0B),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (work.type == "HOMEWORK") "Homework" else "Assignment",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (work.type == "HOMEWORK") Color(0xFF3B82F6) else Color(0xFFF59E0B),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(work.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(work.description, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 3)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Created: ${df.format(Date(work.createdAtMs))}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                work.dueDateMs?.let { due ->
                                    Text("Due: ${df.format(Date(due))}", style = MaterialTheme.typography.labelSmall,
                                        color = if (due < System.currentTimeMillis()) Color(0xFFEF4444) else Color(0xFF34D399))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { viewModel.archiveWork(work) }) {
                                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = Color(0xFFEF4444))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remove", color = Color(0xFFEF4444), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
