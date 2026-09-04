package com.batchfee.edu.ui.students

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.appendInstituteSignature
import com.batchfee.edu.domain.loadInstituteSignature
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.ui.components.buildWhatsAppUrl
import com.example.domain.BulkMessageController
import com.example.domain.BulkMessagePreferences
import com.example.ui.components.BulkActionBar
import com.example.ui.components.BulkMessageDialog
import com.example.ui.components.BulkSelectionTopBar
import com.example.ui.components.BulkSendProgressPanel
import com.example.ui.components.SelectionBadge
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ── Colors (matching PricingScreen premium theme) ───────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
private val WAGreen       = Color(0xFF25D366)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val ModalBg       = Color(0xFF0B1626)
private val CloseSoftRed  = Color(0xFFFFA3A3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onAddStudent: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToIdCards: () -> Unit = {}
) {
    val viewModel: StudentViewModel = viewModel(factory = StudentViewModelFactory(db))
    val students by viewModel.studentList.collectAsState()
    val batches by viewModel.batchList.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val instId = SessionManager.currentInstituteId.value
    var instituteSignature by remember { mutableStateOf("") }

    LaunchedEffect(instId) {
        instituteSignature = loadInstituteSignature(db, instId)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(true) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showStudentsMenu by remember { mutableStateOf(false) }
    var showMessageDialog by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf("") }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var selectedStatus by remember { mutableStateOf("active") }
    var sortBy by remember { mutableStateOf("name") }
    var batchStudentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeBatchEnrollments by remember { mutableStateOf(emptyList<com.batchfee.edu.data.models.BatchStudentEntity>()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by rememberSaveable(stateSaver = listSaver(
        save = { it.toList() },
        restore = { it.toSet() }
    )) { mutableStateOf(setOf<String>()) }
    var showBulkComposer by remember { mutableStateOf(false) }
    var bulkChannel by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.bulkSender.onPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.bulkSender.onResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bulkState by viewModel.bulkSender.state.collectAsState()

    fun clearSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(studentId: String) {
        selectedIds = if (studentId in selectedIds) selectedIds - studentId else selectedIds + studentId
        if (selectedIds.isEmpty()) selectionMode = false
    }

    LaunchedEffect(instId) {
        if (instId == null) {
            activeBatchEnrollments = emptyList()
        } else {
            db.batchStudentDao().getActiveEnrollmentsForInstitute(instId).collect { enrollments ->
                activeBatchEnrollments = enrollments
            }
        }
    }

    LaunchedEffect(selectedBatchId) {
        val batchId = selectedBatchId
        val instId = SessionManager.currentInstituteId.value
        if (batchId == null || instId == null) {
            batchStudentIds = emptySet()
        } else {
            db.batchStudentDao().getStudentsForBatch(batchId, instId).collect { batchStudents ->
                batchStudentIds = batchStudents.map { it.id }.toSet()
            }
        }
    }

    val filteredStudents = remember(students, searchQuery, selectedBatchId, batchStudentIds, selectedStatus, sortBy) {
        students
            .asSequence()
            .filter { student ->
                searchQuery.isBlank() ||
                    student.fullName.contains(searchQuery, ignoreCase = true) ||
                    student.studentCode.contains(searchQuery, ignoreCase = true) ||
                    (student.phone?.contains(searchQuery) == true)
            }
            .filter { student -> selectedBatchId == null || student.id in batchStudentIds }
            .filter { student -> selectedStatus == "any" || student.status.equals(selectedStatus, ignoreCase = true) }
            .let { sequence ->
                when (sortBy) {
                    "roll" -> sequence.sortedBy { it.studentCode.lowercase() }
                    else -> sequence.sortedBy { it.fullName.lowercase() }
                }
            }
            .toList()
    }

    val batchNamesByStudent = remember(activeBatchEnrollments, batches) {
        val batchNames = batches.associate { it.id to it.name }
        activeBatchEnrollments
            .groupBy { it.studentId }
            .mapValues { (_, enrollments) ->
                enrollments.mapNotNull { batchNames[it.batchId] }.distinct().sorted()
            }
    }

    fun sendMessage(useWhatsApp: Boolean) {
        val message = appendInstituteSignature(messageText.trim(), instituteSignature)
        if (message.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Write a message first.") }
            return
        }

        val phones = filteredStudents.mapNotNull { it.phone?.filter(Char::isDigit)?.takeIf(String::isNotBlank) }
        if (phones.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("No phone numbers found for selected students.") }
            return
        }

        val intent = if (useWhatsApp) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${phones.first()}?text=${Uri.encode(message)}"))
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phones.joinToString(";")}")).apply {
                putExtra("sms_body", message)
            }
        }
        runCatching { context.startActivity(intent) }
            .onFailure { scope.launch { snackbarHostState.showSnackbar("No app found to send this message.") } }
    }

    fun startBulkSend(channel: String, delayMs: Long) {
        val message = appendInstituteSignature(messageText.trim(), instituteSignature)
        if (message.isBlank()) return
        val targets = filteredStudents
            .filter { it.id in selectedIds }
            .map { BulkMessageController.BulkTarget(key = it.id, name = it.fullName, phone = it.phone) }
        if (targets.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("Select at least one student.") }
            return
        }
        BulkMessagePreferences.setDelayMs(context, delayMs)
        val started = viewModel.bulkSender.start(
            targets = targets,
            channel = channel,
            delayMs = delayMs,
            messageBuilder = { message },
            launcher = { target, body ->
                if (channel == "whatsapp") {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(buildWhatsAppUrl(target.phone, body)))
                        )
                    }.isSuccess
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${target.phone?.filter(Char::isDigit).orEmpty()}"))
                                .apply { putExtra("sms_body", body) }
                        )
                    }.isSuccess
                }
            }
        )
        if (!started) {
            scope.launch { snackbarHostState.showSnackbar("Sending is already in progress.") }
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                BulkSelectionTopBar(
                    selectedCount = selectedIds.size,
                    totalCount = filteredStudents.size,
                    onClear = { clearSelection() },
                    onSelectAll = {
                        selectedIds = if (selectedIds.size == filteredStudents.size) emptySet()
                        else filteredStudents.map { it.id }.toSet()
                        if (selectedIds.isEmpty()) selectionMode = false
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Students", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 21.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextWhite, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(Icons.Filled.FilterList, contentDescription = "Filter", tint = TextWhite, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { showStudentsMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Student menu", tint = TextWhite, modifier = Modifier.size(28.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = onAddStudent,
                    containerColor = Color.Transparent, // Use custom brush
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Student", tint = Color.White)
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                BulkActionBar(
                    selectedCount = selectedIds.size,
                    onWhatsApp = {
                        bulkChannel = "whatsapp"
                        showBulkComposer = true
                    },
                    onSms = {
                        bulkChannel = "sms"
                        showBulkComposer = true
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            // ── Search bar ───────────────────────────────────
            if (showSearch || searchQuery.isNotBlank()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name, ID, or phone", color = TextMuted.copy(alpha = 0.5f), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = BorderSub,
                        focusedContainerColor = CardBg,
                        unfocusedContainerColor = CardBg,
                        cursorColor = Cyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(2.dp))
            }

            // ── Student count ────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildString {
                        append("${filteredStudents.size} student${if (filteredStudents.size != 1) "s" else ""}")
                        selectedBatchId?.let { batchId ->
                            batches.find { it.id == batchId }?.let { append(" in ${it.name}") }
                        }
                    },
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showFilterDialog = true }
                ) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (selectedStatus == "any") "All" else selectedStatus.replaceFirstChar { it.uppercase() },
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            // ── Student list ─────────────────────────────────
            if (filteredStudents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.PersonOff,
                            contentDescription = null,
                            tint = TextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "No matching students"
                            else "No students yet.\nTap + to add your first student.",
                            color = TextMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 120.dp) // Clear FAB / bulk bar
                ) {
                    items(filteredStudents, key = { it.id }) { student ->
                        StudentCard(
                            student = student,
                            batchNames = batchNamesByStudent[student.id].orEmpty(),
                            selected = student.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(student.id)
                                } else {
                                    onNavigateToProfile(student.id)
                                }
                            },
                            onLongPress = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(student.id)
                                } else {
                                    toggleSelection(student.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            modifier = Modifier.fillMaxWidth(0.90f),
            containerColor = ModalBg,
            shape = RoundedCornerShape(22.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Students Filter", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showFilterDialog = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = CloseSoftRed, modifier = Modifier.size(28.dp))
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Select Batch", color = TextMuted, fontSize = 14.sp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardBgAlt)
                            .border(1.dp, Cyan.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
                    ) {
                        FilterChoiceRow(Icons.Filled.Groups, "All Batches", selectedBatchId == null) {
                            selectedBatchId = null
                        }
                        batches.forEach { batch ->
                            HorizontalDivider(color = BorderSub)
                            FilterChoiceRow(Icons.Filled.Class, batch.name, selectedBatchId == batch.id) {
                                selectedBatchId = batch.id
                            }
                        }
                    }
                    DialogChipRow(
                        label = "Sort by",
                        options = listOf("name" to "Name", "roll" to "Roll No."),
                        selected = sortBy,
                        onSelect = { sortBy = it }
                    )
                    DialogChipRow(
                        label = "Status",
                        options = listOf("any" to "Any", "active" to "Active", "inactive" to "Inactive"),
                        selected = selectedStatus,
                        onSelect = { selectedStatus = it }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFilterDialog = false },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = BgColor)
                ) {
                    Text("Apply Filter", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showStudentsMenu) {
        AlertDialog(
            onDismissRequest = { showStudentsMenu = false },
            modifier = Modifier.fillMaxWidth(0.90f),
            containerColor = ModalBg,
            shape = RoundedCornerShape(22.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Students Menu", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showStudentsMenu = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = CloseSoftRed, modifier = Modifier.size(28.dp))
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudentMenuRow(Icons.Filled.Badge, "ID Cards", "Generate ID cards in bulk") {
                        showStudentsMenu = false
                        onNavigateToIdCards()
                    }
                    HorizontalDivider(color = BorderSub.copy(alpha = 0.85f), modifier = Modifier.padding(start = 58.dp))
                    StudentMenuRow(Icons.Filled.FileUpload, "Export", "You can export student list here") {
                        showStudentsMenu = false
                        scope.launch { snackbarHostState.showSnackbar("Export is coming soon.") }
                    }
                    HorizontalDivider(color = BorderSub.copy(alpha = 0.85f), modifier = Modifier.padding(start = 58.dp))
                    StudentMenuRow(Icons.Filled.Message, "Message", "You can send message to selected students here") {
                        showStudentsMenu = false
                        showMessageDialog = true
                    }
                    HorizontalDivider(color = BorderSub.copy(alpha = 0.85f), modifier = Modifier.padding(start = 58.dp))
                    StudentMenuRow(Icons.Filled.FileDownload, "Import Students", "You can import students using file") {
                        showStudentsMenu = false
                        scope.launch { snackbarHostState.showSnackbar("Import students is coming soon.") }
                    }
                    HorizontalDivider(color = BorderSub.copy(alpha = 0.85f), modifier = Modifier.padding(start = 58.dp))
                    StudentMenuRow(Icons.Filled.Download, "Sample File For Import student", "Download sample file for import students") {
                        showStudentsMenu = false
                        scope.launch { snackbarHostState.showSnackbar("Sample file download is coming soon.") }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showMessageDialog) {
        AlertDialog(
            onDismissRequest = { showMessageDialog = false },
            modifier = Modifier.fillMaxWidth(0.90f),
            containerColor = ModalBg,
            shape = RoundedCornerShape(22.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Message", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showMessageDialog = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = CloseSoftRed, modifier = Modifier.size(28.dp))
                    }
                }
            },
            text = {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("Message", color = TextMuted.copy(alpha = 0.75f)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Cyan.copy(alpha = 0.28f),
                        focusedContainerColor = CardBgAlt,
                        unfocusedContainerColor = CardBgAlt,
                        cursorColor = Cyan
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            sendMessage(useWhatsApp = true)
                            showMessageDialog = false
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) {
                        Text("WhatsApp", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Button(
                        onClick = {
                            sendMessage(useWhatsApp = false)
                            showMessageDialog = false
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = BgColor)
                    ) {
                        Text("SMS", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        )
    }

    if (showBulkComposer) {
        BulkMessageDialog(
            title = "Bulk Message",
            recipientCount = selectedIds.size,
            messageText = messageText,
            onMessageChange = { messageText = it },
            initialDelaySeconds = (BulkMessagePreferences.getDelayMs(context) / 1000L).toInt(),
            onStartWhatsApp = { delayMs ->
                startBulkSend("whatsapp", delayMs)
                showBulkComposer = false
                clearSelection()
            },
            onStartSms = { delayMs ->
                startBulkSend("sms", delayMs)
                showBulkComposer = false
                clearSelection()
            },
            onDismiss = { showBulkComposer = false }
        )
    }

    if (bulkState.active) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            BulkSendProgressPanel(
                state = bulkState,
                onRetryFailed = { viewModel.bulkSender.retryFailed() },
                onStop = { viewModel.bulkSender.cancel() },
                onClose = { viewModel.bulkSender.reset() }
            )
        }
    }
}

// ── Student Card ────────────────────────────────────────────────
@Composable
private fun FilterChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Cyan else TextMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = if (selected) TextWhite else TextMuted,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Cyan)
        }
    }
}

@Composable
private fun DialogChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(82.dp))
        options.forEach { (value, title) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(title, fontSize = 13.sp, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Cyan.copy(alpha = 0.14f),
                    selectedLabelColor = Cyan,
                    containerColor = CardBgAlt,
                    labelColor = TextWhite.copy(alpha = 0.82f)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == value,
                    borderColor = Color.Transparent,
                    selectedBorderColor = Cyan,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp
                )
            )
        }
    }
}

@Composable
private fun StudentMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Cyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = TextMuted.copy(alpha = 0.76f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StudentCard(
    student: StudentEntity,
    batchNames: List<String>,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    val context = LocalContext.current
    val statusColor = when (student.status.lowercase()) {
        "active" -> WAGreen
        "inactive" -> Color(0xFFF59E0B)
        else -> TextMuted
    }

    // polish: Card provides the ripple + elevation press animation; the pointerInput
    // inside handles tap (navigate / toggle) and long-press (enter selection mode).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) CardBgAlt else CardBg),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Cyan else BorderSub)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(selectionMode, selected) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongPress() }
                    )
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                SelectionBadge(selected = selected)
                Spacer(Modifier.width(12.dp))
            }
            // The initial remains beneath the image as a dependable fallback while a
            // private student photo is being fetched or when no photo was added.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    student.fullName.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                student.photoUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { reference ->
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(FirebaseStorageImageUploadHelper.displaySource(context, reference))
                                .crossfade(true)
                                .build(),
                            contentDescription = "${student.fullName}'s photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Name row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        student.fullName,
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    // Status badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            student.status.replaceFirstChar { it.uppercase() },
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (batchNames.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .widthIn(max = 90.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(SkyBlue.copy(alpha = 0.14f))
                                .border(1.dp, SkyBlue.copy(alpha = 0.26f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append(batchNames.first())
                                    if (batchNames.size > 1) append(" +${batchNames.size - 1}")
                                },
                                color = SkyBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Phone + student code
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Phone, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        student.phone ?: "No phone",
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        student.studentCode,
                        color = SkyBlue.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Chevron
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

