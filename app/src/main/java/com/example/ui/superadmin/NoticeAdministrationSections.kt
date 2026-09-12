package com.batchfee.edu.ui.superadmin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import com.batchfee.edu.data.repository.AppNotice
import com.batchfee.edu.data.repository.NoticeAudience
import com.batchfee.edu.data.repository.NoticeCenterRepository
import com.batchfee.edu.data.repository.ProductFeedbackDetails
import com.batchfee.edu.data.repository.ProductFeedbackItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NoticeAdminCard = Color(0xFF0F172A)
private val NoticeAdminBorder = Color(0xFF263348)
private val NoticeAdminText = Color(0xFFF8FAFC)
private val NoticeAdminMuted = Color(0xFF94A3B8)
private val NoticeAdminCyan = Color(0xFF22D3EE)
private val NoticeAdminPink = Color(0xFFEC4899)
private val NoticeAdminAmber = Color(0xFFF59E0B)
private val NoticeAdminRed = Color(0xFFEF4444)
private val NoticeAdminGreen = Color(0xFF22C55E)

/** Root-only composition. Its repository is still backed by a Root-checked callable. */
@Composable
fun V18NoticeAdministrationSection() {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    var notices by remember { mutableStateOf<List<AppNotice>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("update") }
    var includeOwners by remember { mutableStateOf(true) }
    var includeStaff by remember { mutableStateOf(false) }
    var instituteIdsText by remember { mutableStateOf("") }
    var expiryDays by remember { mutableStateOf("30") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedNotice by remember { mutableStateOf<AppNotice?>(null) }

    suspend fun refresh() {
        runCatching { repository.platformNotices() }
            .onSuccess { notices = it }
            .onFailure { throwable ->
                FirebaseFailureReporter.report(throwable, operation = "load platform notices", permissionDeniedIsExpected = true)
                error = throwable.message ?: "Could not load notices."
            }
    }
    LaunchedEffect(Unit) { refresh() }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = NoticeAdminCard), border = BorderStroke(1.dp, NoticeAdminBorder)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(NoticeAdminPink.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Campaign, null, tint = NoticeAdminPink, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Notice Center", color = NoticeAdminText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Quiet bell delivery — no dashboard popup", color = NoticeAdminMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { scope.launch { refresh() } }) {
                    Icon(Icons.Filled.Refresh, null, tint = NoticeAdminCyan, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(3.dp)); Text("Refresh", color = NoticeAdminCyan, fontSize = 11.sp)
                }
            }
            OutlinedTextField(title, { if (it.length <= 120) title = it }, label = { Text("Notice title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = noticeAdminFieldColors())
            OutlinedTextField(body, { if (it.length <= 3_000) body = it }, label = { Text("Full notice — Bengali and English are supported") }, minLines = 4, modifier = Modifier.fillMaxWidth(), colors = noticeAdminFieldColors())
            Text("Category", color = NoticeAdminMuted, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("update", "maintenance", "billing", "feature", "important").forEach { option ->
                    FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option.replaceFirstChar { it.uppercase() }, fontSize = 10.sp) })
                }
            }
            Text("Audience", color = NoticeAdminMuted, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = includeOwners, onClick = { includeOwners = !includeOwners }, label = { Text("Owners", fontSize = 10.sp) })
                FilterChip(selected = includeStaff, onClick = { includeStaff = !includeStaff }, label = { Text("Staff", fontSize = 10.sp) })
            }
            OutlinedTextField(
                instituteIdsText,
                { if (it.length <= 6_000) instituteIdsText = it },
                label = { Text("Specific institute IDs (optional, comma-separated)") },
                supportingText = { Text("Leave blank for every selected role. Maximum 50 institutes.", fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth(), colors = noticeAdminFieldColors()
            )
            OutlinedTextField(expiryDays, { input -> if (input.all(Char::isDigit) && input.length <= 3) expiryDays = input }, label = { Text("Expiry in days (0 = no expiry)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = noticeAdminFieldColors())
            Card(colors = CardDefaults.cardColors(containerColor = NoticeAdminAmber.copy(alpha = .10f)), border = BorderStroke(1.dp, NoticeAdminAmber.copy(alpha = .32f)), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = NoticeAdminAmber, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Notices must not contain passwords, PINs, OTPs or tokens. For compatibility, V1.7 legacy announcements remain separate until all clients are upgraded.", color = NoticeAdminMuted, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            error?.let { Text(it, color = NoticeAdminRed, fontSize = 11.sp) }
            Button(
                onClick = {
                    val roles = buildList { if (includeOwners) add("owner"); if (includeStaff) add("staff") }
                    val ids = instituteIdsText.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    val days = expiryDays.toIntOrNull() ?: -1
                    if (roles.isEmpty()) { error = "Select at least one recipient role."; return@Button }
                    if (days !in 0..365) { error = "Expiry must be from 0 to 365 days."; return@Button }
                    if (ids.size > 50) { error = "A notice can target at most 50 institute IDs."; return@Button }
                    busy = true; error = null
                    scope.launch {
                        runCatching { repository.publishNotice(title, body, category, NoticeAudience(roles, ids), days) }
                            .onSuccess { title = ""; body = ""; instituteIdsText = ""; expiryDays = "30"; refresh() }
                            .onFailure { throwable ->
                                FirebaseFailureReporter.report(throwable, operation = "publish notice", permissionDeniedIsExpected = true)
                                error = throwable.message ?: "Notice was not published."
                            }
                        busy = false
                    }
                },
                enabled = !busy && title.trim().length >= 3 && body.trim().length >= 3,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NoticeAdminPink)
            ) {
                Icon(Icons.Filled.Send, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                Text(if (busy) "Publishing…" else "Publish quiet in-app notice", fontWeight = FontWeight.Bold)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    if (notices.isNotEmpty()) {
        Text("Notice history", color = NoticeAdminMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        notices.forEach { notice ->
            NoticeAdminHistoryCard(notice, onOpen = { selectedNotice = notice })
            Spacer(Modifier.height(6.dp))
        }
    }
    selectedNotice?.let { notice ->
        NoticeAdminDetailDialog(
            notice = notice,
            onDismiss = { selectedNotice = null },
            onArchive = {
                scope.launch {
                    runCatching { repository.archiveNotice(notice.noticeId) }
                        .onSuccess { selectedNotice = null; refresh() }
                        .onFailure { error = it.message ?: "Could not archive notice." }
                }
            },
            onRestore = {
                scope.launch {
                    runCatching { repository.restoreNotice(notice.noticeId) }
                        .onSuccess { selectedNotice = null; refresh() }
                        .onFailure { error = it.message ?: "Could not restore notice." }
                }
            }
        )
    }
}

@Composable
fun V18SupportFeedbackInboxSection() {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ProductFeedbackItem>>(emptyList()) }
    var selected by remember { mutableStateOf<ProductFeedbackDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun refresh() {
        loading = true
        runCatching { repository.supportItems(filter) }
            .onSuccess { items = it }
            .onFailure { throwable ->
                FirebaseFailureReporter.report(throwable, operation = "load product feedback", permissionDeniedIsExpected = true)
                error = throwable.message ?: "Could not load product feedback."
            }
        loading = false
    }
    LaunchedEffect(filter) { refresh() }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = NoticeAdminCard), border = BorderStroke(1.dp, NoticeAdminBorder)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(NoticeAdminAmber.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ChatBubbleOutline, null, tint = NoticeAdminAmber, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Owner feedback inbox", color = NoticeAdminText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Suggestions and complaints are private to BatchFee Team", color = NoticeAdminMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh", color = NoticeAdminCyan, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FeedbackFilterChip("All", filter.isEmpty()) { filter = "" }
                FeedbackFilterChip("Open", filter == "open") { filter = "open" }
                FeedbackFilterChip("In progress", filter == "in_progress") { filter = "in_progress" }
                FeedbackFilterChip("Resolved", filter == "resolved") { filter = "resolved" }
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Text("Loading feedback…", color = NoticeAdminMuted, fontSize = 12.sp)
                items.isEmpty() -> Text("No feedback in this view.", color = NoticeAdminMuted, fontSize = 12.sp)
                else -> items.forEach { item ->
                    FeedbackInboxCard(item, onOpen = {
                        scope.launch {
                            runCatching { repository.supportItemDetails(item.itemId) }
                                .onSuccess { selected = it }
                                .onFailure { error = it.message ?: "Could not open feedback." }
                        }
                    })
                    Spacer(Modifier.height(7.dp))
                }
            }
            error?.let { Text(it, color = NoticeAdminRed, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
    selected?.let { detail ->
        FeedbackDetailDialog(
            details = detail,
            onDismiss = { selected = null },
            onAddNote = { note, status ->
                scope.launch {
                    runCatching { repository.addSupportItemNote(detail.item.itemId, note, status) }
                        .onSuccess {
                            selected = null
                            refresh()
                        }
                        .onFailure { error = it.message ?: "Could not save internal note." }
                }
            },
            onSetStatus = { status ->
                scope.launch {
                    runCatching { repository.updateSupportItemStatus(detail.item.itemId, status) }
                        .onSuccess { selected = null; refresh() }
                        .onFailure { error = it.message ?: "Could not update feedback status." }
                }
            }
        )
    }
}

@Composable private fun FeedbackFilterChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(selected, onClick, { Text(label, fontSize = 10.sp) })

@Composable
private fun NoticeAdminHistoryCard(notice: AppNotice, onOpen: () -> Unit) {
    val color = if (notice.status == "archived") NoticeAdminAmber else NoticeAdminCyan
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(11.dp), colors = CardDefaults.cardColors(containerColor = NoticeAdminCard), border = BorderStroke(1.dp, NoticeAdminBorder)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (notice.status == "archived") Icons.Filled.Archive else Icons.Filled.Campaign, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) {
                Text(notice.title, color = NoticeAdminText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${notice.category.replaceFirstChar { it.uppercase() }} · ${noticeAdminDate(notice.publishedAtMs)}", color = NoticeAdminMuted, fontSize = 10.sp)
            }
            Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(7.dp)) { Text(notice.status, color = color, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
        }
    }
}

@Composable
private fun NoticeAdminDetailDialog(notice: AppNotice, onDismiss: () -> Unit, onArchive: () -> Unit, onRestore: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NoticeAdminCard,
        title = { Text(notice.title, color = NoticeAdminText, fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
            Text(notice.body, color = NoticeAdminText, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(10.dp)); Text("Audience: ${notice.audience.roles.joinToString()}${if (notice.audience.instituteIds.isNotEmpty()) " · ${notice.audience.instituteIds.size} selected institute(s)" else " · all institutes"}", color = NoticeAdminMuted, fontSize = 11.sp)
            Text("Expiry: ${if (notice.expiresAtMs > 0) noticeAdminDate(notice.expiresAtMs) else "Never"}", color = NoticeAdminMuted, fontSize = 11.sp)
        } },
        confirmButton = {
            if (notice.status == "published") Button(onClick = onArchive, colors = ButtonDefaults.buttonColors(containerColor = NoticeAdminAmber)) { Text("Archive", color = Color.Black) }
            else Button(onClick = onRestore, colors = ButtonDefaults.buttonColors(containerColor = NoticeAdminGreen)) { Text("Restore", color = Color.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = NoticeAdminMuted) } }
    )
}

@Composable
private fun FeedbackInboxCard(item: ProductFeedbackItem, onOpen: () -> Unit) {
    val typeColor = if (item.type == "complaint") NoticeAdminRed else NoticeAdminAmber
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(11.dp), colors = CardDefaults.cardColors(containerColor = NoticeAdminCard), border = BorderStroke(1.dp, NoticeAdminBorder)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.type == "complaint") Icons.Filled.ErrorOutline else Icons.Filled.Lightbulb, null, tint = typeColor, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) {
                Text(item.title, color = NoticeAdminText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.instituteName.ifBlank { item.instituteId }} · ${item.createdByName} · ${noticeAdminDate(item.createdAtMs)}", color = NoticeAdminMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(color = statusColor(item.status).copy(alpha = .14f), shape = RoundedCornerShape(7.dp)) { Text(item.status.replace('_', ' '), color = statusColor(item.status), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
        }
    }
}

@Composable
private fun FeedbackDetailDialog(details: ProductFeedbackDetails, onDismiss: () -> Unit, onAddNote: (String, String) -> Unit, onSetStatus: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(details.item.status) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NoticeAdminCard,
        title = { Text(details.item.title, color = NoticeAdminText, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.height(430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${details.item.type.replaceFirstChar { it.uppercase() }} · ${details.item.instituteName.ifBlank { details.item.instituteId }}", color = NoticeAdminAmber, fontSize = 11.sp)
                Text(details.item.body, color = NoticeAdminText, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Internal history", color = NoticeAdminMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (details.notes.isEmpty()) Text("No internal updates yet.", color = NoticeAdminMuted, fontSize = 11.sp)
                details.notes.forEach { itemNote ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111C30)), shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(itemNote.body, color = NoticeAdminText, fontSize = 11.sp)
                            Text("${itemNote.createdByName} · ${noticeAdminDate(itemNote.createdAtMs)} · ${itemNote.status}", color = NoticeAdminMuted, fontSize = 9.sp)
                        }
                    }
                }
                Text("Add immutable internal update", color = NoticeAdminMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(note, { if (it.length <= 2_000) note = it }, label = { Text("What was checked / next update plan") }, minLines = 3, modifier = Modifier.fillMaxWidth(), colors = noticeAdminFieldColors())
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("open", "in_progress", "resolved").forEach { option -> FilterChip(status == option, { status = option }, { Text(option.replace('_', ' '), fontSize = 10.sp) }) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (note.trim().length >= 3) onAddNote(note, status) else onSetStatus(status) }, colors = ButtonDefaults.buttonColors(containerColor = NoticeAdminCyan)) {
                Text(if (note.trim().length >= 3) "Save update" else "Save status", color = Color.Black)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = NoticeAdminMuted) } }
    )
}

@Composable private fun noticeAdminFieldColors() = OutlinedTextFieldDefaults.colors(focusedContainerColor = NoticeAdminCard, unfocusedContainerColor = NoticeAdminCard, focusedBorderColor = NoticeAdminCyan, unfocusedBorderColor = NoticeAdminBorder, focusedTextColor = NoticeAdminText, unfocusedTextColor = NoticeAdminText, cursorColor = NoticeAdminCyan)
private fun statusColor(status: String) = when (status) { "resolved" -> NoticeAdminGreen; "in_progress" -> NoticeAdminCyan; else -> NoticeAdminAmber }
private fun noticeAdminDate(millis: Long) = if (millis <= 0) "" else SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(millis))
