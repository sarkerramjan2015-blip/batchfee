package com.example.ui.superadmin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SuperAdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institutes = MutableStateFlow<List<InstituteEntity>>(emptyList())
    val institutes = _institutes.asStateFlow()

    init {
        viewModelScope.launch {
            db.instituteDao().getAllInstitutes().collect { list ->
                _institutes.value = list
            }
        }
    }
}

class SuperAdminViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SuperAdminViewModel::class.java)) return SuperAdminViewModel(db) as T
        throw IllegalArgumentException()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminScreen(
    db: AppDatabase,
    onLogout: () -> Unit
) {
    val viewModel: SuperAdminViewModel = viewModel(factory = SuperAdminViewModelFactory(db))
    val institutes by viewModel.institutes.collectAsState()
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Super Admin Dashboard") },
                actions = {
                    IconButton(onClick = {
                        SessionManager.logout()
                        onLogout()
                    }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Registered Institutes", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(institutes) { inst ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(inst.name, style = MaterialTheme.typography.titleMedium)
                            Text("Status: ${inst.subscriptionStatus}", style = MaterialTheme.typography.bodyMedium)
                            Text("Plan ID: ${inst.currentPlanId}", style = MaterialTheme.typography.bodyMedium)
                            Text("Joined: ${dateFormat.format(Date(inst.createdAtMs))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
