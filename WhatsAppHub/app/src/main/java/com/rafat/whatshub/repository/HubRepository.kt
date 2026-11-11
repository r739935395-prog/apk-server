package com.rafat.whatshub.repository

import com.rafat.whatshub.bridge.DownloadMediaPayload
import com.rafat.whatshub.bridge.EngineEventEnvelope
import com.rafat.whatshub.bridge.EngineResult
import com.rafat.whatshub.bridge.SendMediaPayload
import com.rafat.whatshub.bridge.SendTextPayload
import com.rafat.whatshub.bridge.WhatsAppEngineGateway
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.database.ChatSummaryRecord
import com.rafat.whatshub.database.ContactRecord
import com.rafat.whatshub.database.GroupMemberRecord
import com.rafat.whatshub.database.GroupRecord
import com.rafat.whatshub.database.MessageMediaRecord
import com.rafat.whatshub.database.MessageRecord
import com.rafat.whatshub.database.SessionMetadataRecord
import com.rafat.whatshub.database.SyncStateRecord
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.security.EncryptedBackupManager
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import java.io.File

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
    val chatTitle: String,
    val senderName: String,
    val previewText: String
)

/**
 * Central repository uniting the encrypted SQLite database, Android Keystore per-slot keys,
 * and the Go whatsmeow engine while guaranteeing strict 4-account isolation.
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

    private val _qrStates = MutableStateFlow<Map<Int, PairingQrState>>(emptyMap())
    val qrStates: StateFlow<Map<Int, PairingQrState>> = _qrStates.asStateFlow()

    private val _pairingCodes = MutableStateFlow<Map<Int, PairingCodeState>>(emptyMap())
    val pairingCodes: StateFlow<Map<Int, PairingCodeState>> = _pairingCodes.asStateFlow()

    private val _incomingAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 32)
    val incomingAlerts: SharedFlow<IncomingMessageAlert> = _incomingAlerts.asSharedFlow()

    private val _activeConversation = MutableStateFlow<Pair<Int, String>?>(null)

    private var pollerJob: Job? = null
    @Volatile
    private var initialized = false

    init {
        scope.launch {
            database.changeVersion.collect {
                _accounts.value = database.getAccounts()
            }
        }
    }

    @Synchronized
    fun initializeEngineAndRestoreSessions() {
        if (initialized) return
        if (!sessionsRootDir.exists()) sessionsRootDir.mkdirs()
        if (!mediaRootDir.exists()) mediaRootDir.mkdirs()

        val initRes = engine.initEngine(
            sessionsDir = sessionsRootDir.absolutePath,
            mediaDir = mediaRootDir.absolutePath,
            caCertPath = caCertPemPath
        )
        if (!initRes.ok) {
            SanitizedLogger.error("Repo", "Engine init failed: ${initRes.error}")
            return
        }

        // Inject each account's isolated 256-bit AES-GCM key unwrapped from Android Keystore
        for (acct in 1..4) {
            val slotKeyHex = keyManager.getOrCreateAccountSlotKeyHex(acct)
            val keyRes = engine.setSlotKey(acct, slotKeyHex)
            if (!keyRes.ok) {
                SanitizedLogger.error("Repo", "Slot key setup failed: ${keyRes.error}", acct)
            }
        }

        // Sync initial session file presence from Go engine snapshots
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

        val debugSetting = database.getSetting(SETTING_DEBUG_LOGS, "false") == "true"
        SanitizedLogger.debugEnabled = debugSetting
        engine.setDebugLogging(debugSetting)

        startEventPoller()
        initialized = true

        // Automatically resume previously paired sessions in isolated coroutines
        scope.launch {
            for (acct in database.getAccounts()) {
                if (acct.hasSavedSession) {
                    engine.connectAccount(acct.accountId)
                }
            }
        }
    }

    private fun startEventPoller() {
        pollerJob?.cancel()
        pollerJob = scope.launch {
            while (isActive) {
                val events = engine.pollEvents(128)
                if (events.isNotEmpty()) {
                    processEngineEvents(events)
                    delay(100L)
                } else {
                    delay(400L)
                }
            }
        }
    }

    /**
     * Publicly callable in integration tests to deterministically drain and process queued engine events.
     */
    fun drainAndProcessEventsNow(): Int {
        val events = engine.pollEvents(512)
        if (events.isNotEmpty()) {
            processEngineEvents(events)
        }
        return events.size
    }

    private fun processEngineEvents(batch: List<EngineEventEnvelope>) {
        for (ev in batch) {
            when (ev.type) {
                "diagnostic_log" -> {
                    SanitizedLogger.record(
                        level = ev.logLevel.ifBlank { "INFO" },
                        module = ev.logModule.ifBlank { "Go" },
                        message = ev.logMessage,
                        accountId = ev.accountId,
                        timestampMs = ev.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                }

                "account_state" -> {
                    if (ev.accountId !in 1..4) continue
                    val nextStatus = AccountStatus.fromWire(ev.state)
                    database.updateAccountState(
                        accountId = ev.accountId,
                        status = nextStatus,
                        jid = ev.jid,
                        phone = ev.phone,
                        pushName = ev.pushName,
                        errorMessage = ev.errorMessage,
                        reconnectAttempt = ev.reconnectAttempt
                    )
                    if (nextStatus == AccountStatus.CONNECTED) {
                        clearPairingArtifacts(ev.accountId)
                        val now = System.currentTimeMillis()
                        database.upsertSessionMetadata(
                            SessionMetadataRecord(
                                accountId = ev.accountId,
                                deviceJid = ev.jid,
                                keyAlias = "whatshub_account_slot_${ev.accountId}_v1",
                                pairedAtMs = now,
                                lastConnectedAtMs = now,
                                sessionDir = File(sessionsRootDir, "account_${ev.accountId}").absolutePath
                            )
                        )
                    } else if (nextStatus == AccountStatus.NOT_CONNECTED || nextStatus == AccountStatus.DISCONNECTED || nextStatus == AccountStatus.AUTH_EXPIRED) {
                        clearPairingArtifacts(ev.accountId)
                    }
                    SanitizedLogger.info("Session", "State -> ${nextStatus.wireValue}", ev.accountId)
                }

                "qr_code" -> {
                    if (ev.accountId !in 1..4 || ev.qrCode.isBlank()) continue
                    _qrStates.value = _qrStates.value + (
                        ev.accountId to PairingQrState(
                            accountId = ev.accountId,
                            qrPayload = ev.qrCode,
                            expiresInSec = ev.expiresInSec
                        )
                    )
                    SanitizedLogger.info("Pairing", "Received fresh QR challenge (${ev.expiresInSec}s)", ev.accountId)
                }

                "pairing_code" -> {
                    if (ev.accountId !in 1..4 || ev.pairingCode.isBlank()) continue
                    _pairingCodes.value = _pairingCodes.value + (
                        ev.accountId to PairingCodeState(
                            accountId = ev.accountId,
                            phone = ev.phone,
                            formattedCode = ev.pairingCode
                        )
                    )
                    SanitizedLogger.info("Pairing", "Phone pairing code issued; awaiting primary device confirmation", ev.accountId)
                }

                "message" -> {
                    if (ev.accountId !in 1..4 || ev.chatJid.isBlank() || ev.messageId.isBlank()) continue
                    val targetMsgId = ev.editTargetId.ifBlank { ev.messageId }
                    val mediaRecord = if (ev.mediaType.isNotBlank()) {
                        MessageMediaRecord(
                            accountId = ev.accountId,
                            chatJid = ev.chatJid,
                            messageId = targetMsgId,
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

                    val activePair = _activeConversation.value
                    val isCurrentlyOpen = activePair?.first == ev.accountId && activePair.second == ev.chatJid

                    val msgRecord = MessageRecord(
                        accountId = ev.accountId,
                        chatJid = ev.chatJid,
                        messageId = targetMsgId,
                        compositeKey = "${ev.accountId}:${ev.chatJid}:$targetMsgId",
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
                        isEdited = ev.editTargetId.isNotBlank(),
                        status = ev.status.ifBlank { if (ev.isFromMe) "sent" else "delivered" },
                        timestampMs = ev.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        media = mediaRecord
                    )

                    val isNew = database.upsertMessageAndUpdateChat(
                        record = msgRecord,
                        chatTitleHint = ev.chatName,
                        incrementUnread = !ev.isHistorySync && !ev.isFromMe && !isCurrentlyOpen,
                        explicitUnreadCount = if (ev.isHistorySync && ev.unreadCount > 0) ev.unreadCount else null
                    )

                    if (isNew && !ev.isHistorySync && !ev.isFromMe && !isCurrentlyOpen && notificationsEnabled()) {
                        val acctLabel = database.getAccount(ev.accountId)?.label ?: "Account ${ev.accountId}"
                        val chatTitle = database.getChatSummary(ev.accountId, ev.chatJid)?.title ?: ev.chatJid.substringBefore('@')
                        val preview = ev.text.ifBlank { "[${ev.mediaType.ifBlank { "Message" }}]" }
                        _incomingAlerts.tryEmit(
                            IncomingMessageAlert(
                                accountId = ev.accountId,
                                accountLabel = acctLabel,
                                chatJid = ev.chatJid,
                                chatTitle = chatTitle,
                                senderName = ev.senderName.ifBlank { chatTitle },
                                previewText = preview
                            )
                        )
                    }
                }

                "reaction" -> {
                    if (ev.accountId in 1..4 && ev.chatJid.isNotBlank() && ev.targetMessageId.isNotBlank()) {
                        database.applyReaction(ev.accountId, ev.chatJid, ev.targetMessageId, ev.emoji)
                    }
                }

                "message_revoked" -> {
                    if (ev.accountId in 1..4 && ev.chatJid.isNotBlank() && ev.targetMessageId.isNotBlank()) {
                        database.markMessageRevoked(ev.accountId, ev.chatJid, ev.targetMessageId)
                    }
                }

                "receipt" -> {
                    if (ev.accountId in 1..4 && ev.chatJid.isNotBlank() && ev.messageIds.isNotEmpty()) {
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
                    if (ev.accountId in 1..4 && ev.chatJid.isNotBlank() && ev.messageId.isNotBlank()) {
                        val state = if (ev.errorMessage.isBlank() && ev.mediaPath.isNotBlank()) "ready" else "failed"
                        database.updateMediaDownloadResult(
                            accountId = ev.accountId,
                            chatJid = ev.chatJid,
                            messageId = ev.messageId,
                            localPath = ev.mediaPath,
                            downloadState = state,
                            errorMessage = ev.errorMessage
                        )
                    }
                }

                "contacts_batch" -> {
                    if (ev.accountId in 1..4 && ev.contacts.isNotEmpty()) {
                        database.upsertContacts(
                            ev.accountId,
                            ev.contacts.map { dto ->
                                ContactRecord(
                                    accountId = ev.accountId,
                                    contactJid = dto.jid,
                                    fullName = dto.fullName,
                                    firstName = dto.firstName,
                                    pushName = dto.pushName,
                                    businessName = dto.businessName,
                                    isOnline = false,
                                    lastSeenMs = 0L
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
    }

    private fun clearPairingArtifacts(accountId: Int) {
        _qrStates.value = _qrStates.value - accountId
        _pairingCodes.value = _pairingCodes.value - accountId
    }

    // --- Account Lifecycle Actions ---

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
            // Crucial: requesting a pairing code transitions state ONLY to PAIRING, never CONNECTED.
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

    // --- Strictly Account-Scoped Messaging Actions ---

    fun setActiveConversation(accountId: Int?, chatJid: String?) {
        _activeConversation.value = if (accountId != null && !chatJid.isNullOrBlank()) {
            accountId to chatJid
        } else {
            null
        }
    }

    suspend fun openAndMarkConversationRead(accountId: Int, chatJid: String) = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val unreadItems = database.clearChatUnread(accountId, chatJid)
        if (unreadItems.isNotEmpty()) {
            val groupedBySender = unreadItems.groupBy({ it.second }, { it.first })
            for ((senderJid, msgIds) in groupedBySender) {
                engine.markRead(accountId, chatJid, senderJid, msgIds)
            }
        }
    }

    suspend fun sendTextMessage(
        accountId: Int,
        chatJid: String,
        text: String,
        quotedMessageId: String = "",
        quotedSenderJid: String = "",
        quotedText: String = ""
    ): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext EngineResult(ok = false, error = "Message cannot be empty")

        val normalizedJid = if (chatJid.contains('@')) chatJid else "${chatJid.trimStart('+')}@s.whatsapp.net"
        val res = engine.sendText(
            accountId = accountId,
            payload = SendTextPayload(
                chatJid = normalizedJid,
                text = cleanText,
                quotedMessageId = quotedMessageId,
                quotedSenderJid = quotedSenderJid,
                quotedText = quotedText
            )
        )
        if (res.ok && res.messageId.isNotBlank()) {
            val ownJid = database.getAccount(accountId)?.jid ?: ""
            database.upsertMessageAndUpdateChat(
                record = MessageRecord(
                    accountId = accountId,
                    chatJid = normalizedJid,
                    messageId = res.messageId,
                    compositeKey = "$accountId:$normalizedJid:${res.messageId}",
                    senderJid = ownJid,
                    senderName = "Me",
                    isFromMe = true,
                    isGroup = normalizedJid.endsWith("@g.us"),
                    textBody = cleanText,
                    mediaType = "",
                    quotedMessageId = quotedMessageId,
                    quotedSenderJid = quotedSenderJid,
                    quotedText = quotedText,
                    reactionsSummary = "",
                    isDeleted = false,
                    isEdited = false,
                    status = "sent",
                    timestampMs = res.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                ),
                incrementUnread = false
            )
        }
        res
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
        quotedText: String = ""
    ): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val normalizedJid = if (chatJid.contains('@')) chatJid else "${chatJid.trimStart('+')}@s.whatsapp.net"
        val res = engine.sendMedia(
            accountId = accountId,
            payload = SendMediaPayload(
                chatJid = normalizedJid,
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
        if (res.ok && res.messageId.isNotBlank()) {
            val ownJid = database.getAccount(accountId)?.jid ?: ""
            val isVoice = mediaType == "voice"
            val mediaRec = MessageMediaRecord(
                accountId = accountId,
                chatJid = normalizedJid,
                messageId = res.messageId,
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
            database.upsertMessageAndUpdateChat(
                record = MessageRecord(
                    accountId = accountId,
                    chatJid = normalizedJid,
                    messageId = res.messageId,
                    compositeKey = "$accountId:$normalizedJid:${res.messageId}",
                    senderJid = ownJid,
                    senderName = "Me",
                    isFromMe = true,
                    isGroup = normalizedJid.endsWith("@g.us"),
                    textBody = caption,
                    mediaType = mediaType,
                    quotedMessageId = quotedMessageId,
                    quotedSenderJid = quotedSenderJid,
                    quotedText = quotedText,
                    reactionsSummary = "",
                    isDeleted = false,
                    isEdited = false,
                    status = "sent",
                    timestampMs = res.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    media = mediaRec
                ),
                incrementUnread = false
            )
        }
        res
    }

    suspend fun sendReaction(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        emoji: String
    ): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4) { "Invalid accountId $accountId" }
        val res = engine.sendReaction(accountId, chatJid, senderJid, targetMessageId, emoji)
        if (res.ok) {
            database.applyReaction(accountId, chatJid, targetMessageId, emoji)
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
        val res = engine.revokeMessage(accountId, chatJid, senderJid, targetMessageId)
        if (res.ok) {
            database.markMessageRevoked(accountId, chatJid, targetMessageId)
        }
        res
    }

    suspend fun downloadMessageMedia(accountId: Int, msg: MessageRecord): EngineResult = withContext(Dispatchers.IO) {
        require(accountId in 1..4 && msg.accountId == accountId) { "Account mismatch on media download" }
        val media = msg.media ?: return@withContext EngineResult(ok = false, error = "No media metadata on message")
        database.updateMediaDownloadResult(accountId, msg.chatJid, msg.messageId, "", "downloading")

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
            database.updateMediaDownloadResult(accountId, msg.chatJid, msg.messageId, res.mediaPath, "ready")
        } else {
            database.updateMediaDownloadResult(accountId, msg.chatJid, msg.messageId, "", "failed", res.error)
        }
        res
    }

    suspend fun sendTypingState(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (accountId in 1..4) {
                engine.sendTyping(accountId, chatJid, isTyping, isAudio)
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
                        lastSeenMs = 0L
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
        val cleaned = rawPhoneOrJid.trim()
        val jid = if (cleaned.contains('@')) cleaned else "${cleaned.replace(Regex("[^0-9]"), "")}@s.whatsapp.net"
        database.ensureChatExists(accountId, jid, displayName)
        return jid
    }

    fun onNetworkChanged(available: Boolean) {
        engine.onNetworkChanged(available)
    }

    // --- Settings, Storage & Encrypted Backup ---

    fun notificationsEnabled(): Boolean =
        database.getSetting(SETTING_NOTIFICATIONS_ENABLED, "true") == "true"

    fun setNotificationsEnabled(enabled: Boolean) {
        database.setSetting(SETTING_NOTIFICATIONS_ENABLED, enabled.toString())
    }

    fun getThemeMode(): String =
        database.getSetting(SETTING_THEME_MODE, "dark")

    fun setThemeMode(mode: String) {
        database.setSetting(SETTING_THEME_MODE, mode)
    }

    fun getLanguageCode(): String =
        database.getSetting(SETTING_LANGUAGE, "en")

    fun setLanguageCode(lang: String) {
        database.setSetting(SETTING_LANGUAGE, lang)
    }

    fun isDebugLoggingEnabled(): Boolean =
        database.getSetting(SETTING_DEBUG_LOGS, "false") == "true"

    fun setDebugLoggingEnabled(enabled: Boolean) {
        database.setSetting(SETTING_DEBUG_LOGS, enabled.toString())
        SanitizedLogger.debugEnabled = enabled
        engine.setDebugLogging(enabled)
    }

    suspend fun calculateMediaStorageBytes(): Long = withContext(Dispatchers.IO) {
        mediaRootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearMediaCache(): Long = withContext(Dispatchers.IO) {
        val freed = calculateMediaStorageBytes()
        mediaRootDir.listFiles()?.forEach { it.deleteRecursively() }
        database.clearLocalMediaReferences()
        freed
    }

    /**
     * Exports an AES-256-GCM encrypted backup archive containing ONLY the already-SQLCipher-encrypted
     * metadata database file (never raw Keystore keys or unencrypted session tokens).
     */
    suspend fun exportEncryptedBackup(dbFile: File, passphrase: String): ByteArray = withContext(Dispatchers.IO) {
        database.checkpointForBackup()
        val files = buildMap {
            if (dbFile.exists()) put("metadata/${dbFile.name}", dbFile)
        }
        EncryptedBackupManager.createEncryptedBackup(files, passphrase)
    }

    suspend fun verifyAndExtractEncryptedBackup(archiveBytes: ByteArray, passphrase: String, stagingDir: File): List<String> =
        withContext(Dispatchers.IO) {
            EncryptedBackupManager.restoreEncryptedBackup(archiveBytes, passphrase, stagingDir)
        }

    fun getEngineVersion(): String = engine.engineVersion()

    fun shutdown() {
        pollerJob?.cancel()
        engine.shutdown()
    }

    companion object {
        const val SETTING_THEME_MODE = "theme_mode"
        const val SETTING_LANGUAGE = "language_code"
        const val SETTING_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val SETTING_DEBUG_LOGS = "debug_logs_enabled"
    }
}
