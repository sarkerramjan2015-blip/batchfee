package com.batchfee.edu.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.batchfee.edu.MainActivity
import com.batchfee.edu.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Displays only trusted BatchFee notice payloads received from Cloud Functions. */
class BatchFeeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // If the user is still signed in, immediately replace the old server
        // token. A fresh registration is also performed on every later login.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            NoticePushRegistration.registerCurrentTenantDevice()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.data["title"] ?: message.notification?.title ?: "BatchFee notice"
        val body = message.data["body"] ?: message.notification?.body ?: "You have a new update."
        showNotice(title.take(120), body.take(3_000))
    }

    private fun showNotice(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BatchFee notices", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Important notices from BatchFee"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "batchfee_notices"
    }
}
