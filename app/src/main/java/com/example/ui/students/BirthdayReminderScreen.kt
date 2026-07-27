package com.batchfee.edu.ui.students

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.appendInstituteSignature
import com.batchfee.edu.domain.loadInstituteSignature
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val AccentPink = Color(0xFFEC4899)
private val AccentOrange = Color(0xFFF97316)
private val AccentGold = Color(0xFFFBBF24)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF10B981)
private val WAGreen = Color(0xFF25D366)

// ── Birthday PNG Card Generator ────────────────────────────────
private fun generateBirthdayCard(
    studentName: String,
    age: Int,
    context: android.content.Context
): Uri {
    val w = 800; val h = 1000
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)

    // Gradient background
    val bgPaint = Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                android.graphics.Color.parseColor("#1E1B4B"),
                android.graphics.Color.parseColor("#312E81"),
                android.graphics.Color.parseColor("#4C1D95")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

    // Decorative circles
    val circlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#7C3AED")
        alpha = 30
    }
    c.drawCircle(100f, 150f, 200f, circlePaint)
    c.drawCircle(700f, 800f, 250f, circlePaint)
    circlePaint.alpha = 20
    c.drawCircle(400f, 500f, 150f, circlePaint)

    // Stars/sparkles
    val sparklePaint = Paint().apply {
        color = AccentGold.toArgb()
        alpha = 60
        style = Paint.Style.FILL
    }
    val sparkles = listOf(
        Triple(120f, 200f, 12f), Triple(680f, 120f, 8f), Triple(200f, 700f, 10f),
        Triple(600f, 600f, 14f), Triple(350f, 100f, 6f), Triple(700f, 400f, 9f),
        Triple(100f, 500f, 11f), Triple(500f, 850f, 7f)
    )
    sparkles.forEach { (sx, sy, r) ->
        c.drawCircle(sx, sy, r, sparklePaint)
    }

    // Birthday cake icon area
    val cakePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 20
    }
    c.drawCircle(400f, 230f, 90f, cakePaint)
    val cakeTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 70f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText("\uD83C\uDF82", 400f, 260f, cakeTextPaint)

    // "HAPPY BIRTHDAY" header
    val headerPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#FBBF24")
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText("HAPPY BIRTHDAY", 400f, 380f, headerPaint)

    // Student name
    val namePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText(studentName, 400f, 450f, namePaint)

    // Age
    val agePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#22D3EE")
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText("Turning $age", 400f, 502f, agePaint)

    // Divider line
    val linePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#FBBF24")
        strokeWidth = 2f
        alpha = 80
    }
    c.drawLine(200f, 540f, 600f, 540f, linePaint)

    // Message lines
    val msgPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#E2E8F0")
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText("Wishing you a day filled with", 400f, 600f, msgPaint)
    c.drawText("joy, laughter, and wonderful moments.", 400f, 640f, msgPaint)
    c.drawText("May all your dreams come true!", 400f, 680f, msgPaint)

    // Gift box
    val giftPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 15
    }
    c.drawRoundRect(RectF(320f, 730f, 480f, 830f), 20f, 20f, giftPaint)
    val giftTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 55f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText("\uD83C\uDF81", 400f, 800f, giftTextPaint)

    // "BatchFee" footer
    val footerPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#94A3B8")
        textSize = 20f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    c.drawText("Sent with love via BatchFee", 400f, 940f, footerPaint)

    // Save
    val file = File(context.cacheDir, "birthday_card_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

// ── Main Screen ─────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayReminderScreen(db: AppDatabase, onBack: () -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: BirthdayViewModel = viewModel(factory = BirthdayViewModelFactory(db))
    val todayBirthdays by viewModel.todayBirthdays.collectAsState()
    val upcomingBirthdays by viewModel.upcomingBirthdays.collectAsState()
    val context = LocalContext.current
    val instId = SessionManager.currentInstituteId.value
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var instituteSignature by remember { mutableStateOf("") }

    LaunchedEffect(instId) {
        instituteSignature = loadInstituteSignature(db, instId)
    }

    var wishDialogTarget by remember { mutableStateOf<StudentEntity?>(null) }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Birthday Reminders",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        val isEmpty = todayBirthdays.isEmpty() && upcomingBirthdays.isEmpty()

        if (isEmpty) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Cake, null,
                        tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No upcoming birthdays.", color = TextMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Birthdays within 30 days will appear here.",
                        color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ── Today's Birthdays ──────────────────────
            if (todayBirthdays.isNotEmpty()) {
                SectionHeader("Today's Birthdays", AccentOrange)
                Spacer(Modifier.height(10.dp))
                todayBirthdays.forEach { student ->
                    BirthdayCard(
                        student = student,
                        viewModel = viewModel,
                        onWishClick = { wishDialogTarget = student }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Upcoming Birthdays ────────────────────
            if (upcomingBirthdays.isNotEmpty()) {
                SectionHeader("Upcoming Birthdays", Cyan)
                Spacer(Modifier.height(10.dp))
                upcomingBirthdays.forEach { student ->
                    BirthdayCard(
                        student = student,
                        viewModel = viewModel,
                        onWishClick = { wishDialogTarget = student }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    // ── Wish dialog ──────────────────────────────────
    if (wishDialogTarget != null) {
        val student = wishDialogTarget!!
        val age = viewModel.calculateAge(student.dateOfBirthMs ?: 0)
        val birthDateStr = remember(student.dateOfBirthMs) {
            student.dateOfBirthMs?.let {
                SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it))
            } ?: ""
        }

        Dialog(onDismissRequest = { wishDialogTarget = null }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Send Birthday Wish",
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${student.fullName} · $birthDateStr · Turning $age",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    // WhatsApp option
                    WishOptionCard(
                        label = "WhatsApp",
                        subtitle = "Send a beautiful birthday card image",
                        icon = Icons.Filled.Chat,
                        color = WAGreen,
                        onClick = {
                            wishDialogTarget = null
                            try {
                                val cardUri = generateBirthdayCard(student.fullName, age, context)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, cardUri)
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        appendInstituteSignature(
                                            "Happy Birthday ${student.fullName}! Turning $age today.",
                                            instituteSignature
                                        )
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    `package` = "com.whatsapp"
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                // Fallback: generic share
                                val cardUri = generateBirthdayCard(student.fullName, age, context)
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, cardUri)
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            appendInstituteSignature(
                                                "Happy Birthday ${student.fullName}! Turning $age today.",
                                                instituteSignature
                                            )
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "Share Birthday Card"
                                ))
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))

                    // SMS option
                    WishOptionCard(
                        label = "SMS",
                        subtitle = "Send a text message",
                        icon = Icons.Filled.Sms,
                        color = ElectricBlue,
                        onClick = {
                            wishDialogTarget = null
                            val msg = appendInstituteSignature(
                                "Happy Birthday ${student.fullName}! \uD83C\uDF82 Turning $age today. " +
                                    "Wishing you a year filled with success and happiness. \uD83C\uDF89\uD83C\uDF81",
                                instituteSignature
                            )
                            val phone = student.phone?.takeIf { it.isNotBlank() }
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("smsto:${phone ?: ""}")
                                    putExtra("sms_body", msg)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, msg)
                                    },
                                    "Share via"
                                ))
                            }
                        }
                    )

                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { wishDialogTarget = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(4.dp, 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BirthdayCard(
    student: StudentEntity,
    viewModel: BirthdayViewModel,
    onWishClick: () -> Unit
) {
    val daysUntil = remember(student.dateOfBirthMs) {
        student.dateOfBirthMs?.let { viewModel.daysUntil(it) } ?: 0
    }
    val age = remember(student.dateOfBirthMs) {
        student.dateOfBirthMs?.let { viewModel.calculateAge(it) } ?: 0
    }
    val birthDateStr = remember(student.dateOfBirthMs) {
        student.dateOfBirthMs?.let {
            SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it))
        } ?: ""
    }
    val isToday = daysUntil == 0

    Card(
        modifier = Modifier.fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(14.dp), spotColor = (if (isToday) AccentOrange else Cyan).copy(alpha = 0.15f)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(
            1.5.dp,
            if (isToday) AccentOrange.copy(alpha = 0.5f) else BorderSub
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo / Avatar
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                val photoUri = student.photoUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                if (photoUri != null) {
                    AsyncImage(
                        model = photoUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.linearGradient(
                                listOf(AccentPink, AccentOrange)
                            )
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            student.fullName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(Modifier.weight(1f)) {
                Text(
                    student.fullName,
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cake, null, tint = AccentOrange, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$birthDateStr · Turning $age",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }

            // Days badge or action
            if (isToday) {
                Button(
                    onClick = onWishClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentOrange
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.Celebration, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Wish", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$daysUntil",
                        color = Cyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (daysUntil == 1) "day" else "days",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun WishOptionCard(
    label: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 12.sp)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        }
    }
}

