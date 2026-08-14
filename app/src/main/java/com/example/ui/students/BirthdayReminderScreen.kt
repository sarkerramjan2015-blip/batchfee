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
private val AccentOrange = Color(0xFF8B5CF6)
private val AccentGold = Color(0xFFFBBF24)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF10B981)
private val WAGreen = Color(0xFF25D366)

// ── Birthday PNG Card Generator ────────────────────────────────
private fun generateBirthdayCard(
    studentName: String,
    age: Int,
    instituteName: String?,
    context: android.content.Context,
): Uri {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val midnight = android.graphics.Color.parseColor("#24104F")
    val violet = android.graphics.Color.parseColor("#5B2185")
    val pink = android.graphics.Color.parseColor("#EC4899")
    val coral = android.graphics.Color.parseColor("#FB7185")
    val gold = android.graphics.Color.parseColor("#FBBF24")
    val cream = android.graphics.Color.parseColor("#FFF7ED")
    val white = android.graphics.Color.WHITE
    val softWhite = android.graphics.Color.argb(225, 255, 255, 255)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(midnight, violet, android.graphics.Color.parseColor("#7C235C")),
            floatArrayOf(0f, 0.58f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    fill.color = android.graphics.Color.argb(38, 255, 255, 255)
    canvas.drawCircle(110f, 150f, 250f, fill)
    canvas.drawCircle(970f, 1160f, 290f, fill)
    fill.color = android.graphics.Color.argb(25, 251, 191, 36)
    canvas.drawCircle(900f, 260f, 185f, fill)

    val confetti = listOf(
        Triple(95f, 285f, 32f) to gold, Triple(190f, 170f, 22f) to coral,
        Triple(906f, 180f, 26f) to gold, Triple(981f, 410f, 30f) to pink,
        Triple(105f, 1000f, 28f) to coral, Triple(938f, 965f, 24f) to gold,
        Triple(240f, 1190f, 20f) to pink, Triple(810f, 1120f, 25f) to coral,
    )
    confetti.forEachIndexed { index, (shape, color) ->
        fill.color = color
        fill.alpha = 190
        canvas.save()
        canvas.rotate(if (index % 2 == 0) 28f else -26f, shape.first, shape.second)
        canvas.drawRoundRect(shape.first - shape.third, shape.second - 7f, shape.first + shape.third, shape.second + 7f, 7f, 7f, fill)
        canvas.restore()
    }
    fill.alpha = 255

    val panel = RectF(70f, 92f, width - 70f, height - 92f)
    fill.color = android.graphics.Color.argb(235, 255, 255, 255)
    canvas.drawRoundRect(panel, 42f, 42f, fill)
    val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(80, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    canvas.drawRoundRect(panel, 42f, 42f, panelStroke)

    val tagRect = RectF(360f, 142f, 720f, 204f)
    fill.color = android.graphics.Color.argb(24, 91, 33, 133)
    canvas.drawRoundRect(tagRect, 31f, 31f, fill)
    val tagStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(90, 91, 33, 133); style = Paint.Style.STROKE; strokeWidth = 2f }
    canvas.drawRoundRect(tagRect, 31f, 31f, tagStroke)
    val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = violet; textSize = 18f; isFakeBoldText = true; textAlign = Paint.Align.CENTER; letterSpacing = 0.13f }
    canvas.drawText("A SPECIAL DAY", 540f, 181f, tagPaint)

    drawBirthdayCake(canvas, 540f, 338f, pink, coral, gold, violet)
    val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = violet; textSize = 59f; isFakeBoldText = true; textAlign = Paint.Align.CENTER; letterSpacing = 0.02f }
    canvas.drawText("Happy Birthday", 540f, 516f, headlinePaint)
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = midnight; textSize = 65f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(fitBirthdayCardText(studentName, namePaint, 790f), 540f, 602f, namePaint)
    val ageRect = RectF(370f, 642f, 710f, 710f)
    fill.color = android.graphics.Color.argb(24, 236, 72, 153)
    canvas.drawRoundRect(ageRect, 34f, 34f, fill)
    val agePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pink; textSize = 27f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(if (age > 0) "Celebrating $age wonderful years" else "Celebrating your special day", 540f, 686f, agePaint)

    val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            275f, 0f, 805f, 0f,
            intArrayOf(pink, gold, pink),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        strokeWidth = 4f
    }
    canvas.drawRoundRect(275f, 758f, 805f, 762f, 2f, 2f, divider)
    val wishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#4C1D5C"); textSize = 28f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Wishing you a beautiful year of learning,", 540f, 836f, wishPaint)
    canvas.drawText("growth, success, and joyful moments ahead.", 540f, 880f, wishPaint)
    val supportivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#7E4B8B"); textSize = 22f; textAlign = Paint.Align.CENTER }
    canvas.drawText("May every dream you work for come a little closer today.", 540f, 935f, supportivePaint)

    drawBirthdayBalloon(canvas, 205f, 1014f, coral, -18f)
    drawBirthdayBalloon(canvas, 875f, 1014f, gold, 18f)
    val footerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(36, 91, 33, 133); strokeWidth = 2f }
    canvas.drawLine(205f, 1115f, 875f, 1115f, footerLine)
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#6B356E"); textSize = 21f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    val signature = instituteName?.trim()?.takeIf { it.isNotBlank() } ?: "BatchFee"
    canvas.drawText("With warm wishes from", 540f, 1170f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#8A5A92"); textSize = 18f; textAlign = Paint.Align.CENTER })
    canvas.drawText(fitBirthdayCardText(signature, footerPaint, 670f), 540f, 1211f, footerPaint)
    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#A46EAE"); textSize = 16f; textAlign = Paint.Align.CENTER; letterSpacing = 0.08f }
    canvas.drawText("BATCHFEE BIRTHDAY WISH", 540f, 1260f, brandPaint)

    val file = File(context.cacheDir, "birthday_card_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun fitBirthdayCardText(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end -= 1
    return value.take(end) + suffix
}

private fun drawBirthdayCake(canvas: Canvas, centerX: Float, top: Float, pink: Int, coral: Int, gold: Int, violet: Int) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fill.color = android.graphics.Color.argb(25, 91, 33, 133)
    canvas.drawCircle(centerX, top + 25f, 126f, fill)
    fill.color = coral
    canvas.drawRoundRect(centerX - 104f, top + 34f, centerX + 104f, top + 112f, 18f, 18f, fill)
    fill.color = pink
    canvas.drawRoundRect(centerX - 92f, top + 112f, centerX + 92f, top + 174f, 15f, 15f, fill)
    fill.color = android.graphics.Color.parseColor("#FFF1F2")
    for (index in 0..4) canvas.drawCircle(centerX - 72f + index * 36f, top + 109f, 12f, fill)
    val candlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND }
    listOf(-56f, 0f, 56f).forEach { offset ->
        canvas.drawLine(centerX + offset, top - 12f, centerX + offset, top + 34f, candlePaint)
        fill.color = android.graphics.Color.parseColor("#FDE68A")
        canvas.drawCircle(centerX + offset, top - 26f, 11f, fill)
    }
    val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(44, 91, 33, 133); strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
    canvas.drawLine(centerX - 120f, top + 187f, centerX + 120f, top + 187f, platePaint)
}

private fun drawBirthdayBalloon(canvas: Canvas, centerX: Float, top: Float, color: Int, tilt: Float) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.save()
    canvas.rotate(tilt, centerX, top)
    canvas.drawOval(RectF(centerX - 42f, top - 62f, centerX + 42f, top + 52f), fill)
    val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb(110, 91, 33, 133); strokeWidth = 2f }
    canvas.drawLine(centerX, top + 50f, centerX + if (tilt < 0f) -28f else 28f, top + 130f, stringPaint)
    canvas.restore()
}

private fun generateLegacyBirthdayCard(
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
                                val cardUri = generateBirthdayCard(student.fullName, age, instituteSignature, context)
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
                                val cardUri = generateBirthdayCard(student.fullName, age, instituteSignature, context)
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

