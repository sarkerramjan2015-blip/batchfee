package com.batchfee.edu.ui.students

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.data.repository.PermanentStudentPurgeRepository
import com.batchfee.edu.data.repository.StudentDeletionRepository
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val ArchiveBg = Color(0xFF07111F)
private val ArchiveCard = Color(0xFF0F172A)
private val ArchiveBorder = Color(0xFF1E293B)
private val ArchiveText = Color(0xFFF8FAFC)
private val ArchiveMuted = Color(0xFF94A3B8)
private val ArchiveCyan = Color(0xFF22D3EE)
private val ArchiveRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedStudentsScreen(db: AppDatabase, onBack: () -> Unit) {
    val instituteId = SessionManager.currentInstituteId.collectAsState().value
    val archivedStudents by remember(instituteId) {
        instituteId?.let { db.studentDao().getArchivedStudentsByInstitute(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<StudentEntity?>(null) }
    var purgeTarget by remember { mutableStateOf<StudentEntity?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ArchiveBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Archived Students", color = ArchiveText, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ArchiveText) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArchiveBg)
            )
        }
    ) { padding ->
        if (archivedStudents.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Archive, null, tint = ArchiveMuted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No archived students", color = ArchiveText, fontWeight = FontWeight.Bold)
                Text("Archived profiles can be restored here.", color = ArchiveMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { items(archivedStudents, key = { it.id }) { student ->
                ArchivedStudentCard(student, onRestore = { restoreTarget = student }, onPurge = { purgeTarget = student })
            } }
        }
    }

    restoreTarget?.let { student ->
        AlertDialog(
            onDismissRequest = { if (!isWorking) restoreTarget = null },
            containerColor = ArchiveCard,
            title = { Text("Restore ${student.fullName}?", color = ArchiveText) },
            text = { Text("The student profile and retained history will return to the active list.", color = ArchiveMuted) },
            confirmButton = { TextButton(enabled = !isWorking, onClick = {
                val inst = instituteId ?: return@TextButton
                scope.launch { isWorking = true; runCatching { StudentDeletionRepository(db).restore(inst, student.id) }
                    .onSuccess { restoreTarget = null; snackbar.showSnackbar("Student restored") }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Could not restore student") }; isWorking = false }
            }) { Text(if (isWorking) "Restoring…" else "Restore", color = ArchiveCyan) } },
            dismissButton = { TextButton(enabled = !isWorking, onClick = { restoreTarget = null }) { Text("Cancel", color = ArchiveMuted) } }
        )
    }
    purgeTarget?.let { student ->
        PermanentDeleteDialog(student = student, isWorking = isWorking, onDismiss = { if (!isWorking) purgeTarget = null }) {
            val inst = instituteId ?: return@PermanentDeleteDialog
            scope.launch { isWorking = true; runCatching { PermanentStudentPurgeRepository(db).purge(inst, student.id) }
                .onSuccess { purgeTarget = null; snackbar.showSnackbar("Student and all linked data permanently deleted") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Permanent deletion failed") }; isWorking = false }
        }
    }
}

@Composable
private fun ArchivedStudentCard(student: StudentEntity, onRestore: () -> Unit, onPurge: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = ArchiveCard), modifier = Modifier.fillMaxWidth().border(1.dp, ArchiveBorder, RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(student.fullName, color = ArchiveText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("${student.studentCode} · Archived ${formatArchiveDate(student.archivedAtMs)}", color = ArchiveMuted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchiveCyan)) { Icon(Icons.Filled.Restore, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Restore") }
                OutlinedButton(onClick = onPurge, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchiveRed), border = androidx.compose.foundation.BorderStroke(1.dp, ArchiveRed.copy(alpha = 0.65f))) { Icon(Icons.Filled.DeleteForever, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Delete") }
            }
        }
    }
}

@Composable
private fun PermanentDeleteDialog(student: StudentEntity, isWorking: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = ArchiveCard,
        title = { Text("Permanently delete?", color = ArchiveRed, fontWeight = FontWeight.Bold) },
        text = { Column {
            Text("This removes ${student.fullName}'s profile, fees, payments, receipts, attendance, results, submissions, photo and login. This cannot be undone.", color = ArchiveMuted)
        } },
        confirmButton = { TextButton(enabled = !isWorking, onClick = onConfirm) { Text("Delete permanently", color = ArchiveRed) } },
        dismissButton = { TextButton(enabled = !isWorking, onClick = onDismiss) { Text("Cancel", color = ArchiveMuted) } }
    )
}

private fun formatArchiveDate(value: Long?): String = value?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "unknown date"
