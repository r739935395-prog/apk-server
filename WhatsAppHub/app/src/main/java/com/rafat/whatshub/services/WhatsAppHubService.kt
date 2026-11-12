package com.rafat.whatshub.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rafat.whatshub.MainActivity
import com.rafat.whatshub.R
import com.rafat.whatshub.WhatsAppHubApp
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.repository.IncomingMessageAlert
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.launch

/**
 * Persistent foreground service keeping up to 4 independent WhatsApp WebSocket sessions active
 * on Android 14 / 15 (Samsung One UI 7) and surfacing account-badged incoming notifications.
 */
class WhatsAppHubService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        promoteToForeground(connectedCount = 0)

        val app = application as WhatsAppHubApp
        val repo = app.repository

        lifecycleScope.launch {
            repo.initializeEngineAndRestoreSessions()
        }

        lifecycleScope.launch {
            repo.accounts.collect { accounts ->
                val connected = accounts.count { it.status == AccountStatus.CONNECTED }
                updateForegroundSummary(connected)
            }
        }

        lifecycleScope.launch {
            repo.incomingAlerts.collect { alert ->
                postMessageNotification(alert)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val connected = (application as WhatsAppHubApp).repository.accounts.value
            .count { it.status == AccountStatus.CONNECTED }
        promoteToForeground(connected)
        return START_STICKY
    }

    private fun promoteToForeground(connectedCount: Int) {
        val notification = buildForegroundNotification(connectedCount)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FGS_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FGS_NOTIFICATION_ID, notification)
            }
        }.onFailure { err ->
            SanitizedLogger.warn("FGS", "Foreground promotion deferred: ${err.javaClass.simpleName}")
        }
    }

    private fun updateForegroundSummary(connectedCount: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(FGS_NOTIFICATION_ID, buildForegroundNotification(connectedCount))
    }

    private fun buildForegroundNotification(connectedCount: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_FGS)
            .setSmallIcon(R.drawable.ic_notification_hub)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_body, connectedCount))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun postMessageNotification(alert: IncomingMessageAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        // Stable per-(accountId, canonicalChatJid) notification ID prevents duplicate stacking
        val notifId = 20_000 + ((alert.accountId * 31 + alert.chatJid.hashCode()) and 0x0FFFFFFF)
        val deepLinkIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACCOUNT_ID, alert.accountId)
            putExtra(MainActivity.EXTRA_CHAT_JID, alert.chatJid)
        }
        val pi = PendingIntent.getActivity(
            this,
            notifId,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accountColorArgb = when (alert.accountId) {
            1 -> 0xFF10B981.toInt()
            2 -> 0xFF3B82F6.toInt()
            3 -> 0xFF8B5CF6.toInt()
            4 -> 0xFFF59E0B.toInt()
            else -> 0xFF14B8A6.toInt()
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_hub)
            .setColor(accountColorArgb)
            .setSubText(alert.accountLabel)
            .setContentTitle(alert.chatTitle)
            .setContentText(alert.previewText)
            .setGroup("rafat_hub_acct_${alert.accountId}")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(this).notify(notifId, notification)
    }

    private fun ensureNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val fgsChannel = NotificationChannel(
            CHANNEL_FGS,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            getString(R.string.msg_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(fgsChannel)
        nm.createNotificationChannel(msgChannel)
    }

    companion object {
        const val CHANNEL_FGS = "whatshub_active_connections"
        const val CHANNEL_MESSAGES = "whatshub_incoming_messages"
        private const val FGS_NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, WhatsAppHubService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                SanitizedLogger.warn("FGS", "Service start deferred by OS background policy")
            }
        }
    }
}
