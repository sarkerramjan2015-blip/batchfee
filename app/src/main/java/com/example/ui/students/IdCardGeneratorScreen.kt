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
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
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
                                    AsyncImage(model = Uri.parse(student.photoUri), contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
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
                // ── Neon glow around card ──
                Box(Modifier.fillMaxWidth().aspectRatio(1.56f).padding(2.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().drawBehind {
                        drawRoundRect(Color.Cyan.copy(alpha = 0.10f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f), size = Size(size.width + 16f, size.height + 16f), topLeft = Offset(-8f, -8f))
                        drawRoundRect(Color(0xFF6366F1).copy(alpha = 0.06f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f), size = Size(size.width + 24f, size.height + 24f), topLeft = Offset(-12f, -12f))
                    })

                    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 18.dp)) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            // ── BACKGROUND SHAPES ──
                            Canvas(Modifier.fillMaxSize()) {
                                val cW = size.width; val cH = size.height
                                val tri = Path().apply { moveTo(cW, 0f); lineTo(cW, cH * 0.32f); lineTo(cW * 0.52f, 0f); close() }
                                drawPath(tri, Color(0xFFF1F5F9))
                                drawOval(Color(0xFFE2E8F0).copy(alpha = 0.35f), topLeft = Offset(cW * 0.58f, cH * 0.80f), size = Size(cW * 0.34f, cH * 0.18f))
                                drawCircle(Color(0xFFF8FAFC), radius = cW * 0.11f, center = Offset(cW * 0.07f, cH * 0.07f))
                                for (gx in 0..4) for (gy in 0..2) {
                                    drawCircle(Color(0xFFCBD5E1).copy(alpha = 0.30f), radius = 2f, center = Offset(cW * (0.81f + gx * 0.03f), cH * (0.84f + gy * 0.04f)))
                                }
                            }

                            Column(Modifier.fillMaxSize()) {
                                // ── HEADER with diagonal stripes ──
                                Box(Modifier.fillMaxWidth().fillMaxHeight(0.30f).background(Brush.horizontalGradient(listOf(Color(0xFF0F2B5B), Color(0xFF1A4F8A), Color(0xFF0F2B5B))))) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        for (i in -5..20) drawLine(Color.White.copy(alpha = 0.035f), Offset(i * 32f, 0f), Offset(i * 32f - size.height * 0.5f, size.height), strokeWidth = 1.5f)
                                        val wave = Path().apply { moveTo(0f, size.height); lineTo(0f, size.height - 6f)
                                            for (x in 0..size.width.toInt() step 20) lineTo(x.toFloat(), size.height - 6f - 3f * kotlin.math.sin(x.toFloat() / 30f))
                                            lineTo(size.width, size.height); close() }
                                        drawPath(wave, Color(0xFF1E4B7A).copy(alpha = 0.25f))
                                    }
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (!logoUri.isNullOrBlank()) {
                                            AsyncImage(model = logoUri, contentDescription = "Logo", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentScale = ContentScale.Fit)
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(instName.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("$instCode  |  $instPhone", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.width(120.dp).height(1.5.dp).background(Cyan.copy(alpha = 0.55f)))
                                        }
                                    }
                                }

                                // ── PHOTO with sweep ring ──
                                Box(Modifier.fillMaxWidth().offset(y = (-40).dp).height(80.dp), contentAlignment = Alignment.TopCenter) {
                                    Box(Modifier.size(86.dp).clip(CircleShape).background(Brush.sweepGradient(listOf(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF6366F1), Color(0xFF22D3EE)))), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(78.dp).clip(CircleShape).background(Color.White).padding(4.dp).clip(CircleShape).background(Color(0xFFE2E8F0)), contentAlignment = Alignment.Center) {
                                            if (!s.photoUri.isNullOrBlank()) {
                                                AsyncImage(model = ImageRequest.Builder(context).data(Uri.parse(s.photoUri)).crossfade(true).build(), contentDescription = "Photo", modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                            } else {
                                                Text(s.fullName.take(1).uppercase(), color = Color(0xFF64748B), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                // ── DETAILS ──
                                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp).offset(y = (-24).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(s.fullName, color = Color(0xFF0F172A), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, letterSpacing = 0.5.sp)
                                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                                        Spacer(Modifier.width(6.dp))
                                        Text("ID: ${s.studentCode}", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    IdInfoRow("📚", "Class", s.className ?: "N/A")
                                    IdInfoRow("🩸", "Blood", s.bloodGroup ?: "N/A")
                                    IdInfoRow("📞", "Phone", s.phone ?: "N/A")
                                    IdInfoRow("👤", "Guardian", s.guardianName ?: "N/A")
                                    if (!s.address.isNullOrBlank()) { Spacer(Modifier.height(2.dp)); Text(s.address.take(45), color = Color(0xFF64748B), fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    Spacer(Modifier.weight(1f))

                                    // Footer: barcode + dates
                                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                        Column {
                                            Text("ISSUED: $issuedDate", color = Color(0xFF94A3B8), fontSize = 7.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                                            Text("VALID: $expiryDate", color = Color(0xFF94A3B8), fontSize = 7.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                                        }
                                        Canvas(Modifier.width(72.dp).height(18.dp).offset(x = 4.dp)) {
                                            val bs = listOf(1,0,1,1,0,0,1,0,1,1,0,1,0,0,1,1,0,1); val bw = size.width / bs.size
                                            bs.forEachIndexed { i, v -> if (v == 1) drawRect(Color(0xFF1E293B), Offset(i*bw, 0f), Size(bw*0.65f, size.height)) }
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                        Column { Box(Modifier.width(80.dp).height(1.dp).background(Color(0xFFCBD5E1))); Text("Authorised Signatory", color = Color(0xFF94A3B8), fontSize = 7.sp, letterSpacing = 0.3.sp) }
                                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFEFF6FF)).padding(6.dp), contentAlignment = Alignment.Center) {
                                            Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFF1E3A5F), radius = 8f, style = Stroke(2f)); drawCircle(Color(0xFF3B82F6), radius = 3f) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { openPdf(context, generateProfessionalIdCardPdf(context, s, institute, issuedDate, expiryDate)) },
                        modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) { Icon(Icons.Filled.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Print", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = {
                            val file = generateProfessionalIdCardPdf(context, s, institute, issuedDate, expiryDate)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply { setType("application/pdf"); putExtra(Intent.EXTRA_STREAM, uri); setPackage("com.whatsapp") }
                            if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                            else android.widget.Toast.makeText(context, "WhatsApp not installed", android.widget.Toast.LENGTH_SHORT).show()
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
private fun generateProfessionalIdCardPdf(context: Context, student: StudentEntity, institute: InstituteEntity?, issuedDate: String, expiryDate: String): File {
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
    if (!logoUri.isNullOrBlank()) { try {
        val conn = (URL(logoUri).openConnection() as HttpURLConnection).apply { doInput = true; connectTimeout = 5000; readTimeout = 5000; connect() }
        val bmp = BitmapFactory.decodeStream(conn.inputStream); conn.inputStream.close(); conn.disconnect()
        if (bmp != null) { canvas.drawBitmap(android.graphics.Bitmap.createScaledBitmap(bmp, 48, 48, true), 16f, 22f, null); bmp.recycle(); textStartX = 76f }
    } catch (_: Exception) {} }

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
