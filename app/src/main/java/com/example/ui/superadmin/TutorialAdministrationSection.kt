package com.batchfee.edu.ui.superadmin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.repository.AppTutorial
import com.batchfee.edu.data.repository.NoticeCenterRepository
import kotlinx.coroutines.launch

private val TutorialAdminCard = Color(0xFF0F172A)
private val TutorialAdminBorder = Color(0xFF263348)
private val TutorialAdminText = Color(0xFFF8FAFC)
private val TutorialAdminMuted = Color(0xFF94A3B8)
private val TutorialAdminCyan = Color(0xFF22D3EE)
private val TutorialAdminBlue = Color(0xFF3B82F6)
private val TutorialAdminAmber = Color(0xFFF59E0B)
private val TutorialAdminRed = Color(0xFFEF4444)
private val TutorialAdminGreen = Color(0xFF22C55E)

/** Root-only UI. The callable independently enforces Root access and keeps the raw URL out of Firestore. */
@Composable
fun V18TutorialAdministrationSection() {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    var tutorials by remember { mutableStateOf<List<AppTutorial>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editorTutorial by remember { mutableStateOf<AppTutorial?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.platformTutorials() }
                .onSuccess { tutorials = it }
                .onFailure { throwable ->
                    FirebaseFailureReporter.report(throwable, operation = "load tutorial library", permissionDeniedIsExpected = true)
                    error = throwable.message ?: "Could not load tutorials."
                }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TutorialAdminCard),
        border = BorderStroke(1.dp, TutorialAdminBorder)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(TutorialAdminBlue.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayCircle, null, tint = TutorialAdminCyan, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("App guide & tutorials", color = TutorialAdminText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Root-managed video library for institute users", color = TutorialAdminMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { refresh() }, enabled = !loading && !busy) {
                    Icon(Icons.Filled.Refresh, null, tint = TutorialAdminCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Refresh", color = TutorialAdminCyan, fontSize = 11.sp)
                }
            }
            Text(
                "Paste one YouTube link. Shorts automatically use a portrait frame; normal videos use 16:9. It plays inside BatchFee, while YouTube controls availability and advertising.",
                color = TutorialAdminMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Button(
                onClick = { editorTutorial = null; showEditor = true; error = null },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TutorialAdminBlue)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add tutorial", fontWeight = FontWeight.Bold)
            }
            when {
                loading -> Text("Loading tutorial library…", color = TutorialAdminMuted, fontSize = 12.sp)
                tutorials.isEmpty() -> Text("No tutorial has been created yet.", color = TutorialAdminMuted, fontSize = 12.sp)
                else -> tutorials.forEach { tutorial ->
                    TutorialAdminItem(
                        tutorial = tutorial,
                        onEdit = { editorTutorial = tutorial; showEditor = true; error = null },
                        onArchiveRestore = {
                            busy = true
                            scope.launch {
                                runCatching {
                                    if (tutorial.status == "published") repository.archiveTutorial(tutorial.tutorialId)
                                    else repository.restoreTutorial(tutorial.tutorialId)
                                }.onSuccess { refresh() }
                                    .onFailure { throwable ->
                                        error = throwable.message ?: "Could not update this tutorial."
                                    }
                                busy = false
                            }
                        },
                        enabled = !busy
                    )
                }
            }
            error?.let { Text(it, color = TutorialAdminRed, fontSize = 11.sp) }
        }
    }

    if (showEditor) {
        TutorialEditorDialog(
            tutorial = editorTutorial,
            busy = busy,
            onDismiss = { if (!busy) showEditor = false },
            onSave = { title, description, category, youtubeUrl, displayOrder ->
                busy = true
                scope.launch {
                    runCatching {
                        editorTutorial?.let {
                            repository.updateTutorial(it.tutorialId, title, description, category, youtubeUrl, displayOrder)
                        } ?: repository.createTutorial(title, description, category, youtubeUrl, displayOrder)
                    }.onSuccess {
                        showEditor = false
                        refresh()
                    }.onFailure { throwable ->
                        error = throwable.message ?: "Could not save this tutorial."
                    }
                    busy = false
                }
            }
        )
    }
}

@Composable
private fun TutorialAdminItem(
    tutorial: AppTutorial,
    onEdit: () -> Unit,
    onArchiveRestore: () -> Unit,
    enabled: Boolean
) {
    val isPublished = tutorial.status == "published"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = BorderStroke(1.dp, TutorialAdminBorder)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayCircle, null, tint = TutorialAdminCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tutorial.title, color = TutorialAdminText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${tutorial.category} · Order ${tutorial.displayOrder}", color = TutorialAdminMuted, fontSize = 10.sp)
                }
                Text(if (isPublished) "Live" else "Archived", color = if (isPublished) TutorialAdminGreen else TutorialAdminAmber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            if (tutorial.description.isNotBlank()) {
                Text(tutorial.description, color = TutorialAdminMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Filled.Edit, null, tint = TutorialAdminCyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp)); Text("Edit", color = TutorialAdminCyan, fontSize = 11.sp)
                }
                TextButton(onClick = onArchiveRestore, enabled = enabled) {
                    Icon(if (isPublished) Icons.Filled.Archive else Icons.Filled.Restore, null, tint = if (isPublished) TutorialAdminAmber else TutorialAdminGreen, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp)); Text(if (isPublished) "Archive" else "Restore", color = if (isPublished) TutorialAdminAmber else TutorialAdminGreen, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TutorialEditorDialog(
    tutorial: AppTutorial?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Int) -> Unit
) {
    val identity = tutorial?.tutorialId ?: "new"
    var title by remember(identity) { mutableStateOf(tutorial?.title.orEmpty()) }
    var description by remember(identity) { mutableStateOf(tutorial?.description.orEmpty()) }
    var category by remember(identity) { mutableStateOf(tutorial?.category ?: "Getting started") }
    var youtubeUrl by remember(identity) { mutableStateOf(tutorial?.youtubeVideoId?.let { "https://youtu.be/$it" }.orEmpty()) }
    var displayOrder by remember(identity) { mutableStateOf(tutorial?.displayOrder?.toString() ?: "0") }
    var formError by remember(identity) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TutorialAdminCard,
        title = { Text(if (tutorial == null) "Add tutorial" else "Edit tutorial", color = TutorialAdminText, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(title, { if (it.length <= 120) title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tutorialFieldColors())
                OutlinedTextField(description, { if (it.length <= 1_000) description = it }, label = { Text("Short description (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(), colors = tutorialFieldColors())
                OutlinedTextField(category, { if (it.length <= 60) category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tutorialFieldColors())
                OutlinedTextField(
                    youtubeUrl,
                    { if (it.length <= 2_000) youtubeUrl = it },
                    label = { Text("YouTube video link or 11-character ID") },
                    supportingText = { Text("Shorts use portrait frame; normal videos use 16:9. Playlist and channel links are not accepted.", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = tutorialFieldColors()
                )
                OutlinedTextField(displayOrder, { if (it.all(Char::isDigit) && it.length <= 5) displayOrder = it }, label = { Text("Display order (0 first)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tutorialFieldColors())
                formError?.let { Text(it, color = TutorialAdminRed, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val order = displayOrder.toIntOrNull()
                    when {
                        title.trim().length < 3 -> formError = "Write a short title."
                        category.trim().isBlank() -> formError = "Write a category."
                        youtubeUrl.trim().isBlank() -> formError = "Paste a YouTube video link."
                        order == null || order !in 0..10_000 -> formError = "Use an order from 0 to 10000."
                        else -> onSave(title.trim(), description.trim(), category.trim(), youtubeUrl.trim(), order)
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = TutorialAdminBlue)
            ) { Text(if (busy) "Saving…" else "Save tutorial") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = TutorialAdminMuted) } }
    )
}

@Composable
private fun tutorialFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TutorialAdminText,
    unfocusedTextColor = TutorialAdminText,
    focusedBorderColor = TutorialAdminCyan,
    unfocusedBorderColor = TutorialAdminBorder,
    focusedLabelColor = TutorialAdminCyan,
    unfocusedLabelColor = TutorialAdminMuted,
    cursorColor = TutorialAdminCyan
)
