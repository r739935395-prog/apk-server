package com.rafat.whatshub.repository

import com.rafat.whatshub.bridge.DownloadMediaPayload
import com.rafat.whatshub.bridge.EngineEventEnvelope
import com.rafat.whatshub.bridge.EngineResult
import com.rafat.whatshub.bridge.SendMediaPayload
import com.rafat.whatshub.bridge.SendTextPayload
import com.rafat.whatshub.bridge.WhatsAppEngineGateway
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.database.AppPreferencesState
import com.rafat.whatshub.database.ContactRecord
import com.rafat.whatshub.database.GroupMemberRecord
import com.rafat.whatshub.database.GroupRecord
import com.rafat.whatshub.database.IdentityResolver
import com.rafat.whatshub.database.MessageIdGenerator
import com.rafat.whatshub.database.MessageMediaRecord
import com.rafat.whatshub.database.MessageRecord
import com.rafat.whatshub.database.SessionMetadataRecord
import com.rafat.whatshub.database.SyncStateRecord
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.security.EncryptedBackupManager
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.security.PortableRestoreOutcome
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PairingQrState(
    val accountId: Int,
    val qrPayload: String,
    val expiresInSec: Int,
    val generatedAtMs: Long = System.currentTimeMillis()
)

data class PairingCodeState(
    val accountId: Int,
    val phone: String,
    val formattedCode: String,
    val generatedAtMs: Long = System.currentTimeMillis()
)

data class IncomingMessageAlert(
    val accountId: Int,
    val accountLabel: String,
    val chatJid: String,
    val messageId: String,
    val chatTitle: String,
    val senderName: String,
    val previewText: String
)

data class EngineHealthTelemetry(
    val engineVersion: String,
    val pollerAlive: Boolean,
    val processedEventsCount: Long,
    val isolatedEventErrors: Long,
    val lastPollTimestampMs: Long
)

/**
 * Central repository uniting SQLCipher storage, Android Keystore per-slot keys,
 * centralized @lid <-> @s.whatsapp.net identity resolution, durable Peek+Ack event ingestion,
 * and non-cancellable Pending -> Sent -> Delivered -> Read / Failed -> Retry messaging.
 */
class HubRepository(
    val database: WhatsAppHubDatabase,
    private val keyManager: KeystoreKeyManager,
    private val engine: WhatsAppEngineGateway,
    private val sessionsRootDir: File,
    private val mediaRootDir: File,
    private val caCertPemPath: String = "",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _accounts = MutableStateFlow(database.getAccounts())
    val accounts: StateFlow<List<AccountRecord>> = _accounts.asStateFlow()

    private val _appPreferences = MutableStateFlow(loadPreferencesFromDb())
    val appPreferences: StateFlow<AppPreferencesState> = _appPreferences.asStateFlow()

    private val _qrStates = MutableStateFlow<Map<Int, PairingQrState>>(emptyMap())
    val qrStates: StateFlow<Map<Int, PairingQrState>> = _qrStates.asStateFlow()

    private val _pairingCodes = MutableStateFlow<Map<Int, PairingCodeState>>(emptyMap())
    val pairingCodes: StateFlow<Map<Int, PairingCodeState>> = _pairingCodes.asStateFlow()

    private val _incomingAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 32)
    val incomingAlerts: SharedFlow<IncomingMessageAlert> = _incomingAlerts.asSharedFlow()

    private val _activeConversation = MutableStateFlow<Pair<Int, String>?>(null)

    // Tracks messages that received remote server ACK in this process so a transient local DB error
    // NEVER triggers a duplicate network transmission on Retry.
    private val remoteAckedMessageKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Bounded LRU set to suppress duplicate OS notifications for already-alerted composite message keys
    private val alertedNotificationKeys: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 512
        })
    )

    private val pollMutex = Mutex()
    private var pollerJob: Job? = null
    private val processedEventsCounter = AtomicLong(0L)
    private val isolatedErrorsCounter = AtomicLong(0L)
    @Volatile
    private var lastPollAtMs: Long = 0L

    @Volatile
    private var initialized = false

    init {
        scope.launch {
            database.accountsInvalidations.collect {
                _accounts.value = database.getAccounts()
            }
        }
        scope.launch {
            database.settingsInvalidations.collect {
                _appPreferences.value = loadPreferencesFromDb()
            }
        }
    }

    private fun loadPreferencesFromDb(): AppPreferencesState {
        val acctNotifs = (1..4).associateWith { acct ->
            database.getSetting("notifications_account_$acct", "true") == "true"
        }
        return AppPreferencesState(
            themeMode = database.getSetting(SETTING_THEME_MODE, "amoled"),
            languageCode = database.getSetting(SETTING_LANGUAGE, "en"),
            fontScaleKey = database.getSetting(SETTING_FONT_SCALE, "default"),
            notificationsEnabled = database.getSetting(SETTING_NOTIFICATIONS_ENABLED, "true") == "true",
            notificationShowPreview = database.getSetting(SETTING_NOTIFICATION_PREVIEW, "true") == "true",
            accountNotifications = acctNotifs,
            debugLogsEnabled = database.getSetting(SETTING_DEBUG_LOGS, "false") == "true"
        )
    }

    @Synchronized
    fun initializeEngineAndRestoreSessions() {
        if (initialized) return
        if (!sessionsRootDir.exists()) sessionsRootDir.mkdirs()
        if (!mediaRootDir.exists()) mediaRootDir.mkdirs()

        // Crash-recovery pass: transition any interrupted un-ACKed pending messages to failed
        runCatching {
            val recovered = database.recoverInterruptedPendingMessages()
            if (recovered > 0) {
                SanitizedLogger.warn("Repo", "Recovered $recovered interrupted pending outgoing messages after restart")
            }
            database.pruneOldJournalEntries()
        }

        val initRes = engine.initEngine(
            sessionsDir = sessionsRootDir.absolutePath,
            mediaDir = mediaRootDir.absolutePath,
            caCertPath = caCertPemPath
        )
        if (!initRes.ok) {
            SanitizedLogger.error("Repo", "Engine init failed: ${initRes.error}")
            return
        }

        for (acct in 1..4) {
            val slotKeyHex = keyManager.getOrCreateAccountSlotKeyHex(acct)
            val keyRes = engine.setSlotKey(acct, slotKeyHex)
            if (!keyRes.ok) {
                SanitizedLogger.error("Repo", "Slot key setup failed: ${keyRes.error}", acct)
            }
        }

        val snapshots = engine.getAccountsSnapshot()
        for (snap in snapshots) {
            if (snap.accountId in 1..4 && snap.hasSavedSession) {
                database.updateAccountState(
                    accountId = snap.accountId,
                    status = AccountStatus.fromWire(snap.state),
                    jid = snap.jid,
                    phone = snap.phone,
                    pushName = snap.pushName,
                    hasSavedSession = true
                )
            }
        }

        val debugSetting = _appPreferences.value.debugLogsEnabled
        SanitizedLogger.debugEnabled = debugSetting
        engine.setDebugLogging(debugSetting)

        startEventPoller()
        initialized = true

        scope.launch {
            for (acct in database.getAccounts()) {
                if (acct.hasSavedSession) {
                    engine.connectAccount(acct.accountId)
                }
            }
        }
    }

    /**
     * Fault-isolated Peek + Persist + Ack event loop.
     * Never dies on exceptions and never acknowledges Go events until SQLite persistence completes.
     */
    private fun startEventPoller() {
        pollerJob?.cancel()
        pollerJob = scope.launch {
            while (isActive) {
                var drainedCount = 0
                var hadTransientStorageError = false
                try {
                    pollMutex.withLock {
                        lastPollAtMs = System.currentTimeMillis()
                        val peeked = engine.peekEvents(128)
                        if (peeked.malformedCount > 0) {
                            isolatedErrorsCounter.addAndGet(peeked.malformedCount.toLong())
                        }
                        if (peeked.events.isNotEmpty() || peeked.highestRawSeq > 0L) {
                            hadTransientStorageError = !processEngineEventsBatchSafely(peeked.events)
                            if (!hadTransientStorageError && peeked.highestRawSeq > 0L) {
                                engine.ackEvents(peeked.highestRawSeq)
                            }
                            drainedCount = peeked.events.size
                        }
                    }
                } catch (t: Throwable) {
                    isolatedErrorsCounter.incrementAndGet()
                    SanitizedLogger.error("Poller", "Recovered poller cycle exception: ${t.javaClass.simpleName}")
                    hadTransientStorageError = true
                }

                when {
                    hadTransientStorageError -> delay(350L)
                    drainedCount >= 64 -> delay(35L)
                    drainedCount > 0 -> delay(80L)
                    else -> delay(260L)
                }
            }
        }
    }

    /**
     * Deterministic drain entrypoint for integration & messaging tests.
     */
    fun drainAndProcessEventsNow(): Int {
        val peeked = engine.peekEvents(512)
        if (peeked.events.isNotEmpty() || peeked.highestRawSeq > 0L) {
            val clean = processEngineEventsBatchSafely(peeked.events)
            if (clean && peeked.highestRawSeq > 0L) {
                engine.ackEvents(peeked.highestRawSeq)
            }
        }
        return peeked.events.size
    }

    /**
     * Processes each envelope inside an isolated boundary.
     * Returns true when the batch is safe to ACK; returns false ONLY if SQLite itself is locked/unavailable
     * so the unacknowledged batch stays in the Go router for immediate retry.
     */
    private fun processEngineEventsBatchSafely(batch: List<EngineEventEnvelope>): Boolean {
        for (ev in batch) {
            try {
                processSingleEngineEvent(ev)
                processedEventsCounter.incrementAndGet()
            } catch (sqlLocked: android.database.sqlite.SQLiteDatabaseLockedException) {
                SanitizedLogger.warn("Poller", "SQLite busy during event seq=${ev.seq}; deferring ACK for retry", ev.accountId)
                return false
            } catch (t: Throwable) {
                // Isolate data/domain error on this single envelope so it cannot poison other events!
                isolatedErrorsCounter.incrementAndGet()
                SanitizedLogger.error(
                    "Poller",
                    "Isolated error on event type=${ev.type} seq=${ev.seq}: ${t.javaClass.simpleName}",
                    ev.accountId
                )
            }
        }
        return true
    }

    private fun processSingleEngineEvent(ev: EngineEventEnvelope) {
        // Persist verified LID <-> PN mappings FIRST before handling any chat/message/receipt!
        if (ev.accountId in 1..4) {
            if (ev.lidJid.isNotBlank() && ev.pnJid.isNotBlank()) {
                database.recordIdentityMappingAndMerge(
                    accountId = ev.accountId,
                    lidJidRaw = ev.lidJid,
                    pnJidRaw = ev.pnJid,
                    source = ev.mappingSource.ifBlank { ev.type }
                )
            }
            if (ev.senderLidJid.isNotBlank() && ev.senderPnJid.isNotBlank()) {
                database.recordIdentityMappingAndMerge(
                    accountId = ev.accountId,
                    lidJidRaw = ev.senderLidJid,
                    pnJidRaw = ev.senderPnJid,
                    source = "sender_${ev.type}"
                )
            }
        }

        when (ev.type) {
            "identity_mapping" -> {
                // Already handled above via recordIdentityMappingAndMerge
            }

            "diagnostic_log" -> {
                SanitizedLogger.record(
                    level = ev.logLevel.ifBlank { "INFO" },
                    module = ev.logModule.ifBlank { "Engine" },
                    message = ev.logMessage,
                    accountId = ev.accountId,
                    timestampMs = ev.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
            }

            "account_state" -> {
                if (ev.accountId in 1..4) {
                    val status = AccountStatus.fromWire(ev.state)
                    database.updateAccountState(
                        accountId = ev.accountId,
                        status = status,
                        jid = ev.jid,
                        phone = ev.phone,
                        pushName = ev.pushName,
                        errorMessage = ev.errorMessage,
                        reconnectAttempt = ev.reconnectAttempt,
                        hasSavedSession = if (status == AccountStatus.NOT_CONNECTED) false else null
                    )
                    if (status == AccountStatus.CONNECTED) {
                        clearPairingArtifacts(ev.accountId)
                        database.upsertSessionMetadata(
                            SessionMetadataRecord(
                                accountId = ev.accountId,
                                deviceJid = ev.jid,
                                keyAlias = "whatshub_account_slot_${ev.accountId}_v1",
                                pairedAtMs = System.currentTimeMillis(),
                                lastConnectedAtMs = System.currentTimeMillis(),
                                sessionDir = File(sessionsRootDir, "account_${ev.accountId}").absolutePath
                            )
                        )
                    } else if (status == AccountStatus.NOT_CONNECTED || status == AccountStatus.DISCONNECTED) {
                        clearPairingArtifacts(ev.accountId)
                    }
                }
            }

            "qr_code" -> {
                if (ev.accountId in 1..4 && ev.qrCode.isNotBlank()) {
                    _qrStates.value = _qrStates.value + (
                        ev.accountId to PairingQrState(
                            accountId = ev.accountId,
                            qrPayload = ev.qrCode,
                            expiresInSec = ev.expiresInSec.coerceAtLeast(15)
                        )
                    )
                }
            }

            "pairing_code" -> {
                if (ev.accountId in 1..4 && ev.pairingCode.isNotBlank()) {
                    _pairingCodes.value = _pairingCodes.value + (
                        ev.accountId to PairingCodeState(
                            accountId = ev.accountId,
                            phone = ev.phone,
                            formattedCode = ev.pairingCode
                        )
                    )
                }
            }

            "message" -> {
                if (ev.accountId in 1..4 && ev.chatJid.isNotBlank() && ev.messageId.isNotBlank()) {
                    val canonicalChatJid = database.resolveCanonicalChatJid(ev.accountId, ev.chatJid)
                    if (ev.editTargetId.isNotBlank()) {
                        database.applyMessageEdit(ev.accountId, canonicalChatJid, ev.editTargetId, ev.text)
                    } else {
                        val dedupKey = "msg:${ev.accountId}:$canonicalChatJid:${ev.messageId}"
                        val active = _activeConversation.value
                        val isCurrentlyViewing = active?.first == ev.accountId &&
                            (active.second == canonicalChatJid || active.second == ev.rawChatJid)

                        val mediaRec = if (ev.mediaType.isNotBlank()) {
                            MessageMediaRecord(
                                accountId = ev.accountId,
                                chatJid = canonicalChatJid,
                                messageId = ev.messageId,
                                mediaType = ev.mediaType,
                                localPath = ev.mediaPath,
                                mimeType = ev.mimeType,
                                fileName = ev.fileName,
                                fileSize = ev.fileSize,
                                durationSec = ev.durationSec,
                                isVoiceNote = ev.isVoiceNote,
                                directPath = ev.directPath,
                                mediaKeyB64 = ev.mediaKeyB64,
                                fileSha256B64 = ev.fileSha256B64,
                                fileEncSha256B64 = ev.fileEncSha256B64,
                                downloadState = if (ev.mediaPath.isNotBlank()) "ready" else "none",
                                errorMessage = ""
                            )
                        } else null

                        val msgRec = MessageRecord(
                            accountId = ev.accountId,
                            chatJid = canonicalChatJid,
                            messageId = ev.messageId,
                            compositeKey = "${ev.accountId}:$canonicalChatJid:${ev.messageId}",
                            senderJid = ev.senderJid,
                            senderName = ev.senderName,
                            isFromMe = ev.isFromMe,
                            isGroup = ev.isGroup,
                            textBody = ev.text,
                            mediaType = ev.mediaType,
                            quotedMessageId = ev.quotedMessageId,
                            quotedSenderJid = ev.quotedSenderJid,
                            quotedText = ev.quotedText,
                            reactionsSummary = "",
                            isDeleted = false,
                            isEdited = false,
                            status = ev.status.ifBlank { if (ev.isFromMe) "sent" else "delivered" },
                            timestampMs = ev.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            media = mediaRec,
                            rawChatJid = ev.rawChatJid.ifBlank { ev.chatJid },
                            clientMessageId = ev.clientMessageId.ifBlank { ev.messageId },
                            transmissionAck = true
                        )

                        val insertedNew = database.upsertMessageAndUpdateChat(
                            record = msgRec,
                            chatTitleHint = ev.chatName,
                            incrementUnread = !ev.isHistorySync && !ev.isFromMe && !isCurrentlyViewing,
                            explicitUnreadCount = if (ev.isHistorySync && ev.unreadCount > 0) ev.unreadCount else null
                        )
                        database.recordProcessedJournalKey(dedupKey, ev.accountId, "message")

                        val prefs = _appPreferences.value
                        val acctNotifEnabled = prefs.accountNotifications[ev.accountId] != false
                        if (insertedNew && !ev.isHistorySync && !ev.isFromMe && !isCurrentlyViewing &&
                            prefs.notificationsEnabled && acctNotifEnabled &&
                            alertedNotificationKeys.add(dedupKey)
                        ) {
                            val chatSummary = database.getChatSummary(ev.accountId, canonicalChatJid)
                            if (chatSummary?.isMuted != true) {
                                val acctLabel = database.getAccount(ev.accountId)?.label ?: "Account ${ev.accountId}"
                                val title = chatSummary?.cleanTitle ?: ev.chatName.ifBlank { ev.senderName }
                                val preview = if (prefs.notificationShowPreview) {
                                    ev.text.ifBlank { "[${ev.mediaType.ifBlank { "Message" }}]" }
                                } else {
                                    "New message"
                                }
                                _incomingAlerts.tryEmit(
                                    IncomingMessageAlert(
                                        accountId = ev.accountId,
                                        accountLabel = acctLabel,
                                        chatJid = canonicalChatJid,
                                        messageId = ev.messageId,
                                        chatTitle = title,
                                        senderName = ev.senderName,
                                        previewText = preview
                                    )
                                )
                            }
                        }
                    }
                }
            }

            "reaction" -> {
                if (ev.accountId in 1..4 && ev.targetMessageId.isNotBlank()) {
                    database.applyMessageReaction(ev.accountId, ev.chatJid, ev.targetMessageId, ev.emoji)
                }
            }

            "message_revoked" -> {
                if (ev.accountId in 1..4 && ev.targetMessageId.isNotBlank()) {
                    database.markMessageRevoked(ev.accountId, ev.chatJid, ev.targetMessageId)
                }
            }

            "receipt" -> {
                if (ev.accountId in 1..4 && ev.messageIds.isNotEmpty()) {
                    database.updateMessageReceiptStatus(ev.accountId, ev.chatJid, ev.messageIds, ev.receiptStatus)
                }
            }

            "typing" -> {
                if (ev.accountId in 1..4 && ev.chatJid.isNotBlank()) {
                    database.updateChatTyping(ev.accountId, ev.chatJid, ev.isTyping, ev.senderJid)
                }
            }

            "presence" -> {
                if (ev.accountId in 1..4 && ev.jid.isNotBlank()) {
                    database.updatePresence(ev.accountId, ev.jid, ev.isOnline, ev.lastSeenMs)
                }
            }

            "media_downloaded" -> {
                if (ev.accountId in 1..4 && ev.messageId.isNotBlank()) {
                    val dlState = if (ev.mediaPath.isNotBlank() && ev.errorMessage.isBlank()) "ready" else "failed"
                    database.updateMediaDownloadState(
                        accountId = ev.accountId,
                        chatJidRaw = ev.chatJid,
                        messageId = ev.messageId,
                        localPath = ev.mediaPath,
                        downloadState = dlState,
                        errorMessage = ev.errorMessage
                    )
                }
            }

            "contacts_batch" -> {
                if (ev.accountId in 1..4 && ev.contacts.isNotEmpty()) {
                    database.upsertContacts(
                        ev.accountId,
                        ev.contacts.map { c ->
                            ContactRecord(
                                accountId = ev.accountId,
                                contactJid = c.jid,
                                fullName = c.fullName,
                                firstName = c.firstName,
                                pushName = c.pushName,
                                businessName = c.businessName,
                                isOnline = false,
                                lastSeenMs = 0L,
                                lidJid = c.lidJid,
                                pnJid = c.pnJid
                            )
                        }
                    )
                }
            }

            "groups_batch" -> {
                if (ev.accountId in 1..4 && ev.groups.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    database.upsertGroups(
                        ev.accountId,
                        ev.groups.map { g ->
                            GroupRecord(
                                accountId = ev.accountId,
                                groupJid = g.jid,
                                name = g.name,
                                topic = g.topic,
                                ownerJid = g.ownerJid,
                                participantCount = g.participantCount,
                                isAnnounce = g.isAnnounce,
                                isLocked = g.isLocked,
                                createdAtMs = g.createdAtMs,
                                updatedAtMs = now,
                                members = g.participants.map { p ->
                                    GroupMemberRecord(
                                        accountId = ev.accountId,
                                        groupJid = g.jid,
                                        memberJid = p.jid,
                                        phoneJid = p.phoneJid,
                                        isAdmin = p.isAdmin,
                                        isSuperAdmin = p.isSuperAdmin
                                    )
                                }
                            )
                        }
                    )
                }
            }

            "history_sync_progress" -> {
                if (ev.accountId in 1..4 && ev.syncType.isNotBlank()) {
                    database.upsertSyncState(
                        SyncStateRecord(
                            accountId = ev.accountId,
                            syncType = ev.syncType,
                            lastChunkIndex = ev.chunkIndex,
                            progressPct = ev.progressPct,
                            importedChats = ev.importedChats,
                            importedMessages = ev.importedMessages,
                            status = ev.status,
                            updatedAtMs = ev.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    private fun clearPairingArtifacts(accountId: Int) {
        _qrStates.value = _qrStates.value - accountId
        _pairingCodes.value = _pairingCodes.value - accountId
    }

    // =========================================================================
    // ACCOUNT LIFECYCLE ACTIONS
    // =========================================================================

    suspend fun connectAccount(accountId: Int): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        clearPairingArtifacts(accountId)
        val slotKey = keyManager.getOrCreateAccountSlotKeyHex(accountId)
        engine.setSlotKey(accountId, slotKey)
        val res = engine.connectAccount(accountId)
        if (!res.ok) {
            database.updateAccountState(accountId, AccountStatus.CONNECTION_ERROR, errorMessage = res.error)
        }
        res
    }

    suspend fun requestPairingCode(accountId: Int, phone: String): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val slotKey = keyManager.getOrCreateAccountSlotKeyHex(accountId)
        engine.setSlotKey(accountId, slotKey)
        val res = engine.requestPairingCode(accountId, phone)
        if (res.ok && res.pairingCode.isNotBlank()) {
            _qrStates.value = _qrStates.value - accountId
            _pairingCodes.value = _pairingCodes.value + (
                accountId to PairingCodeState(accountId = accountId, phone = phone, formattedCode = res.pairingCode)
            )
            database.updateAccountState(accountId, AccountStatus.PAIRING, phone = phone, hasSavedSession = false)
        } else if (!res.ok) {
            database.updateAccountState(accountId, AccountStatus.CONNECTION_ERROR, errorMessage = res.error)
        }
        res
    }

    suspend fun disconnectAccount(accountId: Int): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        clearPairingArtifacts(accountId)
        val res = engine.disconnectAccount(accountId)
        database.updateAccountState(accountId, AccountStatus.DISCONNECTED)
        res
    }

    suspend fun removeAccount(accountId: Int): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        clearPairingArtifacts(accountId)
        val res = engine.removeAccount(accountId)
        val freshKey = keyManager.rotateAccountSlotKey(accountId)
        engine.setSlotKey(accountId, freshKey)
        database.wipeSingleAccountData(accountId)
        res
    }

    suspend fun replaceAccount(accountId: Int): EngineResult = withContext(Dispatchers.IO) {
        removeAccount(accountId)
        connectAccount(accountId)
    }

    fun renameAccount(accountId: Int, newLabel: String) {
        database.updateAccountLabel(accountId, newLabel)
    }

    // =========================================================================
    // RELIABLE OUTGOING MESSAGING PIPELINE (PENDING -> SENT -> DELIVERED -> READ / FAILED -> RETRY)
    // =========================================================================

    fun setActiveConversation(accountId: Int?, chatJid: String?) {
        _activeConversation.value = if (accountId != null && !chatJid.isNullOrBlank()) {
            accountId to database.resolveCanonicalChatJid(accountId, chatJid)
        } else {
            null
        }
    }

    suspend fun openAndMarkConversationRead(accountId: Int, chatJid: String) = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val canonical = database.resolveCanonicalChatJid(accountId, chatJid)
        val unreadItems = database.clearChatUnread(accountId, canonical)
        if (unreadItems.isNotEmpty()) {
            val groupedBySender = unreadItems.groupBy({ it.second }, { it.first })
            for ((senderJid, msgIds) in groupedBySender) {
                engine.markRead(accountId, canonical, senderJid, msgIds)
            }
        }
    }

    /**
     * Durable outgoing text pipeline:
     *  1. Persists a local 'pending' record to SQLite FIRST (invoking [onPendingPersisted] only after DB commit).
     *  2. Transmits on NonCancellable IO context so navigating Back NEVER drops post-send reconciliation.
     *  3. Reconciles server ACK idempotently (never re-transmitting if only local DB lagged).
     *  4. Transitions to 'failed' with clear reason if transmission failed, enabling safe manual Retry.
     */
    suspend fun sendTextMessage(
        accountId: Int,
        chatJid: String,
        text: String,
        quotedMessageId: String = "",
        quotedSenderJid: String = "",
        quotedText: String = "",
        onPendingPersisted: ((String) -> Unit)? = null
    ): EngineResult = withContext(scope.coroutineContext + NonCancellable) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext EngineResult(ok = false, error = "Message cannot be empty")

        val canonicalChatJid = database.resolveCanonicalChatJid(accountId, IdentityResolver.normalizeJid(chatJid))
        if (canonicalChatJid.isBlank()) {
            return@withContext EngineResult(ok = false, error = "Invalid recipient JID")
        }

        val acct = database.getAccount(accountId)
        val ownJid = acct?.jid.orEmpty()
        val clientMsgId = MessageIdGenerator.nextWhatsAppMessageId(acct?.phone.orEmpty())
        val nowMs = System.currentTimeMillis()

        // Step 1: Persist Pending record BEFORE clearing composer or calling JNI
        database.upsertMessageAndUpdateChat(
            record = MessageRecord(
                accountId = accountId,
                chatJid = canonicalChatJid,
                messageId = clientMsgId,
                compositeKey = "$accountId:$canonicalChatJid:$clientMsgId",
                senderJid = ownJid,
                senderName = "Me",
                isFromMe = true,
                isGroup = IdentityResolver.isGroupJid(canonicalChatJid),
                textBody = cleanText,
                mediaType = "",
                quotedMessageId = quotedMessageId,
                quotedSenderJid = quotedSenderJid,
                quotedText = quotedText,
                reactionsSummary = "",
                isDeleted = false,
                isEdited = false,
                status = "pending",
                timestampMs = nowMs,
                rawChatJid = chatJid,
                clientMessageId = clientMsgId,
                transmissionAck = false,
                errorReason = ""
            ),
            incrementUnread = false
        )
        onPendingPersisted?.invoke(clientMsgId)

        // Step 2: Transmit via Go whatsmeow engine with deterministic clientMsgId
        val res = runCatching {
            engine.sendText(
                accountId = accountId,
                payload = SendTextPayload(
                    chatJid = canonicalChatJid,
                    clientMessageId = clientMsgId,
                    text = cleanText,
                    quotedMessageId = quotedMessageId,
                    quotedSenderJid = quotedSenderJid,
                    quotedText = quotedText
                )
            )
        }.getOrElse { err ->
            EngineResult(ok = false, error = err.message ?: "Engine transmission exception")
        }

        // Step 3: Reconcile server ACK vs transmission failure
        if (res.ok) {
            val ackKey = "$accountId:$clientMsgId"
            remoteAckedMessageKeys.add(ackKey)
            reconcileSentWithLocalRetry(
                accountId = accountId,
                chatJid = canonicalChatJid,
                clientMsgId = clientMsgId,
                serverMsgId = res.messageId.ifBlank { clientMsgId },
                serverTsMs = res.timestampMs.takeIf { it > 0 } ?: nowMs
            )
            res.copy(messageId = res.messageId.ifBlank { clientMsgId })
        } else {
            val errReason = res.error.ifBlank { "Delivery failed on Account $accountId" }
            database.markOutgoingMessageFailed(accountId, canonicalChatJid, clientMsgId, errReason)
            res
        }
    }

    suspend fun sendMediaMessage(
        accountId: Int,
        chatJid: String,
        mediaType: String,
        localFile: File,
        mimeType: String,
        caption: String = "",
        durationSec: Int = 0,
        quotedMessageId: String = "",
        quotedSenderJid: String = "",
        quotedText: String = "",
        onPendingPersisted: ((String) -> Unit)? = null
    ): EngineResult = withContext(scope.coroutineContext + NonCancellable) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        if (!localFile.exists() || localFile.length() == 0L) {
            return@withContext EngineResult(ok = false, error = "Selected media file is empty or inaccessible")
        }

        val canonicalChatJid = database.resolveCanonicalChatJid(accountId, IdentityResolver.normalizeJid(chatJid))
        val acct = database.getAccount(accountId)
        val ownJid = acct?.jid.orEmpty()
        val clientMsgId = MessageIdGenerator.nextWhatsAppMessageId(acct?.phone.orEmpty())
        val nowMs = System.currentTimeMillis()
        val isVoice = mediaType == "voice"

        val mediaRec = MessageMediaRecord(
            accountId = accountId,
            chatJid = canonicalChatJid,
            messageId = clientMsgId,
            mediaType = mediaType,
            localPath = localFile.absolutePath,
            mimeType = mimeType,
            fileName = localFile.name,
            fileSize = localFile.length(),
            durationSec = durationSec,
            isVoiceNote = isVoice,
            directPath = "",
            mediaKeyB64 = "",
            fileSha256B64 = "",
            fileEncSha256B64 = "",
            downloadState = "ready",
            errorMessage = ""
        )

        // Step 1: Persist Pending media message BEFORE network upload
        database.upsertMessageAndUpdateChat(
            record = MessageRecord(
                accountId = accountId,
                chatJid = canonicalChatJid,
                messageId = clientMsgId,
                compositeKey = "$accountId:$canonicalChatJid:$clientMsgId",
                senderJid = ownJid,
                senderName = "Me",
                isFromMe = true,
                isGroup = IdentityResolver.isGroupJid(canonicalChatJid),
                textBody = caption,
                mediaType = mediaType,
                quotedMessageId = quotedMessageId,
                quotedSenderJid = quotedSenderJid,
                quotedText = quotedText,
                reactionsSummary = "",
                isDeleted = false,
                isEdited = false,
                status = "pending",
                timestampMs = nowMs,
                media = mediaRec,
                rawChatJid = chatJid,
                clientMessageId = clientMsgId,
                transmissionAck = false
            ),
            incrementUnread = false
        )
        onPendingPersisted?.invoke(clientMsgId)

        // Step 2: Upload & transmit on NonCancellable IO context
        val res = runCatching {
            engine.sendMedia(
                accountId = accountId,
                payload = SendMediaPayload(
                    chatJid = canonicalChatJid,
                    clientMessageId = clientMsgId,
                    mediaType = mediaType,
                    filePath = localFile.absolutePath,
                    mimeType = mimeType,
                    caption = caption,
                    fileName = localFile.name,
                    durationSec = durationSec,
                    quotedMessageId = quotedMessageId,
                    quotedSenderJid = quotedSenderJid,
                    quotedText = quotedText
                )
            )
        }.getOrElse { err ->
            EngineResult(ok = false, error = err.message ?: "Media upload failed")
        }

        if (res.ok) {
            remoteAckedMessageKeys.add("$accountId:$clientMsgId")
            reconcileSentWithLocalRetry(
                accountId = accountId,
                chatJid = canonicalChatJid,
                clientMsgId = clientMsgId,
                serverMsgId = res.messageId.ifBlank { clientMsgId },
                serverTsMs = res.timestampMs.takeIf { it > 0 } ?: nowMs
            )
            res.copy(messageId = res.messageId.ifBlank { clientMsgId })
        } else {
            val errReason = res.error.ifBlank { "Media upload failed on Account $accountId" }
            database.markOutgoingMessageFailed(accountId, canonicalChatJid, clientMsgId, errReason)
            res
        }
    }

    /**
     * Retries a failed outgoing message WITHOUT creating a duplicate local row and WITHOUT
     * re-transmitting if remote server ACK was already received in memory or DB.
     */
    suspend fun retryFailedMessage(accountId: Int, chatJid: String, messageId: String): EngineResult =
        withContext(scope.coroutineContext + NonCancellable) {
            require(accountId in 1..4) { "Invalid accountId $accountId" }
            val canonicalChatJid = database.resolveCanonicalChatJid(accountId, chatJid)
            val existing = database.getMessageById(accountId, canonicalChatJid, messageId)
                ?: return@withContext EngineResult(ok = false, error = "Message no longer exists")

            if (existing.transmissionAck || remoteAckedMessageKeys.contains("$accountId:$messageId")) {
                // Remote transmission already succeeded earlier; reconcile local status ONLY!
                database.markOutgoingMessageSent(accountId, canonicalChatJid, messageId, messageId, existing.timestampMs)
                return@withContext EngineResult(ok = true, messageId = messageId, timestampMs = existing.timestampMs)
            }

            val pendingMsg = database.resetFailedMessageToPending(accountId, canonicalChatJid, messageId)
                ?: existing

            val res = if (pendingMsg.mediaType.isNotBlank() && pendingMsg.media != null) {
                val localFile = File(pendingMsg.media.localPath)
                if (!localFile.exists()) {
                    database.markOutgoingMessageFailed(accountId, canonicalChatJid, messageId, "Local attachment file missing")
                    return@withContext EngineResult(ok = false, error = "Local attachment file missing")
                }
                engine.sendMedia(
                    accountId = accountId,
                    payload = SendMediaPayload(
                        chatJid = canonicalChatJid,
                        clientMessageId = messageId,
                        mediaType = pendingMsg.mediaType,
                        filePath = localFile.absolutePath,
                        mimeType = pendingMsg.media.mimeType,
                        caption = pendingMsg.textBody,
                        fileName = pendingMsg.media.fileName,
                        durationSec = pendingMsg.media.durationSec,
                        quotedMessageId = pendingMsg.quotedMessageId,
                        quotedSenderJid = pendingMsg.quotedSenderJid,
                        quotedText = pendingMsg.quotedText
                    )
                )
            } else {
                engine.sendText(
                    accountId = accountId,
                    payload = SendTextPayload(
                        chatJid = canonicalChatJid,
                        clientMessageId = messageId,
                        text = pendingMsg.textBody,
                        quotedMessageId = pendingMsg.quotedMessageId,
                        quotedSenderJid = pendingMsg.quotedSenderJid,
                        quotedText = pendingMsg.quotedText
                    )
                )
            }

            if (res.ok) {
                remoteAckedMessageKeys.add("$accountId:$messageId")
                reconcileSentWithLocalRetry(
                    accountId = accountId,
                    chatJid = canonicalChatJid,
                    clientMsgId = messageId,
                    serverMsgId = res.messageId.ifBlank { messageId },
                    serverTsMs = res.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
            } else {
                database.markOutgoingMessageFailed(
                    accountId = accountId,
                    chatJidRaw = canonicalChatJid,
                    messageId = messageId,
                    reason = res.error.ifBlank { "Retry failed — verify Account $accountId is connected" }
                )
            }
            res
        }

    private suspend fun reconcileSentWithLocalRetry(
        accountId: Int,
        chatJid: String,
        clientMsgId: String,
        serverMsgId: String,
        serverTsMs: Long
    ) {
        var attempt = 0
        while (attempt < 3) {
            val ok = runCatching {
                database.markOutgoingMessageSent(accountId, chatJid, clientMsgId, serverMsgId, serverTsMs)
            }.isSuccess
            if (ok) return
            attempt++
            delay(120L * attempt)
        }
        SanitizedLogger.error("Repo", "Local DB reconciliation deferred after remote ACK for msg=$clientMsgId", accountId)
    }

    suspend fun forwardMessage(
        sourceMsg: MessageRecord,
        targetAccountId: Int,
        targetChatJid: String
    ): EngineResult {
        require(targetAccountId in 1..4) { "Invalid targetAccountId" }
        val media = sourceMsg.media
        if (sourceMsg.mediaType.isNotBlank() && media != null && media.localPath.isNotBlank()) {
            val file = File(media.localPath)
            if (file.exists() && file.length() > 0L) {
                return sendMediaMessage(
                    accountId = targetAccountId,
                    chatJid = targetChatJid,
                    mediaType = sourceMsg.mediaType,
                    localFile = file,
                    mimeType = media.mimeType,
                    caption = sourceMsg.textBody,
                    durationSec = media.durationSec
                )
            }
        }
        val body = sourceMsg.textBody.ifBlank { "[Forwarded ${sourceMsg.mediaType.ifBlank { "message" }}]" }
        return sendTextMessage(
            accountId = targetAccountId,
            chatJid = targetChatJid,
            text = body
        )
    }

    suspend fun sendReaction(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        emoji: String
    ): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val canonical = database.resolveCanonicalChatJid(accountId, chatJid)
        val res = engine.sendReaction(accountId, canonical, senderJid, targetMessageId, emoji)
        if (res.ok) {
            database.applyMessageReaction(accountId, canonical, targetMessageId, emoji)
        }
        res
    }

    suspend fun deleteMessageForEveryone(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        targetMessageId: String
    ): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val canonical = database.resolveCanonicalChatJid(accountId, chatJid)
        val res = engine.revokeMessage(accountId, canonical, senderJid, targetMessageId)
        if (res.ok) {
            database.markMessageRevoked(accountId, canonical, targetMessageId)
        }
        res
    }

    fun deleteMessageForMe(accountId: Int, chatJid: String, messageId: String) {
        database.deleteMessageLocally(accountId, chatJid, messageId)
    }

    suspend fun downloadMessageMedia(accountId: Int, msg: MessageRecord): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4 && msg.accountId == accountId) { "Account mismatch on media download" }
        val media = msg.media ?: return@withContext EngineResult(ok = false, error = "No media metadata on message")
        database.updateMediaDownloadState(accountId, msg.chatJid, msg.messageId, "", "downloading")

        val res = engine.downloadMedia(
            accountId = accountId,
            payload = DownloadMediaPayload(
                messageId = msg.messageId,
                mediaType = media.mediaType,
                mimeType = media.mimeType,
                fileName = media.fileName,
                directPath = media.directPath,
                mediaKeyB64 = media.mediaKeyB64,
                fileSha256B64 = media.fileSha256B64,
                fileEncSha256B64 = media.fileEncSha256B64
            )
        )
        if (res.ok && res.mediaPath.isNotBlank()) {
            database.updateMediaDownloadState(accountId, msg.chatJid, msg.messageId, res.mediaPath, "ready")
        } else {
            database.updateMediaDownloadState(accountId, msg.chatJid, msg.messageId, "", "failed", res.error)
        }
        res
    }

    suspend fun sendTypingState(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (accountId in 1..4) {
                val canonical = database.resolveCanonicalChatJid(accountId, chatJid)
                engine.sendTyping(accountId, canonical, isTyping, isAudio)
            }
        }

    suspend fun refreshContactsAndGroups(accountId: Int) = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val contacts = engine.getContacts(accountId)
        if (contacts.isNotEmpty()) {
            database.upsertContacts(
                accountId,
                contacts.map {
                    ContactRecord(
                        accountId = accountId,
                        contactJid = it.jid,
                        fullName = it.fullName,
                        firstName = it.firstName,
                        pushName = it.pushName,
                        businessName = it.businessName,
                        isOnline = false,
                        lastSeenMs = 0L,
                        lidJid = it.lidJid,
                        pnJid = it.pnJid
                    )
                }
            )
        }
        val groups = engine.getGroups(accountId)
        if (groups.isNotEmpty()) {
            val now = System.currentTimeMillis()
            database.upsertGroups(
                accountId,
                groups.map { g ->
                    GroupRecord(
                        accountId = accountId,
                        groupJid = g.jid,
                        name = g.name,
                        topic = g.topic,
                        ownerJid = g.ownerJid,
                        participantCount = g.participantCount,
                        isAnnounce = g.isAnnounce,
                        isLocked = g.isLocked,
                        createdAtMs = g.createdAtMs,
                        updatedAtMs = now,
                        members = g.participants.map { p ->
                            GroupMemberRecord(
                                accountId = accountId,
                                groupJid = g.jid,
                                memberJid = p.jid,
                                phoneJid = p.phoneJid,
                                isAdmin = p.isAdmin,
                                isSuperAdmin = p.isSuperAdmin
                            )
                        }
                    )
                }
            )
        }
    }

    suspend fun refreshSingleGroupDetails(accountId: Int, groupJid: String): GroupRecord? = withContext(Dispatchers.IO) {
        val g = engine.getGroupInfo(accountId, groupJid) ?: return@withContext null
        val now = System.currentTimeMillis()
        val rec = GroupRecord(
            accountId = accountId,
            groupJid = g.jid,
            name = g.name,
            topic = g.topic,
            ownerJid = g.ownerJid,
            participantCount = g.participantCount,
            isAnnounce = g.isAnnounce,
            isLocked = g.isLocked,
            createdAtMs = g.createdAtMs,
            updatedAtMs = now,
            members = g.participants.map { p ->
                GroupMemberRecord(
                    accountId = accountId,
                    groupJid = g.jid,
                    memberJid = p.jid,
                    phoneJid = p.phoneJid,
                    isAdmin = p.isAdmin,
                    isSuperAdmin = p.isSuperAdmin
                )
            }
        )
        database.upsertGroups(accountId, listOf(rec))
        rec
    }

    fun startNewConversation(accountId: Int, rawPhoneOrJid: String, displayName: String = ""): String {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val normalized = IdentityResolver.normalizeJid(rawPhoneOrJid)
        require(normalized.isNotBlank()) { "Enter a valid phone number with country code" }
        return database.ensureChatExists(accountId, normalized, displayName)
    }

    fun onNetworkChanged(available: Boolean) {
        engine.onNetworkChanged(available)
    }

    // =========================================================================
    // SETTINGS, TELEMETRY, STORAGE & PORTABLE ENCRYPTED BACKUP/MIGRATION
    // =========================================================================

    fun notificationsEnabled(): Boolean = _appPreferences.value.notificationsEnabled

    fun setNotificationsEnabled(enabled: Boolean) {
        database.setSetting(SETTING_NOTIFICATIONS_ENABLED, enabled.toString())
    }

    fun setNotificationPreviewEnabled(enabled: Boolean) {
        database.setSetting(SETTING_NOTIFICATION_PREVIEW, enabled.toString())
    }

    fun setAccountNotificationEnabled(accountId: Int, enabled: Boolean) {
        require(accountId in 1..4)
        database.setSetting("notifications_account_$accountId", enabled.toString())
    }

    fun getThemeMode(): String = _appPreferences.value.themeMode

    fun setThemeMode(mode: String) {
        database.setSetting(SETTING_THEME_MODE, mode)
    }

    fun getLanguageCode(): String = _appPreferences.value.languageCode

    fun setLanguageCode(lang: String) {
        database.setSetting(SETTING_LANGUAGE, lang)
    }

    fun getFontScaleKey(): String = _appPreferences.value.fontScaleKey

    fun setFontScaleKey(scaleKey: String) {
        database.setSetting(SETTING_FONT_SCALE, scaleKey)
    }

    fun isDebugLoggingEnabled(): Boolean = _appPreferences.value.debugLogsEnabled

    fun setDebugLoggingEnabled(enabled: Boolean) {
        database.setSetting(SETTING_DEBUG_LOGS, enabled.toString())
        SanitizedLogger.debugEnabled = enabled
        engine.setDebugLogging(enabled)
    }

    fun getEngineHealthTelemetry(): EngineHealthTelemetry = EngineHealthTelemetry(
        engineVersion = engine.engineVersion(),
        pollerAlive = pollerJob?.isActive == true,
        processedEventsCount = processedEventsCounter.get(),
        isolatedEventErrors = isolatedErrorsCounter.get(),
        lastPollTimestampMs = lastPollAtMs
    )

    suspend fun calculateMediaStorageBytes(): Long = withContext(Dispatchers.IO) {
        if (!mediaRootDir.exists()) 0L
        else mediaRootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearMediaCache(): Long = withContext(Dispatchers.IO) {
        val freed = calculateMediaStorageBytes()
        mediaRootDir.listFiles()?.forEach { it.deleteRecursively() }
        database.clearLocalMediaReferences()
        freed
    }

    /**
     * Exports a portable v1.1.0+ (WHUBBK02) AES-256-GCM encrypted backup snapshot containing
     * accounts metadata, LID/PN mappings, contacts, groups, chats, messages, and settings
     * (never raw Signal/Noise authentication keys).
     */
    suspend fun exportPortableEncryptedBackup(passphrase: String): ByteArray = withContext(Dispatchers.IO) {
        database.checkpointForBackup()
        val snapshot = database.exportPortableSnapshot()
        EncryptedBackupManager.createPortableEncryptedSnapshot(snapshot, passphrase)
    }

    suspend fun restorePortableEncryptedBackup(archiveBytes: ByteArray, passphrase: String): PortableRestoreOutcome =
        withContext(Dispatchers.IO) {
            EncryptedBackupManager.restorePortableOrLegacyBackup(archiveBytes, passphrase, database)
        }

    fun exportSanitizedLogsText(): String {
        val telemetry = getEngineHealthTelemetry()
        val acctSummary = database.getAccounts().joinToString(" | ") {
            "Slot#${it.accountId}:${it.status.wireValue}"
        }
        val header = buildString {
            appendLine("=== Rafat WhatsApp Hub v1.1.0 Sanitized Diagnostics ===")
            appendLine("Engine: ${telemetry.engineVersion}")
            appendLine("Poller Alive: ${telemetry.pollerAlive} | Events Processed: ${telemetry.processedEventsCount} | Isolated Errors: ${telemetry.isolatedEventErrors}")
            appendLine("Accounts: $acctSummary")
            appendLine("Note: Message contents, session keys, QR codes, and full phone numbers are strictly redacted.")
            appendLine("-------------------------------------------------------")
        }
        val lines = SanitizedLogger.entries.value.joinToString("\n") { it.formattedLine() }
        return header + (lines.ifBlank { "(No diagnostic entries recorded)" }) + "\n"
    }

    fun getEngineVersion(): String = engine.engineVersion()

    fun shutdown() {
        pollerJob?.cancel()
        engine.shutdown()
    }

    companion object {
        const val SETTING_THEME_MODE = "theme_mode"
        const val SETTING_LANGUAGE = "language_code"
        const val SETTING_FONT_SCALE = "font_scale_key"
        const val SETTING_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val SETTING_NOTIFICATION_PREVIEW = "notification_show_preview"
        const val SETTING_DEBUG_LOGS = "debug_logs_enabled"
    }
}
