package com.batchfee.edu.ui.students

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.batchfee.edu.R
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardGeneratorScreen(db: AppDatabase, onBack: () -> Unit, onNavigateToPreview: (String, String) -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: StudentViewModel = viewModel(factory = StudentViewModelFactory(db))
    val students by viewModel.studentList.collectAsState()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(students, searchQuery) {
        if (searchQuery.isBlank()) students
        else students.filter { it.fullName.contains(searchQuery, ignoreCase = true) || it.studentCode.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("ID Card Generator", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("Search student by name or ID...", color = TextMuted.copy(alpha = 0.5f)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = Cyan, unfocusedBorderColor = BorderSub, focusedContainerColor = CardBgAlt, unfocusedContainerColor = CardBgAlt)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered) { student ->
                    Card(
                        modifier = Modifier.fillMaxWidth(), onClick = { onNavigateToPreview("student", student.id) },
                        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSub)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(ElectricBlue.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                if (!student.photoUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = FirebaseStorageImageUploadHelper.displaySource(context, student.photoUri),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Filled.Person, null, tint = Cyan, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(student.fullName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(student.studentCode, color = TextMuted, fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════
//  PROFESSIONAL ID CARD — shapes, glow, ring
// ══════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardPreviewScreen(db: AppDatabase, type: String, studentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var student by remember { mutableStateOf<StudentEntity?>(null) }
    var institute by remember { mutableStateOf<InstituteEntity?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(studentId) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            val s = withContext(Dispatchers.IO) { db.studentDao().getStudentById(studentId, instId).firstOrNull() }
            val inst = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
            student = s; institute = inst
        }
        isLoading = false
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("ID Card Preview", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (isLoading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cyan) }
        else if (student == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Student not found.", color = TextMuted) }
        else {
            val s = student!!
            val instName = institute?.name ?: "BatchFee Institute"
            val instCode = institute?.instituteCode ?: "N/A"
            val instPhone = institute?.phone ?: ""
            val logoUri = institute?.profilePhotoUri
            val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
            val issuedDate = df.format(Date())
            val expiryDate = df.format(Date(System.currentTimeMillis() + 365L * 86400000))

            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PremiumStudentIdCard(
                    student = s,
                    instituteName = instName,
                    instituteCode = instCode,
                    institutePhone = instPhone,
                    logoUri = logoUri,
                    issuedDate = issuedDate,
                    expiryDate = expiryDate,
                    context = context,
                )

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    generateProfessionalIdCardPdf(context, s, institute, issuedDate, expiryDate)
                                }
                                openPdf(context, file)
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) { Icon(Icons.Filled.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Print", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    generateProfessionalIdCardPdf(context, s, institute, issuedDate, expiryDate)
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply { setType("application/pdf"); putExtra(Intent.EXTRA_STREAM, uri); setPackage("com.whatsapp") }
                                if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                                else android.widget.Toast.makeText(context, "WhatsApp not installed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366))
                    ) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("WhatsApp", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PremiumStudentIdCard(
    student: StudentEntity,
    instituteName: String,
    instituteCode: String,
    institutePhone: String,
    logoUri: String?,
    issuedDate: String,
    expiryDate: String,
    context: Context,
) {
    val navy = Color(0xFF0B1F3A)
    val navyMid = Color(0xFF123C6A)
    val ink = Color(0xFF10233F)
    val softInk = Color(0xFF64748B)
    val photoRequest = remember(student.photoUri) {
        ImageRequest.Builder(context)
            .data(FirebaseStorageImageUploadHelper.displaySource(context, student.photoUri))
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .padding(3.dp)
            .drawBehind {
                drawRoundRect(
                    color = Cyan.copy(alpha = 0.14f),
                    topLeft = Offset(-6f, -6f),
                    size = Size(size.width + 12f, size.height + 12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(26f, 26f),
                )
            },
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val leftPanel = Path().apply {
                        moveTo(0f, height * 0.23f)
                        lineTo(width * 0.31f, height * 0.23f)
                        lineTo(width * 0.20f, height)
                        lineTo(0f, height)
                        close()
                    }
                    drawPath(leftPanel, Color(0xFFF0F9FF))
                    drawCircle(
                        color = Color(0xFFE0F2FE),
                        radius = width * 0.19f,
                        center = Offset(width * 0.92f, height * 0.83f),
                    )
                    drawCircle(
                        color = Color(0xFFDBEAFE).copy(alpha = 0.7f),
                        radius = width * 0.08f,
                        center = Offset(width * 0.75f, height * 0.89f),
                    )
                    drawLine(
                        color = Cyan.copy(alpha = 0.65f),
                        start = Offset(width * 0.45f, height * 0.32f),
                        end = Offset(width * 0.91f, height * 0.32f),
                        strokeWidth = 2f,
                    )
                }

                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .background(Brush.horizontalGradient(listOf(navy, navyMid, navy))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!logoUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = FirebaseStorageImageUploadHelper.displaySource(context, logoUri),
                                    contentDescription = "Institute logo",
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Icon(Icons.Filled.School, null, tint = navyMid, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = instituteName.uppercase(),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                letterSpacing = 0.25.sp,
                            )
                            Text(
                                text = listOf(instituteCode, institutePhone).filter { it.isNotBlank() }.joinToString("  |  "),
                                color = Color.White.copy(alpha = 0.70f),
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Cyan.copy(alpha = 0.18f))
                                .border(1.dp, Cyan.copy(alpha = 0.60f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("STUDENT ID", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(84.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(navy)
                                .border(2.dp, Cyan.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                                .padding(3.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!student.photoUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = photoRequest,
                                    contentDescription = "Student photo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    student.fullName.take(1).uppercase(),
                                    color = softInk,
                                    fontSize = 31.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }

                        Spacer(Modifier.width(13.dp))
                        Column(
                            modifier = Modifier.fillMaxHeight().weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                                Spacer(Modifier.width(5.dp))
                                Text("ACTIVE STUDENT", color = Color(0xFF15803D), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                student.fullName,
                                color = ink,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "ID  •  ${student.studentCode}",
                                color = softInk,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.45.sp,
                            )
                            Spacer(Modifier.height(9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                IdMetric("CLASS", student.className ?: "N/A", Modifier.weight(1f))
                                IdMetric("BLOOD", student.bloodGroup ?: "N/A", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("GUARDIAN  ${student.guardianName ?: student.guardianPhone ?: "N/A"}", color = softInk, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(Color(0xFFF8FAFC))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("ISSUED  $issuedDate", color = softInk, fontSize = 7.sp, fontWeight = FontWeight.SemiBold)
                            Text("VALID UNTIL  $expiryDate", color = softInk, fontSize = 7.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Filled.Verified, null, tint = Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("BATCHFEE VERIFIED", color = navyMid, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
                        Spacer(Modifier.width(9.dp))
                        Canvas(Modifier.width(47.dp).height(17.dp)) {
                            val pattern = listOf(1, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 1, 1)
                            val barWidth = size.width / pattern.size
                            pattern.forEachIndexed { index, bit ->
                                if (bit == 1) drawRect(navy, Offset(index * barWidth, 0f), Size(barWidth * 0.62f, size.height))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFFF1F5F9))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(label, color = Color(0xFF64748B), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(value, color = Color(0xFF10233F), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IdInfoRow(icon: String, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color(0xFF94A3B8), fontSize = 9.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp))
        Text(value, color = Color(0xFF0F172A), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun openPdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
}

// ═══════════════════════════════════════
//  PROFESSIONAL PDF — shapes & patterns
// ═══════════════════════════════════════
private suspend fun generateProfessionalIdCardPdf(
    context: Context,
    student: StudentEntity,
    institute: InstituteEntity?,
    issuedDate: String,
    expiryDate: String,
): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(544, 343, 1).create())
    val canvas = page.canvas
    val width = 544f
    val height = 343f

    val navy = AndroidColor.rgb(11, 31, 58)
    val navyMid = AndroidColor.rgb(18, 60, 106)
    val cyan = AndroidColor.rgb(34, 211, 238)
    val ink = AndroidColor.rgb(16, 35, 63)
    val muted = AndroidColor.rgb(100, 116, 139)
    val paleBlue = AndroidColor.rgb(240, 249, 255)
    val paleGray = AndroidColor.rgb(248, 250, 252)
    val white = AndroidColor.WHITE
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 17f; isFakeBoldText = true }
    val smallHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(185, 255, 255, 255); textSize = 8f }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 7f; isFakeBoldText = true; letterSpacing = 0.08f }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 11f; isFakeBoldText = true }

    canvas.drawColor(white)
    fill.color = paleBlue
    canvas.drawCircle(width * 0.95f, height * 0.80f, 102f, fill)
    fill.color = AndroidColor.rgb(219, 234, 254)
    canvas.drawCircle(width * 0.76f, height * 0.92f, 42f, fill)
    fill.color = navy
    canvas.drawRect(0f, 0f, width, 65f, fill)
    fill.color = navyMid
    val headerShape = android.graphics.Path().apply {
        moveTo(width * 0.60f, 0f)
        lineTo(width, 0f)
        lineTo(width, 65f)
        lineTo(width * 0.46f, 65f)
        close()
    }
    canvas.drawPath(headerShape, fill)
    fill.color = cyan
    canvas.drawRect(0f, 63f, width, 65f, fill)

    val logoRect = RectF(14f, 15f, 50f, 51f)
    val logoPath = android.graphics.Path().apply { addRoundRect(logoRect, 8f, 8f, android.graphics.Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(logoPath)
    fill.color = white
    canvas.drawRect(logoRect, fill)
    loadIdCardBitmap(context, institute?.profilePhotoUri)?.let { logo ->
        drawCenterCroppedBitmap(canvas, logo, logoRect)
        logo.recycle()
    } ?: run {
        val monogram = (institute?.name ?: "B").trim().take(1).uppercase()
        val monogramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navyMid
            textSize = 20f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(monogram, logoRect.centerX(), logoRect.centerY() + 7f, monogramPaint)
    }
    canvas.restore()

    val instituteName = institute?.name?.uppercase() ?: "BATCHFEE INSTITUTE"
    canvas.drawText(fitPdfText(instituteName, headerPaint, 285f), 62f, 33f, headerPaint)
    val instituteMeta = listOf(institute?.instituteCode, institute?.phone).filterNotNull().filter { it.isNotBlank() }.joinToString("  |  ")
    canvas.drawText(fitPdfText(instituteMeta, smallHeaderPaint, 285f), 62f, 49f, smallHeaderPaint)
    val cardLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 8f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT; letterSpacing = 0.08f }
    canvas.drawText("STUDENT IDENTITY CARD", width - 16f, 35f, cardLabelPaint)

    val photoFrame = RectF(17f, 84f, 125f, 250f)
    fill.color = navy
    canvas.drawRoundRect(photoFrame, 13f, 13f, fill)
    val photoInner = RectF(21f, 88f, 121f, 246f)
    val photoPath = android.graphics.Path().apply { addRoundRect(photoInner, 10f, 10f, android.graphics.Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(photoPath)
    fill.color = AndroidColor.rgb(226, 232, 240)
    canvas.drawRect(photoInner, fill)
    loadIdCardBitmap(context, student.photoUri)?.let { photo ->
        drawCenterCroppedBitmap(canvas, photo, photoInner)
        photo.recycle()
    } ?: run {
        val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 43f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(student.fullName.trim().take(1).uppercase(), photoInner.centerX(), photoInner.centerY() + 15f, initialPaint)
    }
    canvas.restore()

    val contentX = 151f
    val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(21, 128, 61); textSize = 8f; isFakeBoldText = true; letterSpacing = 0.1f }
    fill.color = AndroidColor.rgb(34, 197, 94)
    canvas.drawCircle(contentX + 3f, 95f, 3f, fill)
    canvas.drawText("ACTIVE STUDENT", contentX + 12f, 98f, activePaint)
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 22f; isFakeBoldText = true }
    canvas.drawText(fitPdfText(student.fullName, namePaint, 345f), contentX, 126f, namePaint)
    val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 10f; isFakeBoldText = true; letterSpacing = 0.08f }
    canvas.drawText("ID  -  ${student.studentCode}", contentX, 144f, idPaint)

    val metricTop = 161f
    val metrics = listOf("CLASS" to (student.className ?: "N/A"), "BLOOD" to (student.bloodGroup ?: "N/A"))
    metrics.forEachIndexed { index, (label, value) ->
        val left = contentX + index * 143f
        val metricRect = RectF(left, metricTop, left + 133f, metricTop + 42f)
        fill.color = AndroidColor.rgb(241, 245, 249)
        canvas.drawRoundRect(metricRect, 8f, 8f, fill)
        canvas.drawText(label, left + 10f, metricTop + 15f, labelPaint)
        canvas.drawText(fitPdfText(value, valuePaint, 112f), left + 10f, metricTop + 31f, valuePaint)
    }
    canvas.drawText("GUARDIAN", contentX, 222f, labelPaint)
    canvas.drawText(fitPdfText(student.guardianName ?: student.guardianPhone ?: "N/A", valuePaint, 285f), contentX, 239f, valuePaint)

    val footerTop = 274f
    fill.color = paleGray
    canvas.drawRect(0f, footerTop, width, height, fill)
    val footerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(226, 232, 240); strokeWidth = 1f }
    canvas.drawLine(0f, footerTop, width, footerTop, footerLine)
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 7f; isFakeBoldText = true; letterSpacing = 0.05f }
    canvas.drawText("ISSUED  $issuedDate", 18f, 297f, footerPaint)
    canvas.drawText("VALID UNTIL  $expiryDate", 18f, 311f, footerPaint)
    val verifiedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navyMid; textSize = 7f; isFakeBoldText = true; letterSpacing = 0.08f }
    canvas.drawText("BATCHFEE VERIFIED", 202f, 304f, verifiedPaint)
    fill.color = cyan
    canvas.drawCircle(191f, 301f, 6f, fill)
    fill.color = navy
    canvas.drawCircle(191f, 301f, 2.5f, fill)
    val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 7f; textAlign = Paint.Align.CENTER }
    canvas.drawLine(340f, 304f, 415f, 304f, footerLine)
    canvas.drawText("Authorised Signatory", 377f, 316f, signaturePaint)
    val barcodePattern = listOf(1, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0)
    val barWidth = 64f / barcodePattern.size
    barcodePattern.forEachIndexed { index, bit ->
        if (bit == 1) {
            fill.color = navy
            canvas.drawRect(457f + index * barWidth, 289f, 457f + index * barWidth + barWidth * 0.62f, 316f, fill)
        }
    }

    document.finishPage(page)
    val file = File(context.cacheDir, "id_card_${student.studentCode}.pdf")
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file
}

private fun fitPdfText(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end -= 1
    return value.take(end) + suffix
}

private suspend fun loadIdCardBitmap(context: Context, source: String?): android.graphics.Bitmap? {
    if (source.isNullOrBlank()) return null
    val resolvedSource = FirebaseStorageImageUploadHelper.resolveForDirectRead(context, source) ?: return null
    return try {
        val uri = Uri.parse(resolvedSource)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val connection = URL(resolvedSource).openConnection() as HttpURLConnection
                try {
                    connection.doInput = true
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }
            "content", "file" -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            else -> BitmapFactory.decodeFile(resolvedSource)
        }
    } catch (_: Exception) {
        null
    }
}

private fun drawCenterCroppedBitmap(canvas: android.graphics.Canvas, bitmap: android.graphics.Bitmap, target: RectF) {
    if (bitmap.width <= 0 || bitmap.height <= 0) return
    val sourceAspect = bitmap.width.toFloat() / bitmap.height
    val targetAspect = target.width() / target.height()
    val source = if (sourceAspect > targetAspect) {
        val cropWidth = (bitmap.height * targetAspect).toInt()
        val left = (bitmap.width - cropWidth) / 2
        android.graphics.Rect(left, 0, left + cropWidth, bitmap.height)
    } else {
        val cropHeight = (bitmap.width / targetAspect).toInt()
        val top = (bitmap.height - cropHeight) / 2
        android.graphics.Rect(0, top, bitmap.width, top + cropHeight)
    }
    canvas.drawBitmap(bitmap, source, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}

private suspend fun generateLegacyIdCardPdf(context: Context, student: StudentEntity, institute: InstituteEntity?, issuedDate: String, expiryDate: String): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(340, 544, 1).create())
    val canvas = page.canvas; val w = 340f; val h = 544f

    val darkBlue = AndroidColor.rgb(15, 43, 91)
    val textDark = AndroidColor.rgb(15, 23, 42); val textMuted = AndroidColor.rgb(100, 116, 139)
    val white = AndroidColor.WHITE; val grayBg = AndroidColor.rgb(226, 232, 240)

    val fill = Paint().apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = AndroidColor.rgb(203, 213, 225) }
    val boldStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = darkBlue }

    canvas.drawColor(white)

    // Background shapes
    fill.color = AndroidColor.rgb(241, 245, 249)
    val tri = android.graphics.Path().apply { moveTo(w, 0f); lineTo(w, h * 0.32f); lineTo(w * 0.52f, 0f); close() }; canvas.drawPath(tri, fill)
    fill.color = AndroidColor.rgb(226, 232, 240); canvas.drawCircle(w * 0.85f, h * 0.9f, 35f, fill)
    fill.color = AndroidColor.rgb(248, 250, 252); canvas.drawCircle(w * 0.07f, h * 0.06f, 30f, fill)
    for (gx in 0..4) for (gy in 0..2) { fill.color = AndroidColor.argb(85, 203, 213, 225); canvas.drawCircle(w * (0.81f + gx * 0.03f), h * (0.85f + gy * 0.03f), 2f, fill) }

    // Header
    fill.color = darkBlue; canvas.drawRect(0f, 0f, w, h * 0.30f, fill)
    fill.color = AndroidColor.argb(12, 255, 255, 255)
    for (i in -5..20) { val x = i * 34f; canvas.drawLine(x, 0f, x - (h * 0.30f) * 0.5f, h * 0.30f, Paint().apply { this.color = fill.color; strokeWidth = 1.5f }) }
    fill.color = AndroidColor.argb(45, 30, 75, 122)
    val wave = android.graphics.Path().apply { moveTo(0f, h * 0.30f); lineTo(0f, h * 0.30f - 5f)
        for (x in 0..w.toInt() step 18) lineTo(x.toFloat(), (h * 0.30f - 5f - 2.5f * kotlin.math.sin(x / 27f)).toFloat())
        lineTo(w, h * 0.30f); close() }; canvas.drawPath(wave, fill)

    // Institute logo
    val logoUri = institute?.profilePhotoUri; var textStartX = 20f
    loadIdCardBitmap(context, logoUri)?.let { bmp ->
        canvas.drawBitmap(android.graphics.Bitmap.createScaledBitmap(bmp, 48, 48, true), 16f, 22f, null)
        bmp.recycle()
        textStartX = 76f
    }

    val hdr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; isFakeBoldText = true }
    hdr.textSize = 17f; canvas.drawText((institute?.name ?: "BatchFee").uppercase(), textStartX, 48f, hdr)
    hdr.textSize = 9f; hdr.color = AndroidColor.argb(175, 255, 255, 255)
    canvas.drawText("${institute?.instituteCode ?: ""}  |  ${institute?.phone ?: ""}", textStartX, 68f, hdr)
    fill.color = AndroidColor.rgb(34, 211, 238); canvas.drawRect(textStartX, 76f, textStartX + 90f, 78f, fill)
    hdr.textAlign = Paint.Align.CENTER; hdr.textSize = 10f; hdr.color = AndroidColor.argb(195, 255, 255, 255)
    canvas.drawText("STUDENT IDENTITY CARD", w / 2f, h * 0.30f - 14f, hdr); hdr.textAlign = Paint.Align.LEFT

    // Photo with ring
    val pcx = w / 2f; val pcy = h * 0.30f + 22f
    fill.color = AndroidColor.argb(35, 34, 211, 238); canvas.drawCircle(pcx, pcy, 52f, fill)
    fill.color = AndroidColor.argb(25, 59, 130, 246); canvas.drawCircle(pcx, pcy, 48f, fill)
    fill.color = white; canvas.drawCircle(pcx, pcy, 42f, fill)
    fill.color = grayBg; canvas.drawCircle(pcx, pcy, 38f, fill)
    val nit = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = textMuted; isFakeBoldText = true; textSize = 30f }
    canvas.drawText(student.fullName.take(1).uppercase(), pcx, pcy + 11f, nit)

    // Name + ID
    var y = pcy + 58f
    nit.textSize = 17f; nit.color = textDark; canvas.drawText(student.fullName, pcx, y, nit)
    y += 20f; nit.textSize = 10f; nit.color = textMuted; nit.isFakeBoldText = false
    canvas.drawText("ID: ${student.studentCode}", pcx, y, nit); y += 14f

    // Info rows
    val rows = listOf("Class" to (student.className ?: "N/A"), "Blood Group" to (student.bloodGroup ?: "N/A"), "Phone" to (student.phone ?: "N/A"), "Guardian" to (student.guardianName ?: "N/A"))
    val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = textMuted }
    val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = textDark; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    rows.forEach { (l, v) -> canvas.drawText(l, 28f, y, lp); canvas.drawText(v, w - 28f, y, vp); y += 20f }
    vp.textAlign = Paint.Align.LEFT; vp.textSize = 9f; vp.color = textMuted
    val addr = student.address?.take(42) ?: ""; if (addr.isNotBlank()) { canvas.drawText(addr, 28f, y, vp); y += 18f }
    vp.textAlign = Paint.Align.RIGHT

    // Barcode
    val barY = h - 62f; val bs = listOf(1,0,1,1,0,0,1,0,1,1,0,1,0,0,1,1,0,1); val bw = 60f / bs.size
    bs.forEachIndexed { i, v -> if (v == 1) { fill.color = textDark; canvas.drawRect(28f + i*bw, barY, 28f + i*bw + bw*0.6f, barY + 18f, fill) } }

    // Dates
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f; color = textMuted }
    canvas.drawText("ISSUED: $issuedDate", 28f, h - 36f, sp); canvas.drawText("VALID: $expiryDate", 28f, h - 24f, sp)

    // Signature
    val sx = w - 110f; canvas.drawLine(sx, h - 40f, sx + 80f, h - 40f, stroke); canvas.drawText("Authorised Signatory", sx, h - 30f, sp)

    // Emblem
    val scx = w - 40f; val scy = h - 52f
    fill.color = AndroidColor.rgb(239, 246, 255); canvas.drawCircle(scx, scy, 18f, fill)
    canvas.drawCircle(scx, scy, 18f, boldStroke); fill.color = darkBlue; canvas.drawCircle(scx, scy, 4f, fill)

    document.finishPage(page)
    val file = File(context.cacheDir, "id_card_${student.studentCode}.pdf")
    file.outputStream().use { document.writeTo(it) }; document.close()
    return file
}
