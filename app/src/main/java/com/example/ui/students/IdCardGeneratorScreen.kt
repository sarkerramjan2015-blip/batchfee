package com.example.ui.students

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.R
import com.example.data.database.AppDatabase
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.InstituteEntity
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
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
private val AccentGreen = Color(0xFF22C55E)

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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search student by name or ID...", color = TextMuted.copy(alpha = 0.5f)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                    focusedBorderColor = Cyan, unfocusedBorderColor = BorderSub,
                    focusedContainerColor = CardBgAlt, unfocusedContainerColor = CardBgAlt
                )
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered) { student ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onNavigateToPreview("student", student.id) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSub)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(ElectricBlue.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!student.photoUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = Uri.parse(student.photoUri),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Filled.Person, null, tint = Cyan, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardPreviewScreen(db: AppDatabase, type: String, studentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var student by remember { mutableStateOf<StudentEntity?>(null) }
    var institute by remember { mutableStateOf<InstituteEntity?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(studentId) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            val s = withContext(Dispatchers.IO) { db.studentDao().getStudentById(studentId, instId).firstOrNull() }
            val inst = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
            student = s
            institute = inst
        }
        isLoading = false
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("ID Card Preview", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan)
            }
        } else if (student == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Student not found.", color = TextMuted)
            }
        } else {
            val s = student!!
            val instName = institute?.name ?: "BatchFee Institute"
            val instCode = institute?.instituteCode ?: "N/A"
            val instPhone = institute?.phone ?: ""

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ID Card Preview
                Card(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.6f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.35f)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF1E3A5F),
                                            Color(0xFF2563EB),
                                            Color(0xFF1E3A5F)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(instName.uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("STUDENT IDENTITY CARD", color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        // Photo
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .offset(y = (-20).dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!s.photoUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(Uri.parse(s.photoUri)).crossfade(true).build(),
                                    contentDescription = "Photo",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFFE2E8F0)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(s.fullName.take(1).uppercase(), color = Color(0xFF64748B), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        // Details
                        Column(
                            modifier = Modifier
                                .weight(0.65f)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(s.fullName, color = Color(0xFF1E293B), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("ID: ${s.studentCode}", color = Color(0xFF64748B), fontSize = 10.sp)
                            idCardRow("Batch", instCode, labelColor = AndroidColor.rgb(71, 85, 105), valueColor = AndroidColor.rgb(30, 41, 59))
                            idCardRow("Phone", s.phone ?: "N/A", labelColor = AndroidColor.rgb(71, 85, 105), valueColor = AndroidColor.rgb(30, 41, 59))
                            idCardRow("Blood", s.bloodGroup ?: "N/A", labelColor = AndroidColor.rgb(71, 85, 105), valueColor = AndroidColor.rgb(30, 41, 59))
                            if (!s.guardianName.isNullOrBlank()) {
                                idCardRow("Guardian", s.guardianName, labelColor = AndroidColor.rgb(71, 85, 105), valueColor = AndroidColor.rgb(30, 41, 59))
                            }
                            if (!s.address.isNullOrBlank()) {
                                Text(
                                    s.address.take(40),
                                    color = Color(0xFF64748B),
                                    fontSize = 8.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Print button
                Button(
                    onClick = {
                        val file = generateIdCardPdf(context, s, instName, instCode, instPhone)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Print ID Card", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))

                // WhatsApp button
                Button(
                    onClick = {
                        var handled = false
                        try {
                            val pdfFile = generateIdCardPdf(context, s, instName, instCode, instPhone)
                            val pdfUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
                            val shareIntent = Intent(Intent.ACTION_SEND)
                            shareIntent.type = "application/pdf"
                            shareIntent.putExtra(Intent.EXTRA_STREAM, pdfUri)
                            shareIntent.putExtra(Intent.EXTRA_TEXT, "ID Card - ${s.fullName}")
                            shareIntent.setPackage("com.whatsapp")
                            if (shareIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(shareIntent)
                                handled = true
                            }
                        } catch (_: Exception) { }
                        if (!handled) {
                            Toast.makeText(context, "WhatsApp not installed. Use Print to share.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(Icons.Filled.Phone, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share on WhatsApp", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun idCardRow(label: String, value: String, labelColor: Int, valueColor: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(labelColor), fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color(valueColor), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun generateIdCardPdf(context: Context, student: StudentEntity, instituteName: String, instituteCode: String, institutePhone: String): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(340, 544, 1).create())
    val canvas = page.canvas

    val white = AndroidColor.WHITE
    val darkBlue = AndroidColor.rgb(30, 58, 95)
    val blue = AndroidColor.rgb(37, 99, 235)
    val textDark = AndroidColor.rgb(30, 41, 59)
    val textMuted = AndroidColor.rgb(71, 85, 105)
    val grayBg = AndroidColor.rgb(226, 232, 240)

    val fill = Paint().apply { style = Paint.Style.FILL }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = textDark }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = textDark; isFakeBoldText = true }
    val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = white; isFakeBoldText = true }

    canvas.drawColor(white)

    // Header
    fill.color = darkBlue
    canvas.drawRect(0f, 0f, 340f, 140f, fill)
    fill.color = blue
    canvas.drawRect(0f, 120f, 340f, 140f, fill)

    whiteText.textSize = 18f
    whiteText.textAlign = Paint.Align.CENTER
    canvas.drawText(instituteName.uppercase(), 170f, 48f, whiteText)
    whiteText.textSize = 9f
    canvas.drawText("STUDENT IDENTITY CARD", 170f, 68f, whiteText)
    whiteText.textAlign = Paint.Align.LEFT

    // Photo placeholder
    fill.color = grayBg
    canvas.drawCircle(170f, 170f, 38f, fill)
    fill.color = white
    canvas.drawCircle(170f, 170f, 35f, fill)

    // Draw initial if no photo
    bold.textSize = 28f
    bold.textAlign = Paint.Align.CENTER
    bold.color = textMuted
    canvas.drawText(student.fullName.take(1).uppercase(), 170f, 178f, bold)
    bold.textAlign = Paint.Align.LEFT
    bold.color = textDark

    // Student details
    var y = 230f
    bold.textSize = 17f
    canvas.drawText(student.fullName, 40f, y, bold)
    y += 22f
    text.textSize = 10f
    text.color = textMuted
    canvas.drawText("ID: ${student.studentCode}", 40f, y, text)
    y += 28f

    // Info rows
    val rows = listOf(
        "Institute" to instituteCode,
        "Phone" to (student.phone ?: "N/A"),
        "Blood Group" to (student.bloodGroup ?: "N/A"),
        "Guardian" to (student.guardianName ?: "N/A"),
        "Class" to (student.className ?: "N/A")
    )
    rows.forEach { (label, value) ->
        text.color = textMuted
        canvas.drawText(label, 40f, y, text)
        text.color = textDark
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(value, 300f, y, text)
        text.textAlign = Paint.Align.LEFT
        y += 22f
    }

    // Footer
    y = 490f
    text.color = textMuted
    text.textSize = 8f
    text.textAlign = Paint.Align.CENTER
    canvas.drawText("Issued by $instituteName", 170f, y, text)
    canvas.drawText("Contact: $institutePhone", 170f, y + 14f, text)
    text.textAlign = Paint.Align.LEFT

    document.finishPage(page)

    val file = File(context.cacheDir, "id_card_${student.studentCode}.pdf")
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file
}
