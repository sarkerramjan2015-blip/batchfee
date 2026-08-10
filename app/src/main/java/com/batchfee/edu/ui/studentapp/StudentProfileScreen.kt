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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
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
    val sid = StudentSessionManager.studentId.value ?: ""
    val iid = StudentSessionManager.instituteId.value ?: ""
    val sessionName = StudentSessionManager.studentName.value ?: ""
    val sessionCode = StudentSessionManager.studentCode.value ?: ""

    var fullName by remember { mutableStateOf(sessionName) }
    var studentCode by remember { mutableStateOf(sessionCode) }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var guardianName by remember { mutableStateOf("") }
    var guardianPhone by remember { mutableStateOf("") }
    var bloodGroup by remember { mutableStateOf("") }
    var instituteName by remember { mutableStateOf("") }
    var institutePhone by remember { mutableStateOf("") }

    DisposableEffect(iid, sid) {
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()

        listeners += fs.collection("institutes").document(iid).collection("students").document(sid)
            .addSnapshotListener { snap, _ ->
                snap?.let {
                    fullName = it.getString("fullName") ?: fullName
                    studentCode = it.getString("studentCode") ?: studentCode
                    phone = it.getString("phone") ?: ""
                    email = it.getString("email") ?: ""
                    address = it.getString("address") ?: ""
                    className = it.getString("className") ?: ""
                    guardianName = it.getString("guardianName") ?: ""
                    guardianPhone = it.getString("guardianPhone") ?: ""
                    bloodGroup = it.getString("bloodGroup") ?: ""
                }
            }
        listeners += fs.collection("institutes").document(iid)
            .addSnapshotListener { snap, _ ->
                snap?.let {
                    instituteName = it.getString("name") ?: it.getString("instituteName") ?: ""
                    institutePhone = it.getString("phone") ?: ""
                }
            }
        onDispose { listeners.forEach { it.remove() } }
    }

    Scaffold(
        containerColor = PsBg,
        topBar = { TopAppBar(title = { Text("Profile", color = PsWhite, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PsBg)) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(PsBg).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Avatar
            Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(PsCyan, PsBlue))), contentAlignment = Alignment.Center) {
                Text(fullName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(fullName, color = PsWhite, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Text("ID: $studentCode", color = PsCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (className.isNotBlank()) Text(className, color = PsMuted, fontSize = 12.sp)

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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PsMuted, fontSize = 13.sp)
        Text(value, color = PsWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
