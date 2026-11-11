package com.rafat.whatshub

import android.app.Application
import com.rafat.whatshub.bridge.JniWhatsAppEngineGateway
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.services.SessionWatchdogWorker
import java.io.File

class WhatsAppHubApp : Application() {

    lateinit var keyManager: KeystoreKeyManager
        private set

    lateinit var database: WhatsAppHubDatabase
        private set

    lateinit var repository: HubRepository
        private set

    override fun onCreate() {
        super.onCreate()
        keyManager = KeystoreKeyManager.fromContext(this)
        database = WhatsAppHubDatabase.createEncrypted(this, keyManager)

        val sessionsDir = File(noBackupFilesDir, "whatshub_sessions")
        val mediaDir = File(filesDir, "media")
        val caPemPath = KeystoreKeyManager.exportAndroidSystemCaBundle(this)

        repository = HubRepository(
            database = database,
            keyManager = keyManager,
            engine = JniWhatsAppEngineGateway(),
            sessionsRootDir = sessionsDir,
            mediaRootDir = mediaDir,
            caCertPemPath = caPemPath
        )

        SessionWatchdogWorker.schedule(this)
    }
}
