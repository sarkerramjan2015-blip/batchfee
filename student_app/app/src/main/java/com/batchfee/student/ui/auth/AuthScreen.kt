package com.batchfee.student.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Auth screen placeholder — not used in demo mode.
 * Will be implemented when Firebase auth is added back.
 */
@Composable
fun AuthScreen(onLoginSuccess: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Auth screen — not available in demo mode")
    }
}
