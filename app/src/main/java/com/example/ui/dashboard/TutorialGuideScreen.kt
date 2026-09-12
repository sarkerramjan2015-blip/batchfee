package com.batchfee.edu.ui.dashboard

import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.viewinterop.AndroidView
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.repository.AppTutorial
import com.batchfee.edu.data.repository.NoticeCenterRepository
import kotlinx.coroutines.launch

private val TutorialBackground = Color(0xFF07111F)
private val TutorialCard = Color(0xFF0F172A)
private val TutorialBorder = Color(0xFF263348)
private val TutorialText = Color(0xFFF8FAFC)
private val TutorialMuted = Color(0xFF94A3B8)
private val TutorialCyan = Color(0xFF22D3EE)
private val TutorialBlue = Color(0xFF3B82F6)
private val TutorialAmber = Color(0xFFF59E0B)

/**
 * Tenant-facing tutorial library. Videos stay inside a contained WebView so a
 * guide feels like part of BatchFee; only Root-approved YouTube video IDs can
 * reach this player through the callable boundary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialGuideScreen(onBack: () -> Unit) {
    val repository = remember { NoticeCenterRepository() }
    val scope = rememberCoroutineScope()
    var tutorials by remember { mutableStateOf<List<AppTutorial>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTutorial by remember { mutableStateOf<AppTutorial?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.tutorials() }
                .onSuccess { tutorials = it }
                .onFailure { throwable ->
                    FirebaseFailureReporter.report(
                        throwable,
                        operation = "load app tutorials",
                        permissionDeniedIsExpected = true
                    )
                    error = throwable.message ?: "Could not load tutorials. Check your connection and try again."
                }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        containerColor = TutorialBackground,
        topBar = {
            TopAppBar(
                title = { Text("App Guide & Tutorials", color = TutorialText, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TutorialText)
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, "Refresh tutorials", tint = TutorialCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TutorialBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(TutorialBackground),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TutorialCard),
                    border = BorderStroke(1.dp, TutorialBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Filled.VideoLibrary, null, tint = TutorialCyan, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Learn BatchFee step by step", color = TutorialText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "Choose a guide to play it inside BatchFee. New guides are managed by the BatchFee Team.",
                                color = TutorialMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
            when {
                loading -> item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = TutorialCyan, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                }
                error != null -> item {
                    TutorialStateCard(
                        message = error.orEmpty(),
                        actionLabel = "Try again",
                        onAction = { refresh() }
                    )
                }
                tutorials.isEmpty() -> item {
                    TutorialStateCard(
                        message = "No guide has been published yet. Please check again later.",
                        actionLabel = "Refresh",
                        onAction = { refresh() }
                    )
                }
                else -> items(tutorials, key = { it.tutorialId }) { tutorial ->
                    TutorialCardItem(tutorial = tutorial, onOpen = { selectedTutorial = tutorial })
                }
            }
        }
    }

    selectedTutorial?.let { tutorial ->
        TutorialPlayerDialog(tutorial = tutorial, onDismiss = { selectedTutorial = null })
    }
}

@Composable
private fun TutorialStateCard(message: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TutorialCard),
        border = BorderStroke(1.dp, TutorialBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.VideoLibrary, null, tint = TutorialAmber, modifier = Modifier.size(28.dp))
            Text(message, color = TutorialMuted, fontSize = 13.sp, lineHeight = 18.sp)
            TextButton(onClick = onAction) { Text(actionLabel, color = TutorialCyan) }
        }
    }
}

@Composable
private fun TutorialCardItem(tutorial: AppTutorial, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TutorialCard),
        border = BorderStroke(1.dp, TutorialBorder)
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(TutorialBlue.copy(alpha = 0.20f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.PlayCircle, null, tint = TutorialCyan, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tutorial.category, color = TutorialCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(tutorial.title, color = TutorialText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tutorial.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(tutorial.description, color = TutorialMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TutorialPlayerDialog(tutorial: AppTutorial, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TutorialCard,
        title = {
            Column {
                Text(tutorial.title, color = TutorialText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (tutorial.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(tutorial.description, color = TutorialMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EmbeddedYouTubeTutorial(videoId = tutorial.youtubeVideoId)
                Text(
                    "This guide plays inside BatchFee. Playback availability and any advertising are controlled by YouTube.",
                    color = TutorialMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = TutorialCyan) } }
    )
}

@Composable
private fun EmbeddedYouTubeTutorial(videoId: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                tag = videoId
                loadUrl(youtubeEmbedUrl(videoId))
            }
        },
        update = { webView ->
            if (webView.tag != videoId) {
                webView.tag = videoId
                webView.loadUrl(youtubeEmbedUrl(videoId))
            }
        }
    )
}

private fun youtubeEmbedUrl(videoId: String): String =
    "https://www.youtube-nocookie.com/embed/$videoId?playsinline=1&rel=0&modestbranding=1&controls=1&fs=1&iv_load_policy=3"
