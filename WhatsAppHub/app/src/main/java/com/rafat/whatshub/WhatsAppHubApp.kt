package com.rafat.whatshub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.rafat.whatshub.bridge.NativeEngineGateway
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
        System.loadLibrary("sqlcipher")

        keyManager = KeystoreKeyManager.createHardwareBacked(this)
        val dbPassphrase = keyManager.getOrCreateDatabasePassphrase()
        try {
            database = WhatsAppHubDatabase.openEncrypted(this, dbPassphrase)
        } finally {
            dbPassphrase.fill(0)
        }

        val sessionsDir = File(filesDir, "sessions")
        val mediaDir = File(filesDir, "media")
        val caPemPath = exportSystemCaBundleToInternalFile()

        repository = HubRepository(
            database = database,
            keyManager = keyManager,
            engine = NativeEngineGateway(),
            sessionsRootDir = sessionsDir,
            mediaRootDir = mediaDir,
            caCertPemPath = caPemPath,
            scope = appScope
        )

        appScope.launch {
            repository.initializeEngineAndRestoreSessions()
        }

        connectivityMonitor = ConnectivityMonitor(this) { isOnline ->
            repository.onNetworkChanged(isOnline)
        }.also { it.start() }

        SessionWatchdogWorker.schedulePeriodic(this)
        SanitizedLogger.info("App", "Rafat Hub v1.1.0 initialized with SQLCipher v2 & 4-account isolation")
    }

    /**
     * Memory + disk cached ImageLoader so local image/sticker bubbles render smoothly at 120Hz
     * without re-decoding files during message status updates.
     */
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

    private fun exportSystemCaBundleToInternalFile(): String {
        return runCatching {
            val outFile = File(filesDir, "cacert_system.pem")
            val systemCertsDir = File("/system/etc/security/cacerts")
            if (systemCertsDir.isDirectory) {
                val files = systemCertsDir.listFiles()?.sortedBy { it.name }.orEmpty()
                if (files.isNotEmpty()) {
                    outFile.bufferedWriter().use { writer ->
                        for (certFile in files) {
                            if (certFile.isFile) {
                                val text = certFile.readText()
                                val beginIdx = text.indexOf("-----BEGIN CERTIFICATE-----")
                                val endMarker = "-----END CERTIFICATE-----"
                                val endIdx = text.indexOf(endMarker)
                                if (beginIdx >= 0 && endIdx > beginIdx) {
                                    writer.write(text.substring(beginIdx, endIdx + endMarker.length))
                                    writer.newLine()
                                }
                            }
                        }
                    }
                    if (outFile.length() > 1024) return@runCatching outFile.absolutePath
                }
            }
            ""
        }.getOrDefault("")
    }
}
