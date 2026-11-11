package com.rafat.whatshub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.rafat.whatshub.navigation.HubNavHost
import com.rafat.whatshub.services.WhatsAppHubService
import com.rafat.whatshub.ui.theme.WhatsAppHubTheme

class MainActivity : AppCompatActivity() {

    private var pendingDeepLink by mutableStateOf<Pair<Int, String>?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        WhatsAppHubService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as WhatsAppHubApp
        val repo = app.repository

        extractNotificationDeepLink(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WhatsAppHubService.start(this)
        }

        repo.initializeEngineAndRestoreSessions()

        setContent {
            val changeTick by repo.database.changeVersion.collectAsState()
            val themeMode = repo.getThemeMode()
            val langCode = repo.getLanguageCode()
            // Reference changeTick so theme/language changes recompose immediately
            changeTick.let { _ -> }

            WhatsAppHubTheme(
                themeMode = themeMode,
                languageCode = langCode
            ) {
                HubNavHost(
                    repository = repo,
                    deepLinkConversation = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractNotificationDeepLink(intent)
    }

    private fun extractNotificationDeepLink(intent: Intent?) {
        val acctId = intent?.getIntExtra(WhatsAppHubService.EXTRA_OPEN_ACCOUNT_ID, 0) ?: 0
        val chatJid = intent?.getStringExtra(WhatsAppHubService.EXTRA_OPEN_CHAT_JID).orEmpty()
        if (acctId in 1..4 && chatJid.isNotBlank()) {
            pendingDeepLink = acctId to chatJid
        }
    }
}
