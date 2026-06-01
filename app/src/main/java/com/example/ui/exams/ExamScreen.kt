package com.example.ui.exams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.ui.components.FeatureGuard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamListScreen(db: AppDatabase, onBack: () -> Unit, onAddExam: () -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: ExamViewModel = viewModel(factory = ExamViewModelFactory(db))
    val exams by viewModel.exams.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exams & Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddExam) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (exams.isEmpty()) {
                Text("No exams scheduled.", modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyColumn {
                    items(exams) { e ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(e.examName, style = MaterialTheme.typography.titleMedium)
                                Text("Total Marks: ${e.totalMarks}", style = MaterialTheme.typography.bodyMedium)
                                Text("Passing Marks: ${e.passingMarks}", style = MaterialTheme.typography.bodyMedium)
                                Text("Status: ${e.status}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExamScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ExamViewModel = viewModel(factory = ExamViewModelFactory(db))
    val batches by viewModel.batches.collectAsState()

    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var examName by remember { mutableStateOf("") }
    var totalMarks by remember { mutableStateOf("") }
    var passingMarks by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Exam") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Select Batch")
            LazyRow(modifier = Modifier.fillMaxWidth()) {
                items(batches) { b ->
                    FilterChip(
                        selected = selectedBatchId == b.id,
                        onClick = { selectedBatchId = b.id },
                        label = { Text(b.name) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = examName,
                onValueChange = { examName = it },
                label = { Text("Exam Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = totalMarks,
                onValueChange = { totalMarks = it },
                label = { Text("Total Marks") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passingMarks,
                onValueChange = { passingMarks = it },
                label = { Text("Passing Marks") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (selectedBatchId != null) {
                        viewModel.createExam(
                            batchId = selectedBatchId!!,
                            examName = examName,
                            totalMarks = totalMarks.toDoubleOrNull() ?: 100.0,
                            passingMarks = passingMarks.toDoubleOrNull() ?: 40.0,
                            onSuccess = onBack
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedBatchId != null && examName.isNotBlank() && totalMarks.isNotBlank() && passingMarks.isNotBlank()
            ) {
                Text("Create Exam")
            }
        }
    }
}
