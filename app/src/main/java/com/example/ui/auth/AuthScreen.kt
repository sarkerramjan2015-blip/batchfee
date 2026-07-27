package com.batchfee.edu.ui.auth

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
import com.batchfee.edu.BuildConfig
import com.batchfee.edu.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.AppUserSyncHelper
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.UserEntity
import com.batchfee.edu.domain.BiometricAuthManager
import com.batchfee.edu.domain.DemoAuthRepository
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.batchfee.edu.data.firestore.StaffSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope

class AuthViewModel(private val db: AppDatabase) : ViewModel() {

    /**
     * A dashboard is only safe to enter after its institute has been resolved.
     * This prevents a real account from rendering cached/demo placeholders while
     * its actual profile is still being synchronized.
     */
    private suspend fun resolveInstituteBeforeNavigation(instituteId: String?, role: String) {
        if (role == "SuperAdmin") return
        val resolvedInstituteId = instituteId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Your institute profile could not be resolved. Please try again.")

        withContext(Dispatchers.IO) {
            InstituteSyncHelper.syncInstituteFromFirestore(db, resolvedInstituteId)
            checkNotNull(db.instituteDao().getInstitute(resolvedInstituteId)) {
                "Your institute profile is still unavailable. Please try again."
            }
        }
    }
    
    fun trackDemoLogin(accountType: String) {
        viewModelScope.launch {
            DemoAuthRepository.trackDemoLogin(accountType)
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

                // Write to Firestore FIRST — fail early if network/rules issue
                withContext(Dispatchers.IO) {
                    val firestore = FirebaseFirestore.getInstance()
                    firestore.collection("institutes").document(uid).set(
                        mapOf(
                            "instituteName" to instituteName,
                            "ownerName" to ownerName,
                            "email" to email,
                            "whatsappNumber" to whatsappNumber,
                            "role" to "owner",
                            "createdAt" to now,
                            "isActive" to true,
                            "trialEndDate" to (now + fifteenDaysMs),
                            "studentCount" to 0,
                            "staffCount" to 0,
                            "batchCount" to 0
                        )
                    ).await()
                }

                // Verify Firestore write succeeded before proceeding
                withContext(Dispatchers.IO) {
                    val verify = FirebaseFirestore.getInstance()
                        .collection("institutes").document(uid)
                        .get().await()
                    if (!verify.exists()) {
                        throw FirebaseFirestoreException(
                            "Cloud sync failed. Please check your connection and try again.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
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
                FirebaseCrashlytics.getInstance().recordException(e)
                val message = when (e.errorCode) {
                    "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists"
                    "ERROR_INVALID_EMAIL" -> "Please enter a valid email address"
                    "ERROR_WEAK_PASSWORD" -> "Password should be at least 6 characters"
                    else -> e.localizedMessage ?: "Authentication failed"
                }
                onError(message)
            } catch (e: FirebaseFirestoreException) {
                FirebaseCrashlytics.getInstance().recordException(e)
                // Firestore write failed but Auth succeeded — cleanup auth user
                try {
                    FirebaseAuth.getInstance().currentUser?.delete()
                } catch (_: Exception) { }
                onError(e.localizedMessage ?: "Cloud sync failed. Check your connection and try again.")
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                onError(e.localizedMessage ?: "Registration failed")
            }
        }
    }

    fun login(
        credential: String,
        passwordHash: String,
        onSuccess: (role: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (credential.isBlank() || passwordHash.isBlank()) {
            onError("Email/Staff ID and password are required")
            return
        }
        
        viewModelScope.launch {
            try {
                val input = credential.trim()
                val cleanPassword = passwordHash.trim()
                val hasAt = input.contains("@")
                android.util.Log.d("AUTH_LOGIN", "LOGIN: credential=$input, hasAt=$hasAt, password length=${cleanPassword.length}")

                // ── SMART ROUTING: Email vs Staff ID ──
                val firebaseEmail: String
                if (hasAt) {
                    // Direct email login
                    firebaseEmail = input
                } else {
                    // Staff ID — resolve to email via Room DB
                    val staff = db.staffDao().getStaffByCodeOnce(input.uppercase())
                    if (staff == null) {
                        android.util.Log.w("AUTH_LOGIN", "Staff ID not found: $input")
                        onError("Staff ID not found or not synced to this device.")
                        return@launch
                    }
                    val staffEmail = staff.email
                    if (staffEmail.isNullOrBlank()) {
                        android.util.Log.w("AUTH_LOGIN", "Staff has no email: ${staff.id}")
                        onError("No email linked to this Staff ID. Contact your admin.")
                        return@launch
                    }
                    firebaseEmail = staffEmail
                }

                // ── Firebase Auth ──
                android.util.Log.d("AUTH_LOGIN", "Calling signInWithEmailAndPassword: email=$firebaseEmail")
                val authResult = withContext(Dispatchers.IO) {
                    // Sign out any stale session first to avoid credential expiry errors
                    try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) { }
                    FirebaseAuth.getInstance()
                        .signInWithEmailAndPassword(firebaseEmail, cleanPassword)
                        .await()
                }
                val uid = authResult.user?.uid
                    ?: throw IllegalStateException("Firebase Auth returned null UID")
                android.util.Log.d("AUTH_LOGIN", "AUTH OK: uid=$uid")

                // ── Fetch or rebuild local user record ──
                val managedUser = withContext(Dispatchers.IO) {
                    AppUserSyncHelper.fetchManagedUser(uid)
                }
                var localUser = db.userDao().getUserById(uid)
                val firestoreUserDoc = withContext(Dispatchers.IO) {
                    FirebaseFirestore.getInstance()
                        .collection("institutes").document(uid)
                        .get().await()
                }

                val role: String
                val instituteId: String?
                var staffPermissions: String? = null

                if (managedUser != null && managedUser.role != "Staff") {
                    role = managedUser.role
                    instituteId = managedUser.instituteId ?: uid

                    if (localUser == null) {
                        localUser = UserEntity(
                            id = uid,
                            instituteId = managedUser.instituteId,
                            name = managedUser.name,
                            email = managedUser.email,
                            passwordHash = PasswordHasher.hash(passwordHash),
                            role = managedUser.role,
                            createdAtMs = managedUser.createdAtMs
                        )
                        db.userDao().insertUser(localUser)
                    } else if (
                        localUser.name != managedUser.name ||
                        localUser.email != managedUser.email ||
                        localUser.role != managedUser.role ||
                        localUser.instituteId != managedUser.instituteId
                    ) {
                        localUser = localUser.copy(
                            instituteId = managedUser.instituteId,
                            name = managedUser.name,
                            email = managedUser.email,
                            role = managedUser.role
                        )
                        db.userDao().updateUser(localUser)
                    }

                    if (role != "SuperAdmin" && !instituteId.isNullOrBlank()) {
                        val canonicalInstituteDoc = withContext(Dispatchers.IO) {
                            FirebaseFirestore.getInstance()
                                .collection("institutes").document(instituteId)
                                .get().await()
                        }
                        if (canonicalInstituteDoc.exists()) {
                            val data = canonicalInstituteDoc.data ?: emptyMap()
                            val now = System.currentTimeMillis()
                            val currentPlanId = data["currentPlanId"] as? String ?: "plan_free_trial"
                            val subscriptionStatus = data["subscriptionStatus"] as? String
                                ?: if (currentPlanId == "plan_free_trial") "trial" else "active"
                            db.instituteDao().insertInstitute(
                                InstituteEntity(
                                    id = instituteId,
                                    name = data["instituteName"] as? String ?: "Institute",
                                    currentPlanId = currentPlanId,
                                    subscriptionStatus = subscriptionStatus,
                                    trialStartDateMs = data["createdAt"] as? Long ?: now,
                                    trialEndDateMs = data["trialEndDate"] as? Long ?: (now + 15L * 24 * 60 * 60 * 1000),
                                    currentPeriodEndMs = (data["currentPeriodEndMs"] as? Long)
                                        ?: (data["trialEndDate"] as? Long ?: (now + 15L * 24 * 60 * 60 * 1000)),
                                    createdAtMs = data["createdAt"] as? Long ?: now,
                                    phone = data["phone"] as? String,
                                    whatsappNumber = data["whatsappNumber"] as? String,
                                    ownerName = data["ownerName"] as? String,
                                    email = data["email"] as? String,
                                    instituteCode = data["instituteCode"] as? String,
                                    securityPin = data["securityPin"] as? String
                                )
                            )
                        }
                    }
                } else if (firestoreUserDoc.exists()) {
                    val data = firestoreUserDoc.data ?: emptyMap()
                    role = when (val r = data["role"] as? String) {
                        "owner" -> "InstituteOwner"
                        "admin" -> "InstituteAdmin"
                        "instituteAdmin", "institute_admin" -> "InstituteAdmin"
                        "superAdmin", "super_admin" -> "SuperAdmin"
                        else -> r ?: "InstituteOwner"
                    }
                    instituteId = data["instituteId"] as? String ?: uid

                    if (localUser == null) {
                        val now = System.currentTimeMillis()
                        val currentPlanId = data["currentPlanId"] as? String ?: "plan_free_trial"
                        val subscriptionStatus = data["subscriptionStatus"] as? String
                            ?: if (currentPlanId == "plan_free_trial") "trial" else "active"
                        localUser = UserEntity(
                            id = uid,
                            instituteId = uid,
                            name = data["ownerName"] as? String ?: data["instituteName"] as? String ?: "",
                            email = firebaseEmail,
                            passwordHash = PasswordHasher.hash(passwordHash),
                            role = role,
                            createdAtMs = data["createdAt"] as? Long ?: now
                        )
                        db.userDao().insertUser(localUser)
                        db.instituteDao().insertInstitute(
                            InstituteEntity(
                                id = uid,
                                name = data["instituteName"] as? String ?: "Institute",
                                currentPlanId = currentPlanId,
                                subscriptionStatus = subscriptionStatus,
                                trialStartDateMs = data["createdAt"] as? Long ?: now,
                                trialEndDateMs = data["trialEndDate"] as? Long ?: (now + 15L * 24 * 60 * 60 * 1000),
                                currentPeriodEndMs = (data["currentPeriodEndMs"] as? Long)
                                    ?: (data["trialEndDate"] as? Long ?: (now + 15L * 24 * 60 * 60 * 1000)),
                                createdAtMs = data["createdAt"] as? Long ?: now,
                                phone = data["phone"] as? String,
                                whatsappNumber = data["whatsappNumber"] as? String,
                                ownerName = data["ownerName"] as? String,
                                email = data["email"] as? String,
                                instituteCode = data["instituteCode"] as? String
                            )
                        )
                    }
                } else if (localUser != null && localUser.role != "Staff") {
                    // Firestore doc missing but user exists in local Room — offline/legacy fallback
                    android.util.Log.w("AUTH_LOGIN", "Firestore doc not found but local user exists: uid=$uid, role=${localUser.role}")
                    role = localUser.role
                    instituteId = localUser.instituteId
                    staffPermissions = null
                } else {
                    // Not an institute — check if staff
                    // Try all staff subcollections (worst case iterates, but staff count is small)
                    var foundStaff: StaffSyncHelper.StaffFirestoreData? = null
                    var foundInstId: String? = null
                    // Query local staff list to find institute
                    val localStaff = withContext(Dispatchers.IO) {
                        db.staffDao().getStaffByCodeOnce(input.uppercase())
                    }
                    if (localStaff != null && localStaff.id == uid) {
                        val fsData = StaffSyncHelper.fetchStaffFromFirestore(localStaff.instituteId, uid)
                        if (fsData != null) {
                            foundStaff = fsData
                            foundInstId = localStaff.instituteId
                        }
                    }
                    // Fallback: search by email in local users
                    if (foundStaff == null) {
                        val emailUser = db.userDao().getUserByEmail(firebaseEmail)
                        if (emailUser != null && emailUser.role == "Staff") {
                            val localSt = db.staffDao().getStaffByIdOnce(emailUser.id, emailUser.instituteId ?: "")
                            if (localSt != null) {
                                foundStaff = StaffSyncHelper.fetchStaffFromFirestore(
                                    localSt.instituteId, emailUser.id
                                )
                                foundInstId = emailUser.instituteId
                            }
                        }
                    }

                    if (foundStaff == null || foundInstId == null) {
                        onError("Account not found. If you registered before, your data may need to be re-synced. Contact support.")
                        return@launch
                    }
                    if (foundStaff.status != "active") {
                        onError("This staff account is inactive. Contact your admin.")
                        return@launch
                    }
                    role = "Staff"
                    instituteId = foundInstId
                    staffPermissions = foundStaff.permissions

                    if (localUser == null) {
                        localUser = UserEntity(
                            id = uid,
                            instituteId = foundInstId,
                            name = foundStaff.fullName,
                            email = firebaseEmail,
                            passwordHash = PasswordHasher.hash(passwordHash),
                            role = "Staff",
                            createdAtMs = System.currentTimeMillis()
                        )
                        db.userDao().insertUser(localUser)
                    }
                    // Sync staff to local Room
                    val staffEntity = com.batchfee.edu.data.models.StaffEntity(
                        id = uid,
                        instituteId = foundInstId,
                        staffCode = foundStaff.staffCode,
                        fullName = foundStaff.fullName,
                        photoUri = foundStaff.photoUri.takeIf { it.isNotBlank() },
                        roleTitle = foundStaff.roleTitle,
                        phone = foundStaff.phone.takeIf { it.isNotBlank() },
                        email = foundStaff.email.takeIf { it.isNotBlank() },
                        address = foundStaff.address.takeIf { it.isNotBlank() },
                        joiningDateMs = foundStaff.joiningDateMs,
                        monthlySalary = foundStaff.monthlySalary,
                        assignedBatchIds = foundStaff.assignedBatchIds.takeIf { it.isNotBlank() },
                        status = foundStaff.status,
                        notes = foundStaff.notes.takeIf { it.isNotBlank() },
                        permissions = foundStaff.permissions.takeIf { it.isNotBlank() },
                        createdAtMs = foundStaff.createdAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        updatedAtMs = foundStaff.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        archivedAtMs = foundStaff.archivedAtMs
                    )
                    db.staffDao().insertStaff(staffEntity)
                }

                // Seed demo data at the REAL Firebase UID for demo accounts
                val isDemoOwner = firebaseEmail == "demo@batchfee.app" || firebaseEmail == "owner@batchfee.app"
                if (isDemoOwner && role == "InstituteOwner" && instituteId != null) {
                    withContext(Dispatchers.IO) {
                        val studentCount = db.studentDao().getStudentsByInstituteOnce(instituteId).size
                        if (studentCount == 0) {
                            AppDatabase.realOwnerUid = uid
                            AppDatabase.seedDemoForRealUid(db, uid, instituteId)
                        }
                    }
                } else {
                    val existingPlans = db.subscriptionPlanDao().getAllPlans().first()
                    if (existingPlans.isEmpty()) {
                        AppDatabase.ensureDemoDataSeeded(db)
                    }
                }

                // Reset failed attempts on successful login
                if (localUser.failedAttempts > 0 || localUser.lockedUntilMs != null) {
                    db.userDao().resetFailedAttempts(firebaseEmail)
                }

                resolveInstituteBeforeNavigation(instituteId, role)
                SessionManager.login(uid, instituteId ?: "", role, staffPermissions)
                onSuccess(role)

            } catch (e: FirebaseAuthException) {
                android.util.Log.e("AUTH_LOGIN", "FirebaseAuthException: code=${e.errorCode}, msg=${e.message}")
                FirebaseCrashlytics.getInstance().recordException(e)
                val message = when (e.errorCode) {
                    "ERROR_INVALID_EMAIL" -> "Invalid email address."
                    "ERROR_WRONG_PASSWORD" -> "Wrong password."
                    "ERROR_USER_NOT_FOUND" -> "Account not found. Check your email or contact support."
                    "ERROR_INVALID_CREDENTIAL" -> "Invalid credentials. Try again or reset your password."
                    else -> e.localizedMessage ?: "Login failed. Please try again."
                }
                onError(message)
            } catch (e: Exception) {
                android.util.Log.e("AUTH_LOGIN", "Unexpected error: ${e.message}", e)
                FirebaseCrashlytics.getInstance().recordException(e)
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

                resolveInstituteBeforeNavigation(instituteId, user.role)
                SessionManager.login(user.id, instituteId, user.role, staffPermissions)
                BiometricAuthManager.refreshCurrentSession(appContext, user.email)
                onSuccess(user.role)
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Biometric login failed. Try password login.")
            }
        }
    }

    fun sendPasswordResetEmail(
        email: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            onError("Email address is required.")
            return
        }
        if (!normalizedEmail.contains("@")) {
            onError("Enter your registered email address.")
            return
        }

        viewModelScope.launch {
            try {
                FirebaseAuth.getInstance().sendPasswordResetEmail(normalizedEmail).await()
                onSuccess("Password reset email sent to $normalizedEmail")
            } catch (e: FirebaseAuthException) {
                FirebaseCrashlytics.getInstance().recordException(e)
                val message = when (e.errorCode) {
                    "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
                    "ERROR_USER_NOT_FOUND" -> "No Firebase account was found for this email."
                    else -> e.localizedMessage ?: "Could not send reset email."
                }
                onError(message)
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                onError(e.localizedMessage ?: "Could not send reset email.")
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

// Dark premium palette shared with FeeDashboard
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

// Animated, floating logo composable
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

// Glass card for input fields
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = AuthCyan.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AuthCardBg.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, AuthBorder.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) { content() }
    }
}

// Styled text field for dark theme
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
    onNavigateSuperAdmin: () -> Unit,
    onNavigatePrivacyPolicy: () -> Unit,
    onNavigateTermsConditions: () -> Unit
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
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var fieldError by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingDemoAccount by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    var biometricLoginAvailable by remember { mutableStateOf(false) }
    var showForgotDialog by remember { mutableStateOf(false) }
    var forgotEmail by remember { mutableStateOf("") }
    var consentChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { contentVisible = true }
    LaunchedEffect(sessionNotice, isLoginMode) {
        biometricLoginAvailable = isLoginMode &&
            BiometricAuthManager.savedSession(context) != null &&
            BiometricAuthManager.canAuthenticate(context)
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(AuthBg, AuthBgMid, AuthBgEnd)
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        val compactWidth = maxWidth < 360.dp
        val compactHeight = maxHeight < 700.dp
        val contentHorizontalPadding = if (compactWidth) 16.dp else 24.dp
        val contentVerticalPadding = if (compactHeight) 24.dp else 48.dp
        val logoSize = if (compactWidth) 88.dp else 100.dp
        val orbOneSize = if (compactWidth || compactHeight) 220.dp else 280.dp
        val orbTwoSize = if (compactWidth || compactHeight) 260.dp else 320.dp
        val orbThreeSize = if (compactWidth || compactHeight) 160.dp else 200.dp
        val formMaxWidth = if (maxWidth >= 600.dp) 480.dp else 0.dp
        val actionsMaxWidth = if (compactWidth) 320.dp else 360.dp
        // Decorative orbs
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-80).dp, y = (-48).dp)
                .size(orbOneSize)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF6D28D9).copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 72.dp, y = 96.dp)
                .size(orbTwoSize)
                .background(
                    Brush.radialGradient(
                        colors = listOf(AuthCyan.copy(alpha = 0.25f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-64).dp)
                .size(orbThreeSize)
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
                    .padding(horizontal = contentHorizontalPadding, vertical = contentVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))

                // Animated Logo
                AnimatedLogo(modifier = Modifier.size(logoSize))

                Spacer(Modifier.height(if (compactHeight) 18.dp else 24.dp))

                // App Name + Tagline
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
                Spacer(Modifier.height(if (compactHeight) 20.dp else 32.dp))

                // Login / Register Form Card
                GlassCard(
                    modifier = if (formMaxWidth > 0.dp) {
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = formMaxWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                ) {
                    if (!isLoginMode) {
            val hasInstErr = fieldError.containsKey("instituteName")
            val hasOwnerErr = fieldError.containsKey("ownerName")

            DarkTextField(
                value = instituteName,
                onValueChange = { instituteName = it; if (hasInstErr) { fieldError = fieldError - "instituteName"; errorMessage = null } },
                label = "Institute Name *",
                leadingIcon = { Icon(Icons.Filled.AccountBalance, null, tint = AuthMuted) }
            )
            if (hasInstErr) Text("This field is required", color = Color(0xFFF87171), fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
            Spacer(Modifier.height(12.dp))
            DarkTextField(
                value = ownerName,
                onValueChange = { ownerName = it; if (hasOwnerErr) { fieldError = fieldError - "ownerName"; errorMessage = null } },
                label = "Your Name *",
                leadingIcon = { Icon(Icons.Filled.Person, null, tint = AuthMuted) }
            )
            if (hasOwnerErr) Text("This field is required", color = Color(0xFFF87171), fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
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

        val hasEmailErr = fieldError.containsKey("email")
        DarkTextField(
            value = email,
            onValueChange = { email = it; if (hasEmailErr) { fieldError = fieldError - "email"; errorMessage = null } },
            label = if (isLoginMode) "Email or Staff ID" else "Email *",
            leadingIcon = { Icon(Icons.Filled.Email, null, tint = AuthMuted) },
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
        )
        if (hasEmailErr) Text("This field is required", color = Color(0xFFF87171), fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
        Spacer(Modifier.height(12.dp))

        val hasPwdErr = fieldError.containsKey("password")
        DarkTextField(
            value = password,
            onValueChange = { password = it; if (hasPwdErr) { fieldError = fieldError - "password"; errorMessage = null } },
            label = if (isLoginMode) "Password" else "Password *",
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
        if (hasPwdErr) Text("This field is required", color = Color(0xFFF87171), fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))

                    if (!isLoginMode) {
                        Spacer(Modifier.height(8.dp))
                        val hasConsentErr = fieldError.containsKey("consent")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = consentChecked,
                                onCheckedChange = {
                                    consentChecked = it
                                    if (hasConsentErr && it) {
                                        fieldError = fieldError - "consent"
                                        errorMessage = null
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AuthCyan,
                                    uncheckedColor = AuthMuted,
                                    checkmarkColor = AuthBg
                                )
                            )
                            Row(Modifier.padding(start = 4.dp)) {
                                Text("I agree to the ", color = AuthMuted, fontSize = 12.sp)
                                TextButton(
                                    onClick = onNavigatePrivacyPolicy,
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                                ) {
                                    Text("Privacy Policy", color = AuthCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(" & ", color = AuthMuted, fontSize = 12.sp)
                                TextButton(
                                    onClick = onNavigateTermsConditions,
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                                ) {
                                    Text("Terms", color = AuthCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (hasConsentErr) {
                            Text("You must accept the terms to continue", color = Color(0xFFF87171), fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
                        }
                    }

                    // Forgot Password link (login mode only)
                    if (isLoginMode) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                forgotEmail = email.takeIf { it.contains("@") } ?: ""
                                infoMessage = null
                                showForgotDialog = true
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Forgot Password?", color = AuthMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

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
                    } else if (infoMessage != null) {
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
                                infoMessage!!,
                                color = AuthCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (isLoginMode && sessionNotice != null) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFFFFE4E6).copy(alpha = 0.18f),
                                            Color(0xFFFECACA).copy(alpha = 0.12f)
                                        )
                                    )
                                )
                                .border(1.dp, Color(0xFFFB7185).copy(alpha = 0.38f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                sessionNotice,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color(0xFFFF8A80), Color(0xFFFF5C73))
                                    )
                                ),
                                fontWeight = FontWeight.SemiBold,
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
                                // Per-field validation before calling ViewModel
                                val errs = mutableMapOf<String, Boolean>()
                                if (instituteName.isBlank()) errs["instituteName"] = true
                                if (ownerName.isBlank()) errs["ownerName"] = true
                                if (email.isBlank()) errs["email"] = true
                                if (password.isBlank()) errs["password"] = true
                                if (!consentChecked) errs["consent"] = true
                                if (errs.isNotEmpty()) {
                                    fieldError = errs
                                    errorMessage = "Please fill all required fields and accept the terms."
                                    isLoading = false
                                    return@Button
                                }
                                fieldError = emptyMap()
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

                // Forgot Password Dialog
                if (showForgotDialog) {
                    AlertDialog(
                        onDismissRequest = { showForgotDialog = false },
                        title = { Text("Reset Password", fontWeight = FontWeight.Bold, color = AuthWhite) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Enter your email to receive a password reset link.", color = AuthMuted, fontSize = 14.sp)
                                DarkTextField(
                                    value = forgotEmail,
                                    onValueChange = { forgotEmail = it },
                                    label = "Email Address",
                                    leadingIcon = { Icon(Icons.Filled.Email, null, tint = AuthMuted) }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    infoMessage = null
                                    errorMessage = null
                                    viewModel.sendPasswordResetEmail(
                                        email = forgotEmail,
                                        onSuccess = {
                                            infoMessage = it
                                            showForgotDialog = false
                                            forgotEmail = ""
                                        },
                                        onError = { errorMessage = it }
                                    )
                                }
                            ) { Text("Send Reset Link", color = AuthCyan, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showForgotDialog = false }) { Text("Cancel", color = AuthMuted) }
                        },
                        containerColor = AuthCardBg,
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Toggle login/register
                TextButton(onClick = { isLoginMode = !isLoginMode; errorMessage = null }) {
                    Text(
                        text = if (isLoginMode) "Need an account? Register Institute" else "Already have an account? Login",
                        color = AuthCyan,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onNavigatePrivacyPolicy) {
                        Text("Privacy Policy", color = AuthCyan, fontSize = 12.sp)
                    }
                    Text(
                        text = "·",
                        color = AuthMuted.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                    TextButton(onClick = onNavigateTermsConditions) {
                        Text("Terms & Conditions", color = AuthCyan, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(4.dp))

                // WhatsApp contact button
                val encodedMsg = java.net.URLEncoder
                    .encode("Hello Developer, I am contacting you regarding some queries about the BatchFee app.", "UTF-8")
                val waUri = "https://api.whatsapp.com/send?phone=+8801518657869&text=$encodedMsg"
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(waUri))
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = actionsMaxWidth),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AuthCyan.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = AuthCyan.copy(alpha = 0.06f),
                        contentColor = AuthCyan.copy(alpha = 0.75f)
                    )
                ) {
                    Text("💬", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Chat on WhatsApp: +880 1518657869",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "v" + BuildConfig.VERSION_NAME + " · BatchFee",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuthMuted.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

