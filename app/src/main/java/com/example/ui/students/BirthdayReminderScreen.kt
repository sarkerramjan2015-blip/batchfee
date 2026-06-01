package com.example.ui.students

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayReminderScreen(db: AppDatabase, onBack: () -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: BirthdayViewModel = viewModel(factory = BirthdayViewModelFactory(db))
    val birthdays by viewModel.upcomingBirthdays.collectAsState()
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMMM dd", Locale.getDefault()) }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Birthday Reminders") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                if (birthdays.isEmpty()) {
                    Text("No upcoming birthdays in the next 30 days.", modifier = Modifier.padding(top = 16.dp))
                } else {
                    LazyColumn {
                        items(birthdays) { student ->
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(student.fullName, style = MaterialTheme.typography.titleMedium)
                                        Text(student.dateOfBirthMs?.let { dateFormat.format(Date(it)) } ?: "", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    IconButton(
                                        onClick = {
                                            val message = "Happy Birthday ${student.fullName}! \n\nBest Wishes."
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, message)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share Birthday Wish"))
                                        }
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share Wish")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
