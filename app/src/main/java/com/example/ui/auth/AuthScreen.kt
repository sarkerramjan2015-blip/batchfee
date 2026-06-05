package com.example.ui.auth

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.data.models.UserEntity
import com.example.domain.BiometricAuthManager
import com.example.domain.DemoAuthRepository
import com.example.domain.PasswordHasher
import com.example.domain.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope

class AuthViewModel(private val db: AppDatabase) : ViewModel() {
    
    fun trackDemoLogin(accountType: String) {
        viewModelScope.launch {
            DemoAuthRepository.trackDemoLogin(accountType)
        }
    }

    private suspend fun tryFirebaseLogin(email: String, password: String): UserEntity? {
        return withContext(Dispatchers.IO) {
            try {
                val authResult = FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(email, password)
                    .await()
                val uid = authResult.user?.uid ?: return@withContext null

                val firestoreUser = FirebaseFirestore.getInstance()
                    .collection("Institutes").document(uid).get().await()

                val existingPlans = db.subscriptionPlanDao().getAllPlans().first()
                if (existingPlans.isEmpty()) {
                    AppDatabase.ensureDemoDataSeeded(db)
                }

                if (firestoreUser.exists()) {
                    val data = firestoreUser.data ?: return@withContext null
                    val instituteName = data["instituteName"] as? String ?: "Institute"
                    val ownerName = data["ownerName"] as? String ?: instituteName
                    val role = data["role"] as? String ?: "InstituteOwner"
                    val createdAt = data["createdAt"] as? Long ?: System.currentTimeMillis()

                    val institute = InstituteEntity(
                        id = uid, name = instituteName,
                        currentPlanId = "plan_free_trial",
                        subscriptionStatus = "trial",
                        trialStartDateMs = createdAt,
                        trialEndDateMs = createdAt + (15L * 24 * 60 * 60 * 1000),
                        currentPeriodEndMs = createdAt + (15L * 24 * 60 * 60 * 1000),
                        createdAtMs = createdAt
                    )

                    val mappedRole = when (role) {
                        "owner" -> "InstituteOwner"
                        "superAdmin", "super_admin" -> "SuperAdmin"
                        else -> role
                    }

                    val user = UserEntity(
                        id = uid, instituteId = uid, name = ownerName,
                        email = email, passwordHash = PasswordHasher.hash(password),
                        role = mappedRole, createdAtMs = createdAt
                    )

                    db.instituteDao().insertInstitute(institute)
                    db.userDao().insertUser(user)
                    return@withContext user
                }

                // Firestore document missing — account exists in Auth but was never
                // fully provisioned. Return null to avoid privilege escalation.
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun registerInstitute(
        instituteName: String,
        ownerName: String,
        email: String,
        password: String,
        whatsappNumber: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (instituteName.isBlank() || ownerName.isBlank() || email.isBlank() || password.isBlank()) {
            onError("All fields are required")
            return
        }

        viewModelScope.launch {
            try {
                val existing = db.userDao().getUserByEmail(email)
                if (existing != null) {
                    onError("Email already exists")
                    return@launch
                }

                val authResult = withContext(Dispatchers.IO) {
                    FirebaseAuth.getInstance()
                        .createUserWithEmailAndPassword(email, password)
                        .await()
                }

                val uid = authResult.user?.uid
                    ?: throw IllegalStateException("Firebase Auth succeeded but returned null UID")

                val now = System.currentTimeMillis()
                val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000

                withContext(Dispatchers.IO) {
                    val firestore = FirebaseFirestore.getInstance()
                    firestore.collection("Institutes").document(uid).set(
                        mapOf(
                            "instituteName" to instituteName,
                            "ownerName" to ownerName,
                            "email" to email,
                            "whatsappNumber" to whatsappNumber,
                            "role" to "owner",
                            "createdAt" to now,
                            "isActive" to true,
                            "trialEndDate" to (now + fifteenDaysMs)
                        )
                    ).await()
                }

                val institute = InstituteEntity(
                    id = uid,
                    name = instituteName,
                    currentPlanId = "plan_free_trial",
                    subscriptionStatus = "trial",
                    trialStartDateMs = now,
                    trialEndDateMs = now + fifteenDaysMs,
                    currentPeriodEndMs = now + fifteenDaysMs,
                    createdAtMs = now,
                    whatsappNumber = whatsappNumber.ifBlank { null }
                )

                val user = UserEntity(
                    id = uid,
                    instituteId = uid,
                    name = ownerName,
                    email = email,
                    passwordHash = PasswordHasher.hash(password),
                    role = "InstituteOwner",
                    createdAtMs = now
                )

                val existingPlans = db.subscriptionPlanDao().getAllPlans().first()
                if (existingPlans.isEmpty()) {
                    AppDatabase.ensureDemoDataSeeded(db)
                }

                db.instituteDao().insertInstitute(institute)
                db.userDao().insertUser(user)

                SessionManager.login(uid, uid, user.role)
                onSuccess()
            } catch (e: FirebaseAuthException) {
                val message = when (e.errorCode) {
                    "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists"
                    "ERROR_INVALID_EMAIL" -> "Please enter a valid email address"
                    "ERROR_WEAK_PASSWORD" -> "Password should be at least 6 characters"
                    else -> e.localizedMessage ?: "Authentication failed"
                }
                onError(message)
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Registration failed")
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
            onError("Login ID and password are required")
            return
        }
        
        viewModelScope.launch {
            try {
                val loginId = email.trim()
                var user = db.userDao().getUserByEmail(loginId)

                if (user == null) {
                    user = tryFirebaseLogin(loginId, passwordHash)
                }

                if (user == null && (loginId == "owner@batchfee.app" || loginId == "admin@batchfee.app" || loginId == "STF001")) {
                    try {
                        AppDatabase.ensureDemoDataSeeded(db)
                    } catch (seedEx: Exception) {
                        seedEx.printStackTrace()
                    }
                    user = db.userDao().getUserByEmail(loginId)
                }

                if (user == null) {
                    onError("Invalid credentials")
                    return@launch
                }

                // ── Password verification with backward compatibility ──
                val storedHash = user.passwordHash
                val passwordValid = if (PasswordHasher.isHashed(storedHash)) {
                    PasswordHasher.verify(passwordHash, storedHash)
                } else {
                    // Legacy plain-text password — auto-upgrade to hashed
                    if (storedHash == passwordHash) {
                        db.userDao().updateUser(user.copy(passwordHash = PasswordHasher.hash(passwordHash)))
                        true
                    } else {
                        false
                    }
                }

                if (!passwordValid) {
                    onError("Invalid credentials")
                    return@launch
                }

                val instituteId = user.instituteId ?: ""
                if (instituteId.isEmpty() && user.role != "SuperAdmin") {
                    onError("Demo account not fully initialized. Please wait a moment and try again.")
                    return@launch
                }

                val staffPermissions = if (user.role == "Staff") {
                    val staff = db.staffDao().getStaffByIdOnce(user.id, instituteId)
                    when {
                        staff == null -> {
                            onError("Staff profile was not found. Contact your admin.")
                            return@launch
                        }
                        staff.archivedAtMs != null || staff.status != "active" -> {
                            onError("This staff account is inactive. Contact your admin.")
                            return@launch
                        }
                        else -> staff.permissions
                    }
                } else {
                    null
                }

                SessionManager.login(user.id, instituteId, user.role, staffPermissions)
                onSuccess(user.role)
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Login failed. Please try again.")
            }
        }
    }

    fun loginWithBiometric(
        context: Context,
        onSuccess: (role: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val saved = BiometricAuthManager.savedSession(appContext)
        if (saved == null) {
            onError("Biometric login is not enabled.")
            return
        }

        val availability = BiometricAuthManager.availabilityMessage(appContext)
        if (availability != null) {
            onError(availability)
            return
        }

        viewModelScope.launch {
            try {
                val user = db.userDao().getUserFlow(saved.userId).first()
                if (user == null) {
                    BiometricAuthManager.disable(appContext)
                    onError("Saved biometric user was not found. Log in with password again.")
                    return@launch
                }

                val instituteId = user.instituteId ?: ""
                if (instituteId.isEmpty() && user.role != "SuperAdmin") {
                    BiometricAuthManager.disable(appContext)
                    onError("Saved biometric account is incomplete. Log in with password again.")
                    return@launch
                }

                val staffPermissions = if (user.role == "Staff") {
                    val staff = db.staffDao().getStaffByIdOnce(user.id, instituteId)
                    when {
                        staff == null -> {
                            BiometricAuthManager.disable(appContext)
                            onError("Saved staff profile was not found. Log in with password again.")
                            return@launch
                        }
                        staff.archivedAtMs != null || staff.status != "active" -> {
                            BiometricAuthManager.disable(appContext)
                            onError("This staff account is inactive. Contact your admin.")
                            return@launch
                        }
                        else -> staff.permissions
                    }
                } else {
                    null
                }

                SessionManager.login(user.id, instituteId, user.role, staffPermissions)
                BiometricAuthManager.refreshCurrentSession(appContext, user.email)
                onSuccess(user.role)
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Biometric login failed. Try password login.")
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

// ── Dark premium palette shared with FeeDashboard ──────────
private val AuthBg       = Color(0xFF0F0C29)
private val AuthBgMid    = Color(0xFF302B63)
private val AuthBgEnd    = Color(0xFF24243E)
private val AuthCardBg   = Color(0xFF0F172A)
private val AuthCardAlt  = Color(0xFF111827)
private val AuthBorder   = Color(0xFF1E293B)
private val AuthCyan     = Color(0xFF22D3EE)
private val AuthBlue     = Color(0xFF3B82F6)
private val AuthViolet   = Color(0xFFA855F7)
private val AuthWhite    = Color(0xFFF8FAFC)
private val AuthMuted    = Color(0xFF94A3B8)
private val AuthErrorBg  = Color(0x33EF4444)

// ── Animated, floating logo composable ──────────────────────
@Composable
private fun AnimatedLogo(modifier: Modifier = Modifier) {
    var startAnim by remember { mutableStateOf(false) }

    val fadeAlpha by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0f,
        animationSpec = tween(800, easing = FastOutSlowInEasing)
    )
    val scale by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0.3f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    val floatOffset by rememberInfiniteTransition().animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(Unit) { startAnim = true }

    Box(
        modifier = modifier
            .size(100.dp)
            .graphicsLayer {
                alpha = fadeAlpha
                scaleX = scale
                scaleY = scale
                translationY = floatOffset * density
            }
            .shadow(20.dp, RoundedCornerShape(24.dp), spotColor = AuthCyan.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "BatchFee Logo",
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Glass card for input fields ─────────────────────────────
@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = AuthCyan.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AuthCardBg.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, AuthBorder.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) { content() }
    }
}

// ── Styled text field for dark theme ────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = AuthMuted) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AuthCardAlt,
            unfocusedContainerColor = AuthCardAlt,
            focusedBorderColor = AuthCyan,
            unfocusedBorderColor = AuthBorder,
            focusedTextColor = AuthWhite,
            unfocusedTextColor = AuthWhite,
            cursorColor = AuthCyan,
            focusedLabelColor = AuthCyan,
            unfocusedLabelColor = AuthMuted,
            focusedLeadingIconColor = AuthCyan,
            unfocusedLeadingIconColor = AuthMuted,
            focusedTrailingIconColor = AuthMuted,
            unfocusedTrailingIconColor = AuthMuted
        )
    )
}

@Composable
fun AuthScreen(
    db: AppDatabase,
    sessionNotice: String? = null,
    onNavigateDashboard: () -> Unit,
    onNavigateSuperAdmin: () -> Unit
) {
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(db))
    val context = LocalContext.current
    var isLoginMode by remember { mutableStateOf(true) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var instituteName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var whatsappNumber by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingDemoAccount by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    var biometricLoginAvailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { contentVisible = true }
    LaunchedEffect(sessionNotice, isLoginMode) {
        biometricLoginAvailable = isLoginMode &&
            BiometricAuthManager.savedSession(context) != null &&
            BiometricAuthManager.canAuthenticate(context)
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(AuthBg, AuthBgMid, AuthBgEnd)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        // Decorative orbs
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-100).dp, y = (-60).dp)
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF6D28D9).copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 80.dp, y = 120.dp)
                .size(320.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(AuthCyan.copy(alpha = 0.25f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-80).dp)
                .size(200.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(AuthViolet.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
        )

        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(800, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(800, easing = FastOutSlowInEasing, delayMillis = 200)) { it / 2 },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))

                // ═════════════════════════════════════════════════
                //  Animated Logo
                // ═════════════════════════════════════════════════
                AnimatedLogo()

                Spacer(Modifier.height(24.dp))

                // ═════════════════════════════════════════════════
                //  App Name + Tagline
                // ═════════════════════════════════════════════════
                Text(
                    text = "BatchFee",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                    ),
                    color = AuthWhite
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Smart institute management, simplified.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = AuthCyan,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))

                // ═════════════════════════════════════════════════
                //  Login / Register Form Card
                // ═════════════════════════════════════════════════
                GlassCard {
                    if (!isLoginMode) {
                        DarkTextField(
                            value = instituteName,
                            onValueChange = { instituteName = it },
                            label = "Institute Name",
                            leadingIcon = { Icon(Icons.Filled.AccountBalance, null, tint = AuthMuted) }
                        )
                        Spacer(Modifier.height(12.dp))
                        DarkTextField(
                            value = ownerName,
                            onValueChange = { ownerName = it },
                            label = "Your Name",
                            leadingIcon = { Icon(Icons.Filled.Person, null, tint = AuthMuted) }
                        )
                        Spacer(Modifier.height(12.dp))
                        DarkTextField(
                            value = whatsappNumber,
                            onValueChange = { whatsappNumber = it },
                            label = "WhatsApp Number",
                            leadingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("+880 ", color = AuthMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            },
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    DarkTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = "Email / Staff ID",
                        leadingIcon = { Icon(Icons.Filled.Email, null, tint = AuthMuted) },
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                    )
                    Spacer(Modifier.height(12.dp))

                    DarkTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        leadingIcon = { Icon(Icons.Filled.Lock, null, tint = AuthMuted) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    if (passwordVisible) "Hide" else "Show",
                                    tint = AuthMuted
                                )
                            }
                        }
                    )

                    if (errorMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuthErrorBg)
                                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                errorMessage!!,
                                color = Color(0xFFFCA5A5),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (sessionNotice != null) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuthBlue.copy(alpha = 0.14f))
                                .border(1.dp, AuthCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                sessionNotice,
                                color = AuthCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Primary action button
                    Button(
                        onClick = {
                            if (isLoading || loadingDemoAccount != null) return@Button
                            errorMessage = null
                            isLoading = true
                            if (isLoginMode) {
                                viewModel.login(email, password, onSuccess = { role ->
                                    isLoading = false
                                    BiometricAuthManager.refreshCurrentSession(context, email)
                                    if (role == "SuperAdmin") onNavigateSuperAdmin()
                                    else onNavigateDashboard()
                                }, onError = {
                                    errorMessage = it
                                    isLoading = false
                                })
                            } else {
                                viewModel.registerInstitute(
                                    instituteName, ownerName, email, password, whatsappNumber,
                                    onSuccess = {
                                        isLoading = false
                                        BiometricAuthManager.refreshCurrentSession(context, email)
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
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .shadow(8.dp, RoundedCornerShape(14.dp), spotColor = AuthCyan.copy(alpha = 0.4f))
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.horizontalGradient(listOf(AuthBlue, AuthCyan))),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading && loadingDemoAccount == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (isLoginMode) "Login" else "Create Institute & Start Trial",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }

                    if (biometricLoginAvailable) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                if (isLoading || loadingDemoAccount != null) return@OutlinedButton
                                errorMessage = null
                                val availability = BiometricAuthManager.availabilityMessage(context)
                                val activity = BiometricAuthManager.findFragmentActivity(context)
                                when {
                                    availability != null -> errorMessage = availability
                                    activity == null -> errorMessage = "Biometric login needs an active app screen."
                                    else -> BiometricAuthManager.showPrompt(
                                        activity = activity,
                                        title = "Unlock BatchFee",
                                        subtitle = "Use your fingerprint to log in",
                                        negativeButtonText = "Use password",
                                        onSuccess = {
                                            isLoading = true
                                            viewModel.loginWithBiometric(
                                                context = context,
                                                onSuccess = { role ->
                                                    isLoading = false
                                                    if (role == "SuperAdmin") onNavigateSuperAdmin()
                                                    else onNavigateDashboard()
                                                },
                                                onError = {
                                                    errorMessage = it
                                                    isLoading = false
                                                    biometricLoginAvailable =
                                                        BiometricAuthManager.savedSession(context) != null &&
                                                        BiometricAuthManager.canAuthenticate(context)
                                                }
                                            )
                                        },
                                        onError = { errorMessage = it }
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AuthCyan.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AuthCyan)
                        ) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Login with Fingerprint", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Toggle login/register
                TextButton(onClick = { isLoginMode = !isLoginMode; errorMessage = null }) {
                    Text(
                        text = if (isLoginMode) "Need an account? Register Institute" else "Already have an account? Login",
                        color = AuthCyan,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "v1.0 — BatchFee",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuthMuted.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
