package com.rafat.whatshub.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rafat.whatshub.MainActivity
import com.rafat.whatshub.R
import com.rafat.whatshub.WhatsAppHubApp
import com.rafat.whatshub.database.AccountPalette
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.repository.IncomingMessageAlert
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WhatsAppHubService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectivityMonitor: ConnectivityMonitor? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        val app = application as WhatsAppHubApp
        val repo = app.repository

        startForegroundSafely(buildPersistentNotification(0))

        repo.initializeEngineAndRestoreSessions()
        connectivityMonitor = ConnectivityMonitor(this, repo).also { it.start() }

        serviceScope.launch {
            repo.accounts.collect { list ->
                val connectedCount = list.count { it.status == AccountStatus.CONNECTED }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(FGS_NOTIFICATION_ID, buildPersistentNotification(connectedCount))
            }
        }

        serviceScope.launch {
            repo.incomingAlerts.collect { alert ->
                postIncomingMessageNotification(alert)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            SanitizedLogger.info("Service", "User requested background service stop")
            val app = application as WhatsAppHubApp
            serviceScope.launch(Dispatchers.IO) {
                for (acct in 1..4) {
                    app.repository.disconnectAccount(acct)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectivityMonitor?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FGS_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            } else {
                startForeground(FGS_NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            SanitizedLogger.error("Service", "Foreground start restricted by OS: ${t.javaClass.simpleName}")
        }
    }

    private fun ensureNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

    private fun buildPersistentNotification(connectedCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WhatsAppHubService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_FGS)
            .setSmallIcon(R.drawable.ic_notification_hub)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_body, connectedCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.btn_disconnect), stopPending)
            .build()
    }

    private fun postIncomingMessageNotification(alert: IncomingMessageAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ACCOUNT_ID, alert.accountId)
            putExtra(EXTRA_OPEN_CHAT_JID, alert.chatJid)
        }
        val reqCode = ("${alert.accountId}:${alert.chatJid}").hashCode()
        val pending = PendingIntent.getActivity(
            this,
            reqCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val colorInt = (AccountPalette.colorHexFor(alert.accountId) and 0xFFFFFFFFL).toInt()
        val notif = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_hub)
            .setColor(colorInt)
            .setSubText(alert.accountLabel)
            .setContentTitle("${alert.chatTitle} • ${alert.accountLabel}")
            .setContentText(alert.previewText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2000 + (reqCode and 0x7FFF), notif)
    }

    companion object {
        const val CHANNEL_FGS = "whatshub_fgs_status"
        const val CHANNEL_MESSAGES = "whatshub_incoming_messages"
        const val FGS_NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.rafat.whatshub.STOP_SERVICE"
        const val EXTRA_OPEN_ACCOUNT_ID = "open_account_id"
        const val EXTRA_OPEN_CHAT_JID = "open_chat_jid"

        fun start(context: Context) {
            val intent = Intent(context, WhatsAppHubService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                SanitizedLogger.warn("Service", "Unable to start foreground service from background: ${t.javaClass.simpleName}")
            }
        }
    }
}
