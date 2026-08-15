package com.batchfee.edu.ui.archive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.data.repository.PermanentArchivePurgeRepository
import com.batchfee.edu.data.repository.SafeDeletionRepository
import com.batchfee.edu.data.repository.StudentDeletionRepository
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ArchiveBackground = Color(0xFF06131C)
private val ArchiveSurface = Color(0xFF071B31)
private val ArchiveStroke = Color(0xFF124360)
private val ArchivePrimary = Color(0xFFF4F8FF)
private val ArchiveMuted = Color(0xFF9AAABC)
private val ArchiveCyan = Color(0xFF22D3EE)
private val ArchiveBlue = Color(0xFF3B82F6)
private val ArchiveRed = Color(0xFFFF5A67)

private enum class ArchiveType(
    val title: String,
    val icon: ImageVector,
    val emptyMessage: String
) {
    STUDENTS("Student Archive", Icons.Filled.School, "No students are archived"),
    BATCHES("Batch Archive", Icons.Filled.Groups, "No batches are archived"),
    STAFF("Staff Archive", Icons.Filled.Badge, "No staff are archived")
}

private data class ArchiveRow(
    val id: String,
    val name: String,
    val detail: String,
    val archivedAtMs: Long?
)

/** One owner-only archive centre. Individual archived records always remain recoverable until purged. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllArchivesScreen(db: AppDatabase, onBack: () -> Unit) {
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val role by SessionManager.currentUserRole.collectAsState()
    val archivedStudents by remember(instituteId) {
        instituteId?.let { db.studentDao().getArchivedStudentsByInstitute(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val archivedBatches by remember(instituteId) {
        instituteId?.let { db.batchDao().getArchivedBatchesByInstitute(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val archivedStaff by remember(instituteId) {
        instituteId?.let { db.staffDao().getArchivedStaffByInstitute(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val canPermanentlyDelete = role == "InstituteOwner" || role == "SuperAdmin"
    var selectedType by remember { mutableStateOf<ArchiveType?>(null) }
    var restoreTarget by remember { mutableStateOf<Pair<ArchiveType, ArchiveRow>?>(null) }
    var purgeTarget by remember { mutableStateOf<Pair<ArchiveType, ArchiveRow>?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedType != null) { selectedType = null }

    val rows = when (selectedType) {
        ArchiveType.STUDENTS -> archivedStudents.map { it.asArchiveRow() }
        ArchiveType.BATCHES -> archivedBatches.map { it.asArchiveRow() }
        ArchiveType.STAFF -> archivedStaff.map { it.asArchiveRow() }
        null -> emptyList()
    }

    Scaffold(
        containerColor = ArchiveBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(selectedType?.title ?: "All Archives", color = ArchivePrimary, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectedType == null) onBack() else selectedType = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ArchivePrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArchiveBackground)
            )
        }
    ) { padding ->
        if (selectedType == null) {
            ArchiveCategoryList(
                modifier = Modifier.padding(padding),
                studentCount = archivedStudents.size,
                batchCount = archivedBatches.size,
                staffCount = archivedStaff.size,
                onSelect = { selectedType = it }
            )
        } else if (rows.isEmpty()) {
            ArchiveEmptyState(modifier = Modifier.padding(padding), type = selectedType!!)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Restore keeps the complete record. Permanent delete removes its linked data from the app and cloud.",
                        color = ArchiveMuted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(rows, key = { it.id }) { row ->
                    ArchivedRecordCard(
                        row = row,
                        onRestore = { restoreTarget = selectedType!! to row },
                        onPurge = if (canPermanentlyDelete) { { purgeTarget = selectedType!! to row } } else null
                    )
                }
            }
        }
    }

    restoreTarget?.let { (type, row) ->
        AlertDialog(
            onDismissRequest = { if (!isWorking) restoreTarget = null },
            containerColor = ArchiveSurface,
            title = { Text("Restore ${row.name}?", color = ArchivePrimary, fontWeight = FontWeight.Bold) },
            text = { Text("This will return the ${type.title.removeSuffix(" Archive").lowercase()} to the active list with its retained history.", color = ArchiveMuted) },
            confirmButton = {
                TextButton(enabled = !isWorking, onClick = {
                    val inst = instituteId ?: return@TextButton
                    scope.launch {
                        isWorking = true
                        runCatching { restoreArchived(db, type, inst, row.id) }
                            .onSuccess {
                                restoreTarget = null
                                snackbar.showSnackbar("${type.title.removeSuffix(" Archive")} restored")
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Could not restore record") }
                        isWorking = false
                    }
                }) { Text(if (isWorking) "Restoring…" else "Restore", color = ArchiveCyan) }
            },
            dismissButton = {
                TextButton(enabled = !isWorking, onClick = { restoreTarget = null }) { Text("Cancel", color = ArchiveMuted) }
            }
        )
    }

    purgeTarget?.let { (type, row) ->
        PermanentArchiveDeleteDialog(
            type = type,
            row = row,
            isWorking = isWorking,
            onDismiss = { if (!isWorking) purgeTarget = null },
            onConfirm = {
                val inst = instituteId ?: return@PermanentArchiveDeleteDialog
                scope.launch {
                    isWorking = true
                    runCatching { purgeArchived(db, type, inst, row.id) }
                        .onSuccess {
                            purgeTarget = null
                            snackbar.showSnackbar("${type.title.removeSuffix(" Archive")} permanently deleted")
                        }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Permanent deletion failed") }
                    isWorking = false
                }
            }
        )
    }
}

@Composable
private fun ArchiveCategoryList(
    modifier: Modifier,
    studentCount: Int,
    batchCount: Int,
    staffCount: Int,
    onSelect: (ArchiveType) -> Unit
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Archived records", color = ArchivePrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Choose a category to restore records or permanently remove them.", color = ArchiveMuted, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        ArchiveCategoryCard(ArchiveType.STUDENTS, studentCount, onSelect)
        ArchiveCategoryCard(ArchiveType.BATCHES, batchCount, onSelect)
        ArchiveCategoryCard(ArchiveType.STAFF, staffCount, onSelect)
    }
}

@Composable
private fun ArchiveCategoryCard(type: ArchiveType, count: Int, onSelect: (ArchiveType) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(type) },
        colors = CardDefaults.cardColors(containerColor = ArchiveSurface),
        border = BorderStroke(1.dp, ArchiveStroke),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(ArchiveBlue.copy(alpha = .16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(type.icon, null, tint = ArchiveCyan, modifier = Modifier.size(23.dp)) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(type.title, color = ArchivePrimary, fontWeight = FontWeight.SemiBold)
                Text(if (count == 1) "1 archived record" else "$count archived records", color = ArchiveMuted, fontSize = 13.sp)
            }
            Icon(Icons.Filled.Archive, null, tint = ArchiveMuted, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun ArchiveEmptyState(modifier: Modifier, type: ArchiveType) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(type.icon, null, tint = ArchiveMuted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(type.emptyMessage, color = ArchivePrimary, fontWeight = FontWeight.Bold)
        Text("Archived records will appear here.", color = ArchiveMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ArchivedRecordCard(row: ArchiveRow, onRestore: () -> Unit, onPurge: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ArchiveSurface),
        border = BorderStroke(1.dp, ArchiveStroke),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(row.name, color = ArchivePrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.detail, color = ArchiveMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Archived ${archiveDate(row.archivedAtMs)}", color = ArchiveMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchiveCyan),
                    border = BorderStroke(1.dp, ArchiveCyan.copy(alpha = .65f))
                ) {
                    Icon(Icons.Filled.Restore, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
                onPurge?.let { purge ->
                    OutlinedButton(
                        onClick = purge,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchiveRed),
                        border = BorderStroke(1.dp, ArchiveRed.copy(alpha = .7f))
                    ) {
                        Icon(Icons.Filled.DeleteForever, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermanentArchiveDeleteDialog(
    type: ArchiveType,
    row: ArchiveRow,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val itemLabel = type.title.removeSuffix(" Archive").lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArchiveSurface,
        title = { Text("Delete permanently?", color = ArchiveRed, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "This permanently removes this $itemLabel and all linked records from the app and cloud. It cannot be restored.",
                    color = ArchiveMuted,
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isWorking,
                onClick = onConfirm
            ) { Text(if (isWorking) "Deleting…" else "Delete permanently", color = ArchiveRed) }
        },
        dismissButton = {
            TextButton(enabled = !isWorking, onClick = onDismiss) { Text("Cancel", color = ArchiveMuted) }
        }
    )
}

private suspend fun restoreArchived(db: AppDatabase, type: ArchiveType, instituteId: String, id: String) {
    when (type) {
        ArchiveType.STUDENTS -> StudentDeletionRepository(db).restore(instituteId, id, "Student restored from All Archives")
        ArchiveType.BATCHES -> SafeDeletionRepository(db).restoreBatch(instituteId, id, "Batch restored from All Archives")
        ArchiveType.STAFF -> SafeDeletionRepository(db).restoreStaff(instituteId, id, "Staff restored from All Archives")
    }
}

private suspend fun purgeArchived(db: AppDatabase, type: ArchiveType, instituteId: String, id: String) {
    when (type) {
        ArchiveType.STUDENTS -> com.batchfee.edu.data.repository.PermanentStudentPurgeRepository(db).purge(instituteId, id)
        ArchiveType.BATCHES -> PermanentArchivePurgeRepository(db).purgeBatch(instituteId, id)
        ArchiveType.STAFF -> PermanentArchivePurgeRepository(db).purgeStaff(instituteId, id)
    }
}

private fun StudentEntity.asArchiveRow() = ArchiveRow(id, fullName, studentCode, archivedAtMs)
private fun BatchEntity.asArchiveRow() = ArchiveRow(id, name, listOfNotNull(className, subject).joinToString(" · ").ifBlank { batchCode }, archivedAtMs)
private fun StaffEntity.asArchiveRow() = ArchiveRow(id, fullName, listOfNotNull(roleTitle, staffCode).joinToString(" · "), archivedAtMs)
private fun archiveDate(value: Long?): String = value?.let {
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
} ?: "unknown date"
