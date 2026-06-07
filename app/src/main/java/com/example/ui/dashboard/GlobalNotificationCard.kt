package com.example.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
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
import com.google.firebase.firestore.Query

@Composable
fun GlobalNotificationCard() {
    val db = FirebaseFirestore.getInstance()
    var message by remember { mutableStateOf<String?>(null) }
    var sentAt by remember { mutableStateOf<Long?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: ListenerRegistration = db.collection("Global_Notifications")
            .orderBy("sentAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    return@addSnapshotListener
                }
                if (snapshot != null && !snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    message = doc.getString("message")
                    sentAt = doc.getLong("sentAt")
                }
            }
        onDispose { listener.remove() }
    }

    if (dismissed || message.isNullOrBlank()) return

    val timeAgo = sentAt?.let { ts ->
        val diff = System.currentTimeMillis() - ts
        when {
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            else -> "${diff / 86400000}d ago"
        }
    } ?: ""

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
                Text("System Announcement", color = Color(0xFFA855F7), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(message!!, color = Color(0xFFF8FAFC), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (timeAgo.isNotEmpty()) Text(timeAgo, color = Color(0xFF94A3B8), fontSize = 10.sp)
            }
            IconButton(onClick = { dismissed = true }) {
                Icon(Icons.Filled.Close, "Dismiss", tint = Color(0xFF94A3B8).copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
