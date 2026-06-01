package com.example.ui.billing

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.example.data.models.SubscriptionPlanEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillingViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institute = MutableStateFlow<InstituteEntity?>(null)
    val institute = _institute.asStateFlow()

    private val _currentPlan = MutableStateFlow<SubscriptionPlanEntity?>(null)
    val currentPlan = _currentPlan.asStateFlow()

    init {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.instituteDao().getInstituteFlow(instId).collect { inst ->
                _institute.value = inst
                if (inst != null) {
                    _currentPlan.value = db.subscriptionPlanDao().getPlanById(inst.currentPlanId)
                }
            }
        }
    }
}

class BillingViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BillingViewModel::class.java)) return BillingViewModel(db) as T
        throw IllegalArgumentException()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onUpgrade: () -> Unit
) {
    val viewModel: BillingViewModel = viewModel(factory = BillingViewModelFactory(db))
    val institute by viewModel.institute.collectAsState()
    val plan by viewModel.currentPlan.collectAsState()
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Billing & Subscription") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            Text("Current Plan Details", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Plan: ${plan?.name ?: "Loading..."}", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Status: ${institute?.subscriptionStatus?.uppercase()}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (institute?.subscriptionStatus == "trial") {
                        Text("Trial Ends: ${institute?.let { dateFormat.format(Date(it.trialEndDateMs)) }}")
                    } else {
                        Text("Next Billing Date: ${institute?.let { dateFormat.format(Date(it.currentPeriodEndMs)) }}")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                Text("View Upgrade Options")
            }
        }
    }
}
