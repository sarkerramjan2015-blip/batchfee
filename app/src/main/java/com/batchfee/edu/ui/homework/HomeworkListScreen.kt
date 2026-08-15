package com.batchfee.edu.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.WorkCloudSyncHelper
import com.batchfee.edu.data.models.HomeworkEntity
import com.batchfee.edu.data.repository.PermanentWorkPurgeRepository
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private val HwBg     = Color(0xFF07111F)
private val HwCard   = Color(0xFF0F172A)
private val HwStroke = Color(0xFF1E293B)
private val HwBlue   = Color(0xFF3B82F6)
private val HwGreen  = Color(0xFF22C55E)
private val HwRed    = Color(0xFFEF4444)
private val HwWhite  = Color(0xFFF8FAFC)
private val HwMuted  = Color(0xFF94A3B8)
private val HwDim    = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkListScreen(db: AppDatabase, onBack: () -> Unit, onAddHomework: () -> Unit) {
    val instId = SessionManager.currentInstituteId.value ?: ""
    var homeworks by remember { mutableStateOf<List<HomeworkEntity>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<HomeworkEntity?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(instId) {
        db.homeworkDao().getActive(instId).collect { homeworks = it }
    }

    // Backfill older local-only homework created before cloud publishing was
    // enforced. New homework is synced before it is saved locally.
    LaunchedEffect(instId) {
        if (instId.isNotBlank()) {
            val savedHomework = withContext(Dispatchers.IO) {
                db.homeworkDao().getActive(instId).first()
            }
            savedHomework.forEach { homework -> runCatching { WorkCloudSyncHelper.syncHomework(homework) } }
        }
    }

    val active = homeworks.filter { it.status == "active" }

    Scaffold(
        containerColor = HwBg,
        topBar = {
            TopAppBar(
                title = { Text("Homework", color = HwWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HwMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HwBg)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddHomework,
                containerColor = HwBlue,
                contentColor = Color.White,
                shape = CircleShape
            ) { Icon(Icons.Filled.Add, "Add") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(HwBg)) {
            // Stats row
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = HwCard), border = BorderStroke(1.dp, HwStroke)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatChip("${active.size}", "Active", HwGreen)
                    StatChip("${homeworks.size - active.size}", "Closed", HwDim)
                    StatChip("${homeworks.size}", "Total", HwBlue)
                }
            }

            if (homeworks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.Home, null, Modifier.size(48.dp), tint = HwDim); Spacer(Modifier.height(12.dp)); Text("No homework yet", color = HwMuted, fontSize = 16.sp); Spacer(Modifier.height(4.dp)); Text("Tap + to add your first homework", color = HwDim, fontSize = 13.sp) } }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(homeworks) { hw ->
                        val past = hw.dueDateMs != null && hw.dueDateMs < System.currentTimeMillis()
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = HwCard), border = BorderStroke(1.dp, HwStroke)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(HwBlue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Home, null, tint = HwBlue, modifier = Modifier.size(20.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(hw.title, color = HwWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        hw.subject?.let { Text(it, color = HwBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(8.dp), color = if (hw.status == "active") HwGreen.copy(alpha = 0.15f) else HwDim.copy(alpha = 0.15f)) {
                                            Text(if (hw.status == "active") "Active" else "Closed", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = if (hw.status == "active") HwGreen else HwDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        IconButton(onClick = { pendingDelete = hw }) {
                                            Icon(Icons.Filled.DeleteForever, "Delete homework permanently", tint = HwRed, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                                hw.className?.let { Spacer(Modifier.height(4.dp)); Row { Spacer(Modifier.width(52.dp)); Text(it, color = HwDim, fontSize = 11.sp) } }
                                if (hw.instructions.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(hw.instructions, color = HwMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp) }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Created ${df.format(Date(hw.createdAtMs))}", color = HwDim, fontSize = 11.sp)
                                    hw.dueDateMs?.let { due ->
                                        val isOverdue = due < System.currentTimeMillis()
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Schedule, null, Modifier.size(13.dp), tint = if (isOverdue) HwRed else HwGreen)
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isOverdue) "Overdue" else "Due ${df.format(Date(due))}", color = if (isOverdue) HwRed else HwGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (hw.requiresSubmission) {
                                        Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFFF3E0).copy(alpha = 0.15f)) {
                                            Text("✉ Sub.", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = HwMuted, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { homework ->
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pendingDelete = null },
            containerColor = HwCard,
            titleContentColor = HwWhite,
            textContentColor = HwMuted,
            title = { Text("Delete homework permanently?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${homework.title}\" and all student submissions will be removed from the institute and student app.")
                    deleteError?.let { Text(it, color = HwRed, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        deleteError = null
                        scope.launch {
                            try {
                                PermanentWorkPurgeRepository(db).purgeHomework(instId, homework.id)
                                pendingDelete = null
                            } catch (_: Exception) {
                                deleteError = "Could not delete homework. Check your connection and try again."
                            } finally {
                                isDeleting = false
                            }
                        }
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = HwRed)
                ) { Text(if (isDeleting) "Deleting…" else "Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, enabled = !isDeleting) { Text("Cancel", color = HwMuted) }
            }
        )
    }
}

@Composable
private fun StatChip(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Text(label, color = HwMuted, fontSize = 11.sp)
    }
}
