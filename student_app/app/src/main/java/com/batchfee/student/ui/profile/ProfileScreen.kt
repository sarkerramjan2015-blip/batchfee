package com.batchfee.student.ui.profile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.Institute
import com.batchfee.student.data.models.Student
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    var student by remember { mutableStateOf<Student?>(null) }
    var institute by remember { mutableStateOf<Institute?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val repo = remember { StudentFirestoreRepository() }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            student = repo.getStudent(iid, sid)
            institute = repo.getInstitute(iid)
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                val s = student
                if (s != null) {
                    // Avatar + Name
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PrimaryBlue)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.2f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = s.fullName.take(2).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 28.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(s.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Text("ID: ${s.studentCode}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            if (s.className != null) {
                                Text(s.className, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Personal Info
                    SectionHeader("Personal Information")
                    InfoCard(
                        items = listOf(
                            "Gender" to (s.gender ?: "N/A"),
                            "Blood Group" to (s.bloodGroup ?: "N/A"),
                            "Date of Birth" to (s.dateOfBirthMs?.let { formatDate(it) } ?: "N/A"),
                            "Phone" to (s.phone ?: "N/A"),
                            "Email" to (s.email ?: "N/A"),
                            "Address" to (s.address ?: "N/A"),
                            "School/College" to (s.schoolName ?: "N/A")
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    // Guardian Info
                    SectionHeader("Guardian Information")
                    InfoCard(
                        items = listOf(
                            "Guardian Name" to (s.guardianName ?: "N/A"),
                            "Guardian Phone" to (s.guardianPhone ?: "N/A"),
                            "Guardian Email" to (s.guardianEmail ?: "N/A"),
                            "Emergency Contact" to (s.emergencyContact ?: "N/A")
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    // Institute Info
                    if (institute != null) {
                        SectionHeader("Institute Information")
                        val inst = institute!!
                        InfoCard(
                            items = listOf(
                                "Institute" to inst.name,
                                "Owner" to (inst.ownerName ?: "N/A"),
                                "Phone" to (inst.phone ?: "N/A"),
                                "WhatsApp" to (inst.whatsappNumber ?: "N/A"),
                                "Address" to (inst.address ?: "N/A"),
                                "Code" to (inst.instituteCode ?: "N/A")
                            )
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Profile not found", color = TextSecondaryLight)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun InfoCard(items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            items.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = TextSecondaryLight, fontSize = 13.sp)
                    Text(
                        value,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (index < items.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

private fun formatDate(ms: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return sdf.format(Date(ms))
}
