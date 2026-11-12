package com.rafat.whatshub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.rafat.whatshub.bridge.JniWhatsAppEngineGateway
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.security.SanitizedLogger
import com.rafat.whatshub.services.ConnectivityMonitor
import com.rafat.whatshub.services.SessionWatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class WhatsAppHubApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var keyManager: KeystoreKeyManager
        private set

    lateinit var database: WhatsAppHubDatabase
        private set

    lateinit var repository: HubRepository
        private set

    private var connectivityMonitor: ConnectivityMonitor? = null

    override fun onCreate() {
        super.onCreate()

        keyManager = KeystoreKeyManager.fromContext(this)
        database = WhatsAppHubDatabase.createEncrypted(this, keyManager)

        val sessionsDir = File(filesDir, "sessions")
        val mediaDir = File(filesDir, "media")
        val caPemPath = KeystoreKeyManager.exportAndroidSystemCaBundle(this)

        repository = HubRepository(
            database = database,
            keyManager = keyManager,
            engine = JniWhatsAppEngineGateway(),
            sessionsRootDir = sessionsDir,
            mediaRootDir = mediaDir,
            caCertPemPath = caPemPath,
            scope = appScope
        )

        appScope.launch {
            repository.initializeEngineAndRestoreSessions()
        }

        connectivityMonitor = ConnectivityMonitor(this, repository).also { it.start() }

        SessionWatchdogWorker.schedule(this)
        SanitizedLogger.info("App", "Rafat Hub v1.1.0 initialized with SQLCipher v2 & 4-account isolation")
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_media_preview_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .build()
    }
}
