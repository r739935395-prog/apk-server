package com.rafat.whatshub

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.rafat.whatshub.navigation.HubNavHost
import com.rafat.whatshub.services.WhatsAppHubService
import com.rafat.whatshub.ui.theme.WhatsAppHubTheme

class MainActivity : AppCompatActivity() {

    private var deepLinkConversation by mutableStateOf<Pair<Int, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WhatsAppHubApp
        val repo = app.repository

        WhatsAppHubService.start(this)
        extractNotificationDeepLink(intent)

        setContent {
            // Zero main-thread SQLite reads: observes in-memory cached StateFlow updated on Dispatchers.IO
            val prefs by repo.appPreferences.collectAsState()

            LaunchedEffect(prefs.languageCode) {
                val currentLocales = AppCompatDelegate.getApplicationLocales()
                if (currentLocales.toLanguageTags() != prefs.languageCode) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(prefs.languageCode))
                }
            }

            val direction = if (prefs.languageCode.startsWith("ar")) LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhatsAppHubTheme(
                    themeMode = prefs.themeMode,
                    fontScaleKey = prefs.fontScaleKey
                ) {
                    HubNavHost(
                        repository = repo,
                        initialConversation = deepLinkConversation,
                        onInitialConversationConsumed = { deepLinkConversation = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNotificationDeepLink(intent)
    }

    private fun extractNotificationDeepLink(intent: Intent?) {
        if (intent == null) return
        val acctId = intent.getIntExtra(EXTRA_ACCOUNT_ID, -1)
        val chatJid = intent.getStringExtra(EXTRA_CHAT_JID).orEmpty()
        if (acctId in 1..4 && chatJid.isNotBlank()) {
            deepLinkConversation = acctId to chatJid
        }
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        const val EXTRA_CHAT_JID = "extra_chat_jid"
    }
}
