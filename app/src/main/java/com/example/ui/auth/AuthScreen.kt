package com.example.ui.auth

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.data.models.UserEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.lifecycle.viewModelScope

class AuthViewModel(private val db: AppDatabase) : ViewModel() {
    
    fun registerInstitute(
        instituteName: String,
        ownerName: String,
        email: String,
        passwordHash: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (instituteName.isBlank() || ownerName.isBlank() || email.isBlank() || passwordHash.isBlank()) {
            onError("All fields are required")
            return
        }

        val instituteId = UUID.randomUUID().toString()
        val userId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        val institute = InstituteEntity(
            id = instituteId,
            name = instituteName,
            currentPlanId = "plan_pro",
            subscriptionStatus = "trial",
            trialStartDateMs = now,
            trialEndDateMs = now + thirtyDaysMs,
            currentPeriodEndMs = now + thirtyDaysMs,
            createdAtMs = now
        )

        val user = UserEntity(
            id = userId,
            instituteId = instituteId,
            name = ownerName,
            email = email,
            passwordHash = passwordHash,
            role = "InstituteOwner",
            createdAtMs = now
        )

        viewModelScope.launch {
            try {
                // Ensure initial plans are loaded before registration to avoid foreign key issues
                val existingPlans = db.subscriptionPlanDao().getAllPlans().first()
                if (existingPlans.isEmpty()) {
                     AppDatabase.ensureDemoDataSeeded(db)
                     val doubleCheckPlans = db.subscriptionPlanDao().getAllPlans().first()
                     if (doubleCheckPlans.isEmpty()) {
                         onError("Database initialization in progress. Please try again in a moment.")
                         return@launch
                     }
                }

                val existing = db.userDao().getUserByEmail(email)
                if (existing != null) {
                    onError("Email already exists")
                    return@launch
                }

                db.instituteDao().insertInstitute(institute)
                db.userDao().insertUser(user)
                
                SessionManager.login(userId, instituteId, user.role)
                onSuccess()
            } catch (e: Exception) {
                onError("Registration failed: ${e.message}")
            }
        }
    }

    fun login(
        email: String,
        passwordHash: String,
        onSuccess: (role: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (email.isBlank() || passwordHash.isBlank()) {
            onError("Email and password are required")
            return
        }
        
        viewModelScope.launch {
            try {
                var user = db.userDao().getUserByEmail(email)

                // ── Demo account fallback: trigger seeding if user not found ──
                if (user == null && (email == "owner@batchfee.app" || email == "admin@batchfee.app")) {
                    try {
                        AppDatabase.ensureDemoDataSeeded(db)
                    } catch (seedEx: Exception) {
                        // Seeding failed — still try direct query in case partial data exists
                        seedEx.printStackTrace()
                    }
                    user = db.userDao().getUserByEmail(email)
                }

                if (user == null) {
                    onError("Invalid credentials")
                    return@launch
                }

                if (user.passwordHash != passwordHash) {
                    onError("Invalid credentials")
                    return@launch
                }

                val instituteId = user.instituteId ?: ""
                if (instituteId.isEmpty() && user.role == "InstituteOwner") {
                    onError("Demo account not fully initialized. Please wait a moment and try again.")
                    return@launch
                }

                SessionManager.login(user.id, instituteId, user.role)
                onSuccess(user.role)
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Login failed. Please try again.")
            }
        }
    }
}

class AuthViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun AuthScreen(
    db: AppDatabase,
    onNavigateDashboard: () -> Unit,
    onNavigateSuperAdmin: () -> Unit
) {
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(db))
    var isLoginMode by remember { mutableStateOf(true) }
    
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var instituteName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingDemoAccount by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F0C29),
            Color(0xFF302B63),
            Color(0xFF24243E)
        )
    )
    
    var isVisible by remember { mutableStateOf(false) }
    var animatedTitleAlpha by remember { mutableStateOf(0f) }
    var animatedTaglineOffset by remember { mutableStateOf(20f) }

    LaunchedEffect(Unit) {
        isVisible = true
        animatedTitleAlpha = 1f
        animatedTaglineOffset = 0f
    }

    val titleAlpha by animateFloatAsState(targetValue = animatedTitleAlpha, animationSpec = tween(1200))
    val taglineOffset by animateFloatAsState(targetValue = animatedTaglineOffset, animationSpec = tween(1200, delayMillis = 200))

    // Styling Combinations
    val isStyleA = true // Set to false to switch to Combination B: Dark-Light Hybrid

    val titleColor = if (isStyleA) Color(0xFF1E1B4B) else Color(0xFF1F2937)
    val taglineColor = if (isStyleA) Color(0xFF4C1D95) else Color(0xFF6D28D9)
    val subtitleColor = if (isStyleA) Color(0xFF64748B) else Color(0xFF9CA3AF)
    
    val primaryButtonGradient = if (isStyleA) {
        listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
    } else {
        listOf(Color(0xFF8B5CF6), Color(0xFFD946EF))
    }
    
    val demoOwnerBgColor = if (isStyleA) Color(0xFFEEF2FF) else Color(0xFFE0F2FE)
    val demoOwnerTextColor = if (isStyleA) Color(0xFF4338CA) else Color(0xFF0284C7)
    
    val superAdminBgColor = if (isStyleA) Color(0xFFECFDF5) else Color(0xFFD1FAE5)
    val superAdminTextColor = if (isStyleA) Color(0xFF047857) else Color(0xFF059669)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        // Decorative Blurred Orbs
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-80).dp, y = (-80).dp)
                .size(250.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF6366F1).copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = 100.dp)
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFA855F7).copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )

        // Main content
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(800)) + slideInVertically(initialOffsetY = { 40 }, animationSpec = tween(800)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 48.dp)
                        .fillMaxWidth()
                        .shadow(16.dp, RoundedCornerShape(32.dp), spotColor = Color(0x33000000))
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.White.copy(alpha = 0.9f))
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(32.dp))
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo Box
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Color(0x666366F1))
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF6366F1), Color(0xFFA855F7))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.School,
                            contentDescription = "App Icon",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Text(
                        text = "BatchFee",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = if (isStyleA) FontWeight.Bold else FontWeight.ExtraBold
                        ),
                        color = titleColor,
                        modifier = Modifier.graphicsLayer(alpha = titleAlpha)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Smart institute management, simplified.",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = taglineColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer(translationY = taglineOffset, alpha = titleAlpha)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Manage students, batches, fees, attendance, and reports from one beautiful app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))

                    val textFieldColors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFFF3F4F6).copy(alpha = 0.6f),
                        focusedContainerColor = Color.White,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedLabelColor = Color(0xFF6B7280),
                        focusedLabelColor = Color(0xFF6366F1),
                        focusedTextColor = Color(0xFF111827),
                        unfocusedTextColor = Color(0xFF111827),
                        cursorColor = Color(0xFF6366F1)
                    )

                    if (!isLoginMode) {
                        OutlinedTextField(
                            value = instituteName,
                            onValueChange = { instituteName = it },
                            label = { Text("Institute Name") },
                            leadingIcon = { Icon(Icons.Filled.AccountBalance, contentDescription = null, tint = Color(0xFF9CA3AF)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ownerName,
                            onValueChange = { ownerName = it },
                            label = { Text("Your Name") },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFF9CA3AF)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email Address") },
                        leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = Color(0xFF9CA3AF)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = textFieldColors
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF9CA3AF)) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = Color(0xFF9CA3AF)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = textFieldColors
                    )
                    
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFEF2F2), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = errorMessage!!,
                                color = Color(0xFFDC2626),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = {
                            if (isLoading || loadingDemoAccount != null) return@Button
                            errorMessage = null
                            isLoading = true
                            if (isLoginMode) {
                                viewModel.login(email, password, onSuccess = { role ->
                                    isLoading = false
                                    if (role == "SuperAdmin") onNavigateSuperAdmin()
                                    else onNavigateDashboard()
                                }, onError = { 
                                    errorMessage = it
                                    isLoading = false 
                                })
                            } else {
                                viewModel.registerInstitute(
                                    instituteName, ownerName, email, password,
                                    onSuccess = {
                                        isLoading = false
                                        onNavigateDashboard()
                                    },
                                    onError = { 
                                        errorMessage = it
                                        isLoading = false 
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(primaryButtonGradient),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading && loadingDemoAccount == null) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = if (isLoginMode) "Login" else "Create Institute & Start Trial",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    TextButton(onClick = { 
                        isLoginMode = !isLoginMode 
                        errorMessage = null
                    }) {
                        Text(
                            text = if (isLoginMode) "Need an account? Register Institute" else "Already have an account? Login",
                            color = taglineColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE5E7EB))
                        Text(
                            text = "or try demo access",
                            color = subtitleColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE5E7EB))
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                if (isLoading || loadingDemoAccount != null) return@Button
                                errorMessage = null
                                loadingDemoAccount = "owner"
                                viewModel.login("owner@batchfee.app", "123456", onSuccess = { role ->
                                    loadingDemoAccount = null
                                    onNavigateDashboard()
                                }, onError = { 
                                    errorMessage = it
                                    loadingDemoAccount = null
                                })
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = demoOwnerBgColor,
                                contentColor = demoOwnerTextColor
                            ),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            if (loadingDemoAccount == "owner") {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = demoOwnerTextColor, strokeWidth = 2.dp)
                            } else {
                                Text("Enter Demo Owner Account", fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        Button(
                            onClick = {
                                if (isLoading || loadingDemoAccount != null) return@Button
                                errorMessage = null
                                loadingDemoAccount = "admin"
                                viewModel.login("admin@batchfee.app", "123456", onSuccess = { role ->
                                    loadingDemoAccount = null
                                    // Demo Login: navigate to Dashboard instead of SuperAdmin.
                                    onNavigateDashboard()
                                }, onError = { 
                                    errorMessage = it
                                    loadingDemoAccount = null
                                })
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = superAdminBgColor,
                                contentColor = superAdminTextColor
                            ),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            if (loadingDemoAccount == "admin") {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = superAdminTextColor, strokeWidth = 2.dp)
                            } else {
                                Text("Enter Super Admin Account", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Debug Info: isLoading=$isLoading, loadingDemoAccount=$loadingDemoAccount, error=${errorMessage ?: "null"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

