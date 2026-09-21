package com.batchfee.edu.ui.exams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.repository.CuratedQuestion
import com.batchfee.edu.data.repository.QuestionBankFilters
import com.batchfee.edu.data.repository.QuestionBankLibraryRepository
import com.batchfee.edu.data.repository.ReviewableQuestion
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.launch

private val LibraryBg = Color(0xFF07111F)
private val LibraryCard = Color(0xFF101B30)
private val LibraryBorder = Color(0xFF263851)
private val LibraryText = Color(0xFFF8FAFC)
private val LibraryMuted = Color(0xFF9AAAC2)
private val LibraryCyan = Color(0xFF22D3EE)
private val LibraryGreen = Color(0xFF34D399)

/** Uses only Super Admin-approved global questions. No AI call or source scan is required. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuratedQuestionBankScreen(
    db: AppDatabase,
    onBack: () -> Unit,
) {
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repository = remember { QuestionBankLibraryRepository() }
    val scope = rememberCoroutineScope()
    var curriculum by rememberSaveable { mutableStateOf("") }
    var syllabusYear by rememberSaveable { mutableStateOf("") }
    var className by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var chapter by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var difficulty by rememberSaveable { mutableStateOf("") }
    var paperTitle by rememberSaveable { mutableStateOf("Question Paper") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var questions by remember { mutableStateOf<List<CuratedQuestion>>(emptyList()) }
    var nextPageToken by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var preparedQuestions by remember { mutableStateOf<List<ReviewableQuestion>?>(null) }

    fun filters() = QuestionBankFilters(
        curriculum = curriculum,
        syllabusYear = syllabusYear,
        className = className,
        subject = subject,
        chapter = chapter,
        type = type,
        difficulty = difficulty,
        search = search,
    )
    LaunchedEffect(instituteId, reloadKey) {
        val id = instituteId
        if (id.isNullOrBlank()) {
            loading = false
            error = "Open the question bank from an institute account."
        } else {
            loading = true
            error = null
            runCatching { repository.listPage(id, filters()) }
                .onSuccess {
                    questions = it.questions
                    nextPageToken = it.nextPageToken
                }
                .onFailure { error = it.message ?: "Could not load the approved question bank." }
            loading = false
        }
    }

    fun loadMore() {
        val id = instituteId
        val cursor = nextPageToken
        if (id.isNullOrBlank() || cursor.isNullOrBlank() || loadingMore) return
        loadingMore = true
        error = null
        scope.launch {
            runCatching { repository.listPage(id, filters(), pageToken = cursor) }
                .onSuccess { page ->
                    questions = (questions + page.questions).distinctBy(CuratedQuestion::id)
                    nextPageToken = page.nextPageToken
                }
                .onFailure { error = it.message ?: "Could not load more approved questions." }
            loadingMore = false
        }
    }

    fun preparePaper() {
        val id = instituteId
        if (id.isNullOrBlank() || selectedIds.isEmpty()) return
        preparing = true
        error = null
        scope.launch {
            runCatching { repository.preparePaper(id, selectedIds) }
                .onSuccess { preparedQuestions = it }
                .onFailure { error = it.message ?: "Selected questions could not be prepared." }
            preparing = false
        }
    }

    preparedQuestions?.let { prepared ->
        val first = questions.firstOrNull { it.id == prepared.firstOrNull()?.sourceQuestionId }
        QuestionPaperComposerDialog(
            db = db,
            instituteId = instituteId,
            examName = paperTitle,
            className = className.ifBlank { first?.className.orEmpty() },
            subject = subject.ifBlank { first?.subject.orEmpty() },
            chapter = chapter.ifBlank { first?.chapter.orEmpty() },
            totalMarks = prepared.sumOf { it.marks },
            durationMinutes = 0,
            questions = prepared,
            onDismiss = { preparedQuestions = null },
        )
    }

    Scaffold(
        containerColor = LibraryBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Approved Question Bank", color = LibraryText, fontWeight = FontWeight.Bold)
                        Text("Curated questions - no AI generation needed", color = LibraryMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LibraryText)
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey += 1 }, enabled = !loading && !preparing) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = LibraryCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LibraryBg),
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                Card(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = LibraryCard),
                    border = BorderStroke(1.dp, LibraryBorder),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${selectedIds.size} selected", color = LibraryText, fontWeight = FontWeight.Bold)
                            Text("Canonical approved questions will be used in your PDF.", color = LibraryMuted, fontSize = 11.sp)
                        }
                        Button(
                            onClick = ::preparePaper,
                            enabled = !preparing,
                            colors = ButtonDefaults.buttonColors(containerColor = LibraryCyan, contentColor = LibraryBg),
                        ) {
                            if (preparing) {
                                CircularProgressIndicator(Modifier.width(17.dp).height(17.dp), color = LibraryBg, strokeWidth = 2.dp)
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(if (preparing) "Preparing…" else "Create paper")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LibraryFilterCard(
                    paperTitle = paperTitle,
                    onPaperTitleChange = { paperTitle = it.take(120) },
                    curriculum = curriculum,
                    onCurriculumChange = { curriculum = it.take(120) },
                    syllabusYear = syllabusYear,
                    onSyllabusYearChange = { syllabusYear = it.take(20) },
                    className = className,
                    onClassNameChange = { className = it.take(120) },
                    subject = subject,
                    onSubjectChange = { subject = it.take(160) },
                    chapter = chapter,
                    onChapterChange = { chapter = it.take(200) },
                    search = search,
                    onSearchChange = { search = it.take(120) },
                    type = type,
                    onTypeChange = { type = it },
                    difficulty = difficulty,
                    onDifficultyChange = { difficulty = it },
                    onSearch = { reloadKey += 1 },
                    loading = loading,
                )
            }
            error?.let { message -> item { Text(message, color = Color(0xFFFCA5A5), fontSize = 13.sp) } }
            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = LibraryCyan)
                    }
                }
            } else if (questions.isEmpty() && error == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = LibraryCard), border = BorderStroke(1.dp, LibraryBorder)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.LibraryBooks, null, tint = LibraryCyan)
                            Spacer(Modifier.height(10.dp))
                            Text("No approved questions match", color = LibraryText, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Try broader filters, or wait for Super Admin to approve contributed questions.", color = LibraryMuted, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                item { Text("${questions.size} approved question${if (questions.size == 1) "" else "s"}", color = LibraryMuted, fontSize = 13.sp) }
                items(questions, key = CuratedQuestion::id) { question ->
                    CuratedQuestionCard(
                        question = question,
                        selected = question.id in selectedIds,
                        onSelect = { selected ->
                            selectedIds = if (selected) selectedIds + question.id else selectedIds - question.id
                        },
                    )
                }
                if (nextPageToken != null) {
                    item {
                        OutlinedButton(
                            onClick = ::loadMore,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loadingMore,
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(
                                    Modifier.width(16.dp).height(16.dp),
                                    strokeWidth = 2.dp,
                                    color = LibraryCyan,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (loadingMore) "Loading more..." else "Load more approved questions")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryFilterCard(
    paperTitle: String,
    onPaperTitleChange: (String) -> Unit,
    curriculum: String,
    onCurriculumChange: (String) -> Unit,
    syllabusYear: String,
    onSyllabusYearChange: (String) -> Unit,
    className: String,
    onClassNameChange: (String) -> Unit,
    subject: String,
    onSubjectChange: (String) -> Unit,
    chapter: String,
    onChapterChange: (String) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    type: String,
    onTypeChange: (String) -> Unit,
    difficulty: String,
    onDifficultyChange: (String) -> Unit,
    onSearch: () -> Unit,
    loading: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = LibraryCard), border = BorderStroke(1.dp, LibraryBorder)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Find approved questions", color = LibraryText, fontWeight = FontWeight.Bold)
            OutlinedTextField(paperTitle, onPaperTitleChange, Modifier.fillMaxWidth(), label = { Text("Paper title") }, singleLine = true)
            OutlinedTextField(curriculum, onCurriculumChange, Modifier.fillMaxWidth(), label = { Text("Curriculum (optional)") }, singleLine = true)
            OutlinedTextField(syllabusYear, onSyllabusYearChange, Modifier.fillMaxWidth(), label = { Text("Syllabus year (optional)") }, singleLine = true)
            OutlinedTextField(className, onClassNameChange, Modifier.fillMaxWidth(), label = { Text("Class") }, singleLine = true)
            OutlinedTextField(subject, onSubjectChange, Modifier.fillMaxWidth(), label = { Text("Subject") }, singleLine = true)
            OutlinedTextField(chapter, onChapterChange, Modifier.fillMaxWidth(), label = { Text("Chapter") }, singleLine = true)
            OutlinedTextField(search, onSearchChange, Modifier.fillMaxWidth(), label = { Text("Search text or topic") }, singleLine = true)
            Text("Question type", color = LibraryMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("" to "Any", "mcq" to "MCQ", "short" to "Short", "creative" to "CQ").forEach { (value, label) ->
                    FilterChip(selected = type == value, onClick = { onTypeChange(value) }, label = { Text(label) }, colors = libraryChipColors())
                }
            }
            Text("Difficulty", color = LibraryMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("" to "Any", "easy" to "Easy", "medium" to "Medium", "hard" to "Hard").forEach { (value, label) ->
                    FilterChip(selected = difficulty == value, onClick = { onDifficultyChange(value) }, label = { Text(label) }, colors = libraryChipColors())
                }
            }
            Button(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = LibraryCyan, contentColor = LibraryBg),
            ) { Text("Search approved bank") }
        }
    }
}

@Composable
private fun libraryChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = LibraryCyan.copy(alpha = .18f), selectedLabelColor = LibraryCyan, labelColor = LibraryMuted,
)

@Composable
private fun CuratedQuestionCard(question: CuratedQuestion, selected: Boolean, onSelect: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) LibraryCard.copy(red = .08f, green = .15f, blue = .22f) else LibraryCard),
        border = BorderStroke(1.dp, if (selected) LibraryCyan.copy(alpha = .65f) else LibraryBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onSelect)
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(question.curriculum, question.syllabusYear, question.className, question.subject, question.chapter)
                            .filter(String::isNotBlank)
                            .joinToString("  •  "),
                        color = LibraryCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${question.type.uppercase()}  •  ${question.difficulty}  •  ${question.marks} mark${if (question.marks == 1) "" else "s"}", color = LibraryMuted, fontSize = 11.sp)
                }
            }
            if (question.chapterName.isNotBlank()) {
                Text("Chapter title: ${question.chapterName}", color = LibraryMuted, fontSize = 11.sp)
            }
            if (question.patternKey != "standard" || question.patternVariant.isNotBlank()) {
                Text("Pattern: ${question.patternKey} ${question.patternVariant.replace("|", " · ")}".trim(), color = LibraryMuted, fontSize = 11.sp)
            }
            Text(question.questionText, color = LibraryText, fontWeight = FontWeight.SemiBold)
            question.options.forEachIndexed { index, option ->
                Text("${('A'.code + index).toChar()}. $option", color = LibraryMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("Answer: ${question.correctAnswer}", color = LibraryGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (question.explanation.isNotBlank()) Text(question.explanation, color = LibraryMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
