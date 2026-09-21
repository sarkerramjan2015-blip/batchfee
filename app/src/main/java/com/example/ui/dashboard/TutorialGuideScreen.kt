package com.batchfee.edu.ui.dashboard

import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                EmbeddedYouTubeTutorial(
                    videoId = tutorial.youtubeVideoId,
                    videoLayout = tutorial.videoLayout
                )
                if (tutorial.videoLayout == "portrait") {
                    Text(
                        "This YouTube Short is shown in its original portrait frame.",
                        color = TutorialMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
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
private fun EmbeddedYouTubeTutorial(videoId: String, videoLayout: String) {
    val isPortrait = videoLayout == "portrait"
    var playerWebView by remember(videoId) { mutableStateOf<WebView?>(null) }
    var fullScreenView by remember(videoId) { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember(videoId) {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

    val closeFullScreen: () -> Unit = {
        val callback = fullScreenCallback
        fullScreenView = null
        fullScreenCallback = null
        callback?.onCustomViewHidden()
    }
    val playerChromeClient = remember(videoId) {
        object : WebChromeClient() {
            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (view == null || callback == null) return
                if (fullScreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullScreenView = view
                fullScreenCallback = callback
            }

            override fun onHideCustomView() {
                fullScreenView = null
                fullScreenCallback = null
            }
        }
    }

    DisposableEffect(videoId) {
        onDispose {
            fullScreenCallback?.onCustomViewHidden()
            fullScreenView = null
            fullScreenCallback = null
            playerWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            playerWebView = null
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // A portrait frame is deliberately capped so the dialog remains usable
        // on compact phones. Landscape guides still use the full available width.
        val frameModifier = if (isPortrait) {
            Modifier
                .fillMaxWidth(0.70f)
                .widthIn(max = 260.dp)
                .aspectRatio(9f / 16f)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        }
        AndroidView(
        modifier = frameModifier.clip(RoundedCornerShape(12.dp)),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                webChromeClient = playerChromeClient
                tag = videoId
                loadYouTubePlayer(videoId)
                playerWebView = this
            }
        },
        update = { webView ->
            if (webView.tag != videoId) {
                webView.tag = videoId
                webView.loadYouTubePlayer(videoId)
            }
        }
        )
    }

    fullScreenView?.let { videoView ->
        Dialog(
            onDismissRequest = closeFullScreen,
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            BackHandler(onBack = closeFullScreen)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        (videoView.parent as? ViewGroup)?.removeView(videoView)
                        videoView
                    }
                )
                IconButton(
                    onClick = closeFullScreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp)
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.58f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Exit full screen",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private const val YOUTUBE_PLAYER_BASE_URL = "https://batchfee-477b8.web.app/"

private fun WebView.loadYouTubePlayer(videoId: String) {
    loadDataWithBaseURL(
        YOUTUBE_PLAYER_BASE_URL,
        youtubePlayerHtml(videoId),
        "text/html",
        "UTF-8",
        null
    )
}

private fun youtubePlayerHtml(videoId: String): String = """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>
          html, body { width: 100%; height: 100%; margin: 0; background: #000; overflow: hidden; }
          iframe { position: fixed; inset: 0; width: 100%; height: 100%; border: 0; }
        </style>
      </head>
      <body>
        <iframe
          src="https://www.youtube-nocookie.com/embed/$videoId?playsinline=1&amp;rel=0&amp;controls=1&amp;fs=1&amp;enablejsapi=1&amp;origin=https%3A%2F%2Fbatchfee-477b8.web.app&amp;widget_referrer=https%3A%2F%2Fbatchfee-477b8.web.app%2F"
          title="BatchFee tutorial video"
          referrerpolicy="strict-origin-when-cross-origin"
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
          allowfullscreen>
        </iframe>
      </body>
    </html>
""".trimIndent()
