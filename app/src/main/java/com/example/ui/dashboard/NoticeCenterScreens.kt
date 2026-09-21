package com.batchfee.edu.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.batchfee.edu.data.repository.NoticeCenterRepository
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NoticeScreenBg = Color(0xFF07111F)
private val NoticeScreenCard = Color(0xFF0F172A)
private val NoticeScreenStroke = Color(0xFF1E293B)
private val NoticeText = Color(0xFFF8FAFC)
private val NoticeMuted = Color(0xFF94A3B8)
private val NoticeCyan = Color(0xFF22D3EE)
private val NoticeBlue = Color(0xFF3B82F6)
private val NoticeAmber = Color(0xFFF59E0B)
private val NoticeRed = Color(0xFFEF4444)
private val NoticeGreen = Color(0xFF22C55E)

/** Quiet header affordance. It only fetches in-app state and never shows a popup. */
@Composable
fun NoticeBellButton(onOpen: () -> Unit, compact: Boolean = false) {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    val userId by SessionManager.currentUserId.collectAsState()
    var unreadCount by remember(userId) { mutableIntStateOf(0) }

    LaunchedEffect(userId) {
        if (userId == null) return@LaunchedEffect
        runCatching { repository.myNotices(tab = "all", pageSize = 50).unreadCount }
            .onSuccess { unreadCount = it }
            .onFailure { error ->
                FirebaseFailureReporter.report(error, operation = "notice bell refresh", permissionDeniedIsExpected = true)
            }
    }

    Box(contentAlignment = Alignment.TopEnd) {
        IconButton(
            onClick = {
                onOpen()
                // A light refresh after returning to the header; a failed remote
                // call is intentionally non-disruptive for the user's work.
                scope.launch {
                    runCatching { repository.myNotices(tab = "all", pageSize = 50).unreadCount }
                        .onSuccess { unreadCount = it }
                }
            },
            modifier = Modifier
                .size(if (compact) 42.dp else 44.dp)
                .clip(RoundedCornerShape(if (compact) 12.dp else 14.dp))
                .background(NoticeScreenCard)
        ) {
            Icon(
                if (unreadCount > 0) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                contentDescription = if (unreadCount > 0) "$unreadCount unread notices" else "Notices",
                tint = NoticeText,
                modifier = Modifier.size(if (compact) 20.dp else 22.dp)
            )
        }
        if (unreadCount > 0) {
            Surface(
                color = NoticeRed,
                shape = CircleShape,
                modifier = Modifier.padding(top = 3.dp, end = 3.dp)
            ) {
                Text(
                    if (unreadCount > 9) "9+" else unreadCount.toString(),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminNoticeCenterScreen(onBack: () -> Unit) {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf("all") }
    var notices by remember { mutableStateOf<List<AppNotice>>(emptyList()) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedNotice by remember { mutableStateOf<AppNotice?>(null) }
    var savingNoticeId by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        loadError = null
        notices = emptyList()
        var failureMessage: String? = null
        try {
            val inbox = repository.myNotices(tab = tab, pageSize = 50)
            notices = inbox.notices
            unreadCount = inbox.unreadCount
        } catch (error: Throwable) {
            FirebaseFailureReporter.report(error, operation = "load notice centre", permissionDeniedIsExpected = true)
            failureMessage = noticeUserMessage(error)
            loadError = failureMessage
        } finally {
            // Always leave the progress state before a snackbar suspends this
            // coroutine, otherwise the page appears stuck behind the message.
            loading = false
        }
        failureMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(tab) { refresh() }

    Scaffold(
        containerColor = NoticeScreenBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BatchFee Notices", color = NoticeText, fontWeight = FontWeight.Bold)
                        Text("Quiet in-app updates from BatchFee Team", color = NoticeMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NoticeText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NoticeScreenCard)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeTabChip("Unread", tab == "unread", unreadCount) { tab = "unread" }
                NoticeTabChip("All", tab == "all") { tab = "all" }
                NoticeTabChip("Archived", tab == "archived") { tab = "archived" }
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier.padding(top = 46.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = NoticeCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Loading notices…", color = NoticeMuted)
                    }
                }
                loadError != null -> NoticeLoadError(
                    message = loadError.orEmpty(),
                    onRetry = { scope.launch { refresh() } }
                )
                notices.isEmpty() -> NoticeEmptyState(tab)
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    items(notices, key = { it.noticeId }) { notice ->
                        NoticeCard(notice = notice, onOpen = {
                            selectedNotice = notice
                            if (!notice.isRead && tab != "archived" && savingNoticeId != notice.noticeId) {
                                savingNoticeId = notice.noticeId
                                scope.launch {
                                    runCatching { repository.markNoticeState(notice.noticeId, true) }
                                        .onSuccess {
                                            notices = if (tab == "unread") {
                                                notices.filterNot { it.noticeId == notice.noticeId }
                                            } else {
                                                notices.map {
                                                    if (it.noticeId == notice.noticeId) it.copy(isRead = true) else it
                                                }
                                            }
                                            unreadCount = (unreadCount - 1).coerceAtLeast(0)
                                            selectedNotice = selectedNotice?.let {
                                                if (it.noticeId == notice.noticeId) it.copy(isRead = true) else it
                                            }
                                        }
                                        .onFailure { error ->
                                            FirebaseFailureReporter.report(error, operation = "mark notice read", permissionDeniedIsExpected = true)
                                            snackbarHostState.showSnackbar("Could not save read status. Please try again.")
                                        }
                                    savingNoticeId = null
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    selectedNotice?.let { notice ->
        NoticeDetailDialog(
            notice = notice,
            saving = savingNoticeId == notice.noticeId,
            canChangeReadState = tab != "archived",
            onDismiss = { selectedNotice = null },
            onToggleRead = { nextRead ->
                if (savingNoticeId == notice.noticeId) return@NoticeDetailDialog
                savingNoticeId = notice.noticeId
                scope.launch {
                    runCatching { repository.markNoticeState(notice.noticeId, nextRead) }
                        .onSuccess {
                            notices = notices.map {
                                if (it.noticeId == notice.noticeId) it.copy(isRead = nextRead) else it
                            }
                            unreadCount = if (nextRead) {
                                (unreadCount - 1).coerceAtLeast(0)
                            } else {
                                unreadCount + 1
                            }
                            selectedNotice = selectedNotice?.let {
                                if (it.noticeId == notice.noticeId) it.copy(isRead = nextRead) else it
                            }
                        }
                        .onFailure { error ->
                            FirebaseFailureReporter.report(error, operation = "toggle notice state", permissionDeniedIsExpected = true)
                            snackbarHostState.showSnackbar("Could not save notice state.")
                        }
                    savingNoticeId = null
                }
            }
        )
    }
}

@Composable
private fun NoticeTabChip(label: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (count != null && count > 0) "$label ($count)" else label, fontSize = 12.sp) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = NoticeCyan.copy(alpha = 0.18f),
            selectedLabelColor = NoticeCyan,
            labelColor = NoticeMuted
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            borderColor = NoticeScreenStroke,
            selectedBorderColor = NoticeCyan.copy(alpha = 0.6f),
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun NoticeEmptyState(tab: String) {
    val label = when (tab) {
        "unread" -> "You are all caught up"
        "archived" -> "No archived notices"
        else -> "No notices right now"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.NotificationsNone, null, tint = NoticeMuted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(label, color = NoticeText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Updates will appear here without interrupting your work.", color = NoticeMuted, fontSize = 12.sp)
    }
}

@Composable
private fun NoticeLoadError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(NoticeRed.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ErrorOutline, null, tint = NoticeRed, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Notices could not be loaded", color = NoticeText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = NoticeMuted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = NoticeCyan)
        ) {
            Icon(Icons.Filled.Refresh, null, tint = NoticeScreenBg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("Try again", color = NoticeScreenBg, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NoticeCard(notice: AppNotice, onOpen: () -> Unit) {
    val categoryColor = noticeCategoryColor(notice.category)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NoticeScreenCard),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (!notice.isRead) categoryColor.copy(alpha = 0.65f) else NoticeScreenStroke
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(categoryColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Campaign, null, tint = categoryColor, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(notice.title, color = NoticeText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (!notice.isRead) Box(Modifier.size(8.dp).background(NoticeCyan, CircleShape))
                }
                Spacer(Modifier.height(3.dp))
                Text(notice.body, color = NoticeMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NoticeCategoryChip(notice.category)
                    Spacer(Modifier.width(7.dp))
                    Text(noticeDateLabel(notice.publishedAtMs), color = NoticeMuted, fontSize = 10.sp)
                    if (notice.expiresAtMs > 0L) {
                        Spacer(Modifier.width(7.dp))
                        Text("Expires ${noticeDateLabel(notice.expiresAtMs)}", color = NoticeMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCategoryChip(category: String) {
    val color = noticeCategoryColor(category)
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(6.dp)) {
        Text(category.replaceFirstChar { it.uppercase() }, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun NoticeDetailDialog(
    notice: AppNotice,
    saving: Boolean,
    canChangeReadState: Boolean,
    onDismiss: () -> Unit,
    onToggleRead: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NoticeScreenCard,
        title = {
            Column {
                NoticeCategoryChip(notice.category)
                Spacer(Modifier.height(8.dp))
                Text(notice.title, color = NoticeText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Spacer(Modifier.height(3.dp))
                Text("${notice.senderName} · ${noticeDateLabel(notice.publishedAtMs)}", color = NoticeMuted, fontSize = 11.sp)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState())) {
                Text(notice.body, color = NoticeText, fontSize = 14.sp, lineHeight = 21.sp)
                if (notice.expiresAtMs > 0L) {
                    Spacer(Modifier.height(14.dp))
                    Text("Visible until ${noticeDateLabel(notice.expiresAtMs)}", color = NoticeMuted, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            if (canChangeReadState) {
                TextButton(onClick = { onToggleRead(!notice.isRead) }, enabled = !saving) {
                    Icon(if (notice.isRead) Icons.Filled.Unsubscribe else Icons.Filled.MarkEmailRead, null, tint = NoticeCyan, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (saving) "Saving…" else if (notice.isRead) "Mark unread" else "Mark read",
                        color = if (saving) NoticeMuted else NoticeCyan
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = NoticeMuted) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFeedbackScreen(onBack: () -> Unit) {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var type by remember { mutableStateOf("suggestion") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val cleanTitle = title.trim()
    val cleanBody = body.trim()
    val canSubmit = cleanTitle.length >= 2 && cleanBody.length >= 10

    Scaffold(
        containerColor = NoticeScreenBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Product feedback", color = NoticeText, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NoticeText) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NoticeScreenCard)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NoticeScreenCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, NoticeScreenStroke),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lightbulb, null, tint = NoticeAmber, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Help shape BatchFee", color = NoticeText, fontWeight = FontWeight.Bold)
                            Text("Your feedback goes only to BatchFee support.", color = NoticeMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == "suggestion", onClick = { type = "suggestion" }, label = { Text("Suggestion") }, leadingIcon = { Icon(Icons.Filled.Lightbulb, null, modifier = Modifier.size(16.dp)) })
                        FilterChip(selected = type == "complaint", onClick = { type = "complaint" }, label = { Text("Report a problem") }, leadingIcon = { Icon(Icons.Filled.ReportProblem, null, modifier = Modifier.size(16.dp)) })
                    }
                    OutlinedTextField(
                        value = title, onValueChange = { if (it.length <= 120) title = it },
                        label = { Text("Short title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                if (title.isNotEmpty() && cleanTitle.length < 2) "Enter at least 2 characters."
                                else "${title.length}/120"
                            )
                        },
                        isError = title.isNotEmpty() && cleanTitle.length < 2,
                        colors = feedbackFieldColors()
                    )
                    OutlinedTextField(
                        value = body, onValueChange = { if (it.length <= 2_000) body = it },
                        label = { Text(if (type == "suggestion") "What would make BatchFee better?" else "What happened? Include safe steps to reproduce.") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                if (body.isNotEmpty() && cleanBody.length < 10) "Add ${10 - cleanBody.length} more character${if (10 - cleanBody.length == 1) "" else "s"}."
                                else "${body.length}/2000"
                            )
                        },
                        isError = body.isNotEmpty() && cleanBody.length < 10,
                        colors = feedbackFieldColors()
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NoticeAmber.copy(alpha = 0.10f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NoticeAmber.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = NoticeAmber, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Do not write passwords, PINs, OTPs or access codes here. Support will never ask for them.", color = NoticeMuted, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            sending = true
                            scope.launch {
                                runCatching { repository.submitFeedback(type, title, body) }
                                    .onSuccess {
                                        title = ""; body = ""
                                        snackbarHostState.showSnackbar("Thanks — your feedback was sent to BatchFee Team.")
                                    }
                                    .onFailure { error ->
                                        FirebaseFailureReporter.report(error, operation = "submit product feedback", permissionDeniedIsExpected = true)
                                        snackbarHostState.showSnackbar(error.message ?: "Could not send feedback. Please try again.")
                                    }
                                sending = false
                            }
                        },
                        enabled = !sending && canSubmit,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NoticeBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.ChatBubbleOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sending) "Sending…" else "Send to BatchFee Team", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun feedbackFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedContainerColor = NoticeScreenCard,
    unfocusedContainerColor = NoticeScreenCard,
    focusedBorderColor = NoticeCyan,
    unfocusedBorderColor = NoticeScreenStroke,
    focusedTextColor = NoticeText,
    unfocusedTextColor = NoticeText,
    cursorColor = NoticeCyan
)

private fun noticeCategoryColor(category: String): Color = when (category) {
    "maintenance" -> NoticeAmber
    "billing" -> NoticeGreen
    "feature" -> NoticeCyan
    "important" -> NoticeRed
    else -> NoticeBlue
}

private fun noticeDateLabel(millis: Long): String = if (millis <= 0L) "" else {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(millis))
}

private fun noticeUserMessage(error: Throwable): String {
    val functionsError = generateSequence(error as Throwable?) { it.cause }
        .filterIsInstance<FirebaseFunctionsException>()
        .firstOrNull()
    return when (functionsError?.code) {
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
        FirebaseFunctionsException.Code.INTERNAL ->
            "The notice service is temporarily unavailable. Check your connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "Could not load notices. Please try again."
    }
}
