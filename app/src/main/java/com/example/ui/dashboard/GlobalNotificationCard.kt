package com.batchfee.edu.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

@Composable
fun GlobalNotificationCard() {
    val db = FirebaseFirestore.getInstance()
    var notifications by remember { mutableStateOf<List<GlobalNotification>>(emptyList()) }
    var dismissedIds by remember { mutableStateOf(setOf<String>()) }
    var currentIndex by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listener: ListenerRegistration = db.collection("Global_Notifications")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                notifications = snapshot?.documents?.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    val status = d["status"] as? String ?: "active"
                    val expiresAt = (d["expiresAt"] as? Number)?.toLong()
                    if (status != "active") return@mapNotNull null
                    if (expiresAt != null && expiresAt < now) return@mapNotNull null
                    GlobalNotification(
                        id = doc.id,
                        message = d["message"] as? String ?: "",
                        sentAt = (d["sentAt"] as? Number)?.toLong() ?: 0L,
                        sender = d["senderName"] as? String ?: (d["sender"] as? String ?: "BatchFee Support")
                    )
                }?.sortedByDescending { it.sentAt } ?: emptyList()
            }
        onDispose { listener.remove() }
    }

    val activeNotifications = notifications.filter { it.id !in dismissedIds }
    if (activeNotifications.isEmpty()) return

    if (currentIndex >= activeNotifications.size) currentIndex = 0.coerceAtMost(activeNotifications.size - 1)
    val current = activeNotifications.getOrNull(currentIndex) ?: return

    val timeAgo = if (current.sentAt > 0) {
        val diff = System.currentTimeMillis() - current.sentAt
        when {
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            else -> "${diff / 86400000}d ago"
        }
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFA855F7).copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Campaign, null, tint = Color(0xFFA855F7), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current.sender}", color = Color(0xFFA855F7), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    if (timeAgo.isNotEmpty()) {
                        Text(" · $timeAgo", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                    if (activeNotifications.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${currentIndex + 1}/${activeNotifications.size}",
                            color = Color(0xFF94A3B8).copy(alpha = 0.6f),
                            fontSize = 9.sp
                        )
                    }
                }
                AnimatedContent(targetState = current.message, transitionSpec = { fadeIn() + slideInHorizontally { it / 4 } togetherWith fadeOut() + slideOutHorizontally { -it / 4 } }) { msg ->
                    Text(msg, color = Color(0xFFF8FAFC), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column {
                IconButton(
                    onClick = { dismissedIds = dismissedIds + current.id },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(Icons.Filled.Close, "Dismiss", tint = Color(0xFF94A3B8).copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                }
                if (activeNotifications.size > 1) {
                    Row {
                        IconButton(
                            onClick = { if (currentIndex > 0) currentIndex-- },
                            enabled = currentIndex > 0,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowLeft, "Previous", tint = Color(0xFFA855F7).copy(alpha = if (currentIndex > 0) 0.7f else 0.2f), modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = { if (currentIndex < activeNotifications.size - 1) currentIndex++ },
                            enabled = currentIndex < activeNotifications.size - 1,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowRight, "Next", tint = Color(0xFFA855F7).copy(alpha = if (currentIndex < activeNotifications.size - 1) 0.7f else 0.2f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

private data class GlobalNotification(
    val id: String,
    val message: String,
    val sentAt: Long,
    val sender: String
)
