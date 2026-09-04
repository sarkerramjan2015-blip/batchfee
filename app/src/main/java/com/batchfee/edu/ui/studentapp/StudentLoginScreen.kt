package com.batchfee.edu.ui.studentapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.RememberedStudentIdStore
import kotlinx.coroutines.flow.collectLatest

// Premium dark palette — matching the main AuthScreen
private val StuBg       = Color(0xFF0F0C29)
private val StuBgMid    = Color(0xFF302B63)
private val StuBgEnd    = Color(0xFF24243E)
private val StuCardBg   = Color(0xFF0F172A)
private val StuCardAlt  = Color(0xFF111827)
private val StuBorder   = Color(0xFF1E293B)
private val StuCyan     = Color(0xFF22D3EE)
private val StuBlue     = Color(0xFF3B82F6)
private val StuViolet   = Color(0xFFA855F7)
private val StuGreen    = Color(0xFF34D399)
private val StuWhite    = Color(0xFFF8FAFC)
private val StuMuted    = Color(0xFF94A3B8)
private val StuErrorBg  = Color(0x33EF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember(context.applicationContext) {
        StudentLoginViewModel(RememberedStudentIdStore(context.applicationContext))
    }
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    var contentVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loginSuccess.collectLatest { success ->
            if (success) onLoginSuccess()
        }
    }
    LaunchedEffect(Unit) { contentVisible = true }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(StuBg, StuBgMid, StuBgEnd)
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        val compactWidth = maxWidth < 360.dp
        val compactHeight = maxHeight < 700.dp
        val contentHorizontalPadding = if (compactWidth) 16.dp else 24.dp
        val contentVerticalPadding = if (compactHeight) 16.dp else 32.dp
        val orbOneSize = if (compactWidth || compactHeight) 180.dp else 240.dp
        val orbTwoSize = if (compactWidth || compactHeight) 200.dp else 280.dp
        val orbThreeSize = if (compactWidth || compactHeight) 120.dp else 160.dp
        val formMaxWidth = if (maxWidth >= 600.dp) 480.dp else 0.dp

        // Decorative orbs
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 48.dp, y = (-32).dp)
                .size(orbOneSize)
                .background(
                    Brush.radialGradient(
                        colors = listOf(StuGreen.copy(alpha = 0.25f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-64).dp, y = 72.dp)
                .size(orbTwoSize)
                .background(
                    Brush.radialGradient(
                        colors = listOf(StuCyan.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-80).dp)
                .size(orbThreeSize)
                .background(
                    Brush.radialGradient(
                        colors = listOf(StuViolet.copy(alpha = 0.06f), Color.Transparent)
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
                // Back button row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            tint = StuMuted
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(if (compactHeight) 8.dp else 16.dp))

                // Animated icon — student badge
                var iconStart by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { iconStart = true }
                val iconScale by animateFloatAsState(
                    targetValue = if (iconStart) 1f else 0.3f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                )
                val floatOffset by rememberInfiniteTransition().animateFloat(
                    initialValue = -3f,
                    targetValue = 3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = iconScale; scaleY = iconScale
                            translationY = floatOffset * density
                        }
                        .shadow(16.dp, CircleShape, spotColor = StuGreen.copy(alpha = 0.30f))
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(StuGreen, StuCyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person,
                        "Student",
                        tint = StuWhite,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(Modifier.height(if (compactHeight) 12.dp else 20.dp))

                // Title
                Text(
                    text = "Student Login",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp)
                    ),
                    color = StuWhite
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Access your institute dashboard",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = StuGreen,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(if (compactHeight) 16.dp else 28.dp))

                // Glass form card
                Card(
                    modifier = (if (formMaxWidth > 0.dp) Modifier.fillMaxWidth().widthIn(max = formMaxWidth) else Modifier.fillMaxWidth())
                        .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = StuGreen.copy(alpha = 0.10f)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = StuCardBg.copy(alpha = 0.85f)),
                    border = BorderStroke(1.dp, StuBorder.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {

                        // Student ID
                        OutlinedTextField(
                            value = uiState.studentId,
                            onValueChange = viewModel::updateStudentId,
                            label = { Text("Student ID", color = StuMuted) },
                            placeholder = { Text("Your student ID", color = StuMuted.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Filled.Numbers, null, tint = StuMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next,
                                keyboardType = KeyboardType.Text,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            enabled = !uiState.isLoading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = StuCardAlt,
                                unfocusedContainerColor = StuCardAlt,
                                focusedBorderColor = StuGreen,
                                unfocusedBorderColor = StuBorder,
                                focusedTextColor = StuWhite,
                                unfocusedTextColor = StuWhite,
                                cursorColor = StuGreen,
                                focusedLabelColor = StuGreen,
                                unfocusedLabelColor = StuMuted,
                                focusedLeadingIconColor = StuGreen,
                                unfocusedLeadingIconColor = StuMuted
                            )
                        )

                        if (uiState.hasRememberedStudentId) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = StuGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Student ID saved on this phone",
                                    color = StuMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = viewModel::useDifferentStudentId,
                                    enabled = !uiState.isLoading,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("Change", color = StuCyan, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Password
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = viewModel::updatePassword,
                            label = { Text("Password", color = StuMuted) },
                            placeholder = { Text("Your login password", color = StuMuted.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Filled.Lock, null, tint = StuMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (uiState.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); viewModel.login() }),
                            trailingIcon = {
                                IconButton(onClick = viewModel::togglePasswordVisibility) {
                                    Icon(
                                        if (uiState.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        if (uiState.passwordVisible) "Hide" else "Show",
                                        tint = StuMuted
                                    )
                                }
                            },
                            enabled = !uiState.isLoading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = StuCardAlt,
                                unfocusedContainerColor = StuCardAlt,
                                focusedBorderColor = StuGreen,
                                unfocusedBorderColor = StuBorder,
                                focusedTextColor = StuWhite,
                                unfocusedTextColor = StuWhite,
                                cursorColor = StuGreen,
                                focusedLabelColor = StuGreen,
                                unfocusedLabelColor = StuMuted,
                                focusedLeadingIconColor = StuGreen,
                                unfocusedLeadingIconColor = StuMuted,
                                focusedTrailingIconColor = StuMuted,
                                unfocusedTrailingIconColor = StuMuted
                            )
                        )

                        // Error message
                        if (uiState.errorMessage != null) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(StuErrorBg)
                                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    uiState.errorMessage!!,
                                    color = Color(0xFFFCA5A5),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // Login button
                        Button(
                            onClick = viewModel::login,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            enabled = !uiState.isLoading
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shadow(8.dp, RoundedCornerShape(14.dp), spotColor = StuGreen.copy(alpha = 0.35f))
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.horizontalGradient(listOf(StuGreen, StuCyan))),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        "Login",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Footer hint
                Text(
                    "Use credentials provided by your institute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StuMuted.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
