package com.example.ui.students

import androidx.compose.foundation.background
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
import com.example.ui.components.FeatureGuard
import com.example.domain.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardGeneratorScreen(db: AppDatabase, onBack: () -> Unit, onNavigateToPreview: (String, String) -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: com.example.ui.students.StudentViewModel = viewModel(factory = com.example.ui.students.StudentViewModelFactory(db))
    val students by viewModel.studentList.collectAsState()

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("ID Card Generator") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Text("Select Student for ID Card", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                LazyColumn {
                    items(students) { student ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            onClick = { onNavigateToPreview("student", student.id) }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(student.fullName, style = MaterialTheme.typography.titleMedium)
                                Text(student.studentCode, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardPreviewScreen(db: AppDatabase, type: String, id: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ID Card Preview") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("Institute Name", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.size(100.dp).background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.height(16.dp))
                    Text("Identity Card", style = MaterialTheme.typography.titleLarge)
                    Text("Type: $type", style = MaterialTheme.typography.bodyLarge)
                    Text("ID: $id", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { /* TODO Export as PDF/Image */ }) {
                        Text("Download (Placeholder)")
                    }
                }
            }
        }
    }
}
