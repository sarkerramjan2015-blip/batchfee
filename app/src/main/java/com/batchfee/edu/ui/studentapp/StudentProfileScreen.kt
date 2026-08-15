package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration

private val PsBg     = Color(0xFF07111F)
private val PsCard   = Color(0xFF0F172A)
private val PsStroke = Color(0xFF1E293B)
private val PsCyan   = Color(0xFF22D3EE)
private val PsBlue   = Color(0xFF3B82F6)
private val PsRed    = Color(0xFFEF4444)
private val PsWhite  = Color(0xFFF8FAFC)
private val PsMuted  = Color(0xFF94A3B8)
private val PsDim    = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentProfileScreen(onLogout: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val sessionName by StudentSessionManager.studentName.collectAsState()
    val sessionCode by StudentSessionManager.studentCode.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()

    var fullName by remember(studentId) { mutableStateOf(sessionName.orEmpty()) }
    var studentCode by remember(studentId) { mutableStateOf(sessionCode.orEmpty()) }
    var photoUri by remember(studentId) { mutableStateOf<String?>(null) }
    var phone by remember(studentId) { mutableStateOf("") }
    var email by remember(studentId) { mutableStateOf("") }
    var address by remember(studentId) { mutableStateOf("") }
    var className by remember(studentId) { mutableStateOf("") }
    var guardianName by remember(studentId) { mutableStateOf("") }
    var guardianPhone by remember(studentId) { mutableStateOf("") }
    var bloodGroup by remember(studentId) { mutableStateOf("") }
    var instituteName by remember(instituteId) { mutableStateOf("") }
    var institutePhone by remember(instituteId) { mutableStateOf("") }
    var syncError by remember(studentId, instituteId) { mutableStateOf<String?>(null) }

    fun reportListenerError(error: FirebaseFirestoreException?) {
        if (error != null) {
            syncError = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Live access is no longer available. Please sign in again."
            } else {
                "Live updates are paused. Check your connection."
            }
        }
    }

    DisposableEffect(instituteId, studentId) {
        if (instituteId.isBlank() || studentId.isBlank()) {
            onDispose { }
        } else {
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()

        listeners += fs.collection("institutes").document(instituteId).collection("students").document(studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                snap?.let {
                    fullName = it.getString("fullName") ?: fullName
                    studentCode = it.getString("studentCode") ?: studentCode
                    photoUri = it.getString("photoUri")
                    phone = it.getString("phone") ?: ""
                    email = it.getString("email") ?: ""
                    address = it.getString("address") ?: ""
                    className = it.getString("className") ?: ""
                    guardianName = it.getString("guardianName") ?: ""
                    guardianPhone = it.getString("guardianPhone") ?: ""
                    bloodGroup = it.getString("bloodGroup") ?: ""
                }
            }
        listeners += fs.collection("institutes").document(instituteId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                snap?.let {
                    instituteName = it.getString("name") ?: it.getString("instituteName") ?: ""
                    institutePhone = it.getString("phone") ?: ""
                }
            }
        onDispose { listeners.forEach { it.remove() } }
        }
    }

    Scaffold(
        containerColor = PsBg,
        topBar = { TopAppBar(title = { Text("Profile", color = PsWhite, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PsBg)) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(PsBg).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Avatar
            StudentProfileAvatar(photoUri = photoUri, fullName = fullName)
            Spacer(Modifier.height(12.dp))

            Text(fullName, color = PsWhite, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Text("ID: $studentCode", color = PsCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (className.isNotBlank()) Text(className, color = PsMuted, fontSize = 12.sp)

            syncError?.let { message ->
                Spacer(Modifier.height(12.dp))
                Surface(color = PsRed.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SyncProblem, null, tint = PsRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(message, color = PsRed, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Personal Info Card
            SectionCard("Personal Details") {
                if (phone.isNotBlank()) InfoRow("Phone", phone)
                if (email.isNotBlank()) InfoRow("Email", email)
                if (address.isNotBlank()) InfoRow("Address", address)
                if (bloodGroup.isNotBlank()) InfoRow("Blood Group", bloodGroup)
                if (guardianName.isNotBlank()) InfoRow("Guardian", guardianName)
                if (guardianPhone.isNotBlank()) InfoRow("Guardian Phone", guardianPhone)
            }

            Spacer(Modifier.height(12.dp))

            // Institute Info
            SectionCard("Institute") {
                if (instituteName.isNotBlank()) InfoRow("Name", instituteName)
                if (institutePhone.isNotBlank()) InfoRow("Phone", institutePhone)
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = onLogout, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PsRed.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PsRed)
            ) { Icon(Icons.Filled.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Logout", fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun StudentProfileAvatar(photoUri: String?, fullName: String) {
    val context = LocalContext.current
    Box(
        Modifier.size(80.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(PsCyan, PsBlue))),
        contentAlignment = Alignment.Center
    ) {
        if (photoUri.isNullOrBlank()) {
            Text(fullName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
        } else {
            AsyncImage(
                model = FirebaseStorageImageUploadHelper.displaySource(context, photoUri),
                contentDescription = "$fullName photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = PsCard), border = BorderStroke(1.dp, PsStroke)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = PsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = PsMuted, fontSize = 12.sp, modifier = Modifier.weight(0.34f))
        Text(
            value,
            color = PsWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.66f)
        )
    }
}
