package com.rafat.whatshub.reliability

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.rafat.whatshub.bridge.DownloadMediaPayload
import com.rafat.whatshub.bridge.EngineContactDto
import com.rafat.whatshub.bridge.EngineEventEnvelope
import com.rafat.whatshub.bridge.EngineGroupDto
import com.rafat.whatshub.bridge.EngineResult
import com.rafat.whatshub.bridge.EngineSlotSnapshot
import com.rafat.whatshub.bridge.PeekedEventBatch
import com.rafat.whatshub.bridge.SendMediaPayload
import com.rafat.whatshub.bridge.SendTextPayload
import com.rafat.whatshub.bridge.WhatsAppEngineGateway
import com.rafat.whatshub.bridge.decodeEnvelopeArraySafely
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.database.ContactRecord
import com.rafat.whatshub.database.IdentityResolver
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.InMemoryAesGcmWrapper
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.security.PortableRestoreOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Deterministic Peek+Ack engine test harness supporting:
 *  - Non-destructive peekEvents + explicit ackEvents
 *  - Client-assigned 3EB0... message ID correlation
 *  - Simulated slow network sends (to test Pending state & caller coroutine cancellation)
 *  - Offline/online slot toggling
 */
private class V11TestEngineGateway : WhatsAppEngineGateway {
    override val isNativeAvailable: Boolean = true

    val connectedSlots = mutableSetOf<Int>()
    private var nextEventSeq = 1L
    val routerBuffer = ArrayDeque<EngineEventEnvelope>()
    val sentTextPayloads = mutableListOf<Pair<Int, SendTextPayload>>()
    val sentMediaPayloads = mutableListOf<Pair<Int, SendMediaPayload>>()
    var artificialSendDelayMs: Long = 0L

    override fun initEngine(sessionsDir: String, mediaDir: String, caCertPath: String): EngineResult =
        EngineResult(ok = true)

    override fun setSlotKey(accountId: Int, keyHex: String): EngineResult = EngineResult(ok = true)

    override fun connectAccount(accountId: Int): EngineResult {
        connectedSlots.add(accountId)
        enqueueEvent(
            EngineEventEnvelope(
                type = "account_state",
                accountId = accountId,
                state = "connected",
                jid = "96777000000$accountId:1@s.whatsapp.net",
                phone = "96777000000$accountId",
                pushName = "Rafat Slot $accountId",
                timestampMs = System.currentTimeMillis()
            )
        )
        return EngineResult(ok = true)
    }

    override fun disconnectAccount(accountId: Int): EngineResult {
        connectedSlots.remove(accountId)
        enqueueEvent(
            EngineEventEnvelope(
                type = "account_state",
                accountId = accountId,
                state = "disconnected",
                timestampMs = System.currentTimeMillis()
            )
        )
        return EngineResult(ok = true)
    }

    override fun removeAccount(accountId: Int): EngineResult {
        connectedSlots.remove(accountId)
        return EngineResult(ok = true)
    }

    override fun requestPairingCode(accountId: Int, phone: String): EngineResult =
        EngineResult(ok = true, pairingCode = "RFTH-110$accountId")

    override fun getAccountsSnapshot(): List<EngineSlotSnapshot> =
        (1..4).map { acct ->
            EngineSlotSnapshot(
                accountId = acct,
                state = if (acct in connectedSlots) "connected" else "not_connected",
                hasSavedSession = acct in connectedSlots
            )
        }

    fun enqueueEvent(ev: EngineEventEnvelope): Long {
        val assigned = if (ev.seq > 0L) ev else ev.copy(seq = nextEventSeq++)
        routerBuffer.addLast(assigned)
        return assigned.seq
    }

    override fun peekEvents(maxBatch: Int): PeekedEventBatch {
        val list = routerBuffer.take(maxBatch)
        val highest = list.maxOfOrNull { it.seq } ?: 0L
        return PeekedEventBatch(events = list, highestRawSeq = highest)
    }

    override fun ackEvents(upToSeq: Long): EngineResult {
        while (routerBuffer.isNotEmpty() && routerBuffer.first().seq <= upToSeq) {
            routerBuffer.removeFirst()
        }
        return EngineResult(ok = true)
    }

    override fun pollEvents(maxBatch: Int): List<EngineEventEnvelope> {
        val peeked = peekEvents(maxBatch)
        if (peeked.highestRawSeq > 0L) ackEvents(peeked.highestRawSeq)
        return peeked.events
    }

    override fun sendText(accountId: Int, payload: SendTextPayload): EngineResult {
        if (artificialSendDelayMs > 0L) {
            Thread.sleep(artificialSendDelayMs)
        }
        if (accountId !in connectedSlots) {
            return EngineResult(ok = false, error = "Account $accountId offline")
        }
        sentTextPayloads.add(accountId to payload)
        val assignedId = payload.clientMessageId.ifBlank { "SRV_${System.nanoTime()}" }
        return EngineResult(ok = true, messageId = assignedId, timestampMs = System.currentTimeMillis())
    }

    override fun sendMedia(accountId: Int, payload: SendMediaPayload): EngineResult {
        if (accountId !in connectedSlots) {
            return EngineResult(ok = false, error = "Account $accountId offline")
        }
        sentMediaPayloads.add(accountId to payload)
        val assignedId = payload.clientMessageId.ifBlank { "MED_${System.nanoTime()}" }
        return EngineResult(ok = true, messageId = assignedId, timestampMs = System.currentTimeMillis())
    }

    override fun sendReaction(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String, emoji: String): EngineResult =
        EngineResult(ok = accountId in connectedSlots, messageId = "RX_1")

    override fun revokeMessage(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String): EngineResult =
        EngineResult(ok = accountId in connectedSlots, messageId = "REV_1")

    override fun downloadMedia(accountId: Int, payload: DownloadMediaPayload): EngineResult =
        EngineResult(ok = true, mediaPath = "/tmp/downloaded_${payload.messageId}.bin")

    override fun markRead(accountId: Int, chatJid: String, senderJid: String, messageIds: List<String>): EngineResult =
        EngineResult(ok = true)

    override fun sendTyping(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean): EngineResult =
        EngineResult(ok = true)

    override fun getContacts(accountId: Int): List<EngineContactDto> = emptyList()
    override fun getGroups(accountId: Int): List<EngineGroupDto> = emptyList()
    override fun getGroupInfo(accountId: Int, groupJid: String): EngineGroupDto? = null
    override fun onNetworkChanged(available: Boolean): EngineResult = EngineResult(ok = true)
    override fun setDebugLogging(enabled: Boolean): EngineResult = EngineResult(ok = true)
    override fun shutdown(): EngineResult = EngineResult(ok = true)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessagingReliabilityAndMigrationV11Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: WhatsAppHubDatabase
    private lateinit var engine: V11TestEngineGateway
    private lateinit var repo: HubRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dbFile = File(tempFolder.newFolder("db_v11"), "whatshub_test.db")
        db = WhatsAppHubDatabase(context, dbFile.absolutePath, FrameworkSQLiteOpenHelperFactory())
        val keyManager = KeystoreKeyManager(tempFolder.newFolder("keys"), InMemoryAesGcmWrapper())
        engine = V11TestEngineGateway()
        repo = HubRepository(
            database = db,
            keyManager = keyManager,
            engine = engine,
            sessionsRootDir = tempFolder.newFolder("sessions"),
            mediaRootDir = tempFolder.newFolder("media"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    @After
    fun tearDown() {
        repo.shutdown()
        db.close()
    }

    @Test
    fun all22MessagingScenariosEndToEndVerification() = runBlocking {
        // Connect all 4 accounts
        for (acct in 1..4) assertTrue(repo.connectAccount(acct).ok)
        repo.drainAndProcessEventsNow()

        // Scenario 1: Send text to saved contact (Pending persisted BEFORE send + exact 3EB0 clientMessageId + receipts)
        val savedJid = "967771112222@s.whatsapp.net"
        db.upsertContacts(
            1,
            listOf(
                ContactRecord(
                    accountId = 1,
                    contactJid = savedJid,
                    fullName = "Ahmad Al-Yemeni",
                    firstName = "Ahmad",
                    pushName = "Ahmad",
                    businessName = "",
                    isOnline = true,
                    lastSeenMs = 0L,
                    pnJid = savedJid
                )
            )
        )
        var pendingStatusObservedInDb: String? = null
        val send1 = repo.sendTextMessage(
            accountId = 1,
            chatJid = savedJid,
            text = "Salam Ahmad",
            onPendingPersisted = { pendingId ->
                pendingStatusObservedInDb = db.getMessageById(1, savedJid, pendingId)?.status
            }
        )
        assertEquals("pending", pendingStatusObservedInDb)
        assertTrue(send1.ok)
        assertTrue(send1.messageId.startsWith("3EB0"))
        assertEquals("sent", db.getMessageById(1, savedJid, send1.messageId)?.status)

        // Scenario 2: Send text to unsaved phone number
        val unsavedJid = repo.startNewConversation(1, "+967 775 551 111")
        assertEquals("967775551111@s.whatsapp.net", unsavedJid)
        assertTrue(repo.sendTextMessage(1, unsavedJid, "Hello unsaved number").ok)

        // Scenario 3 & 7: @lid contact -> unmapped clean display -> verified LID<->PN mapping merges thread & receipts!
        val rawLid = "18899900011122@lid"
        val mappedPn = "967774443333@s.whatsapp.net"
        engine.enqueueEvent(
            EngineEventEnvelope(
                type = "message",
                accountId = 1,
                chatJid = rawLid,
                rawChatJid = rawLid,
                messageId = "LID_MSG_1",
                senderJid = rawLid,
                senderName = "",
                text = "First message over unmapped LID",
                timestampMs = 1_700_000_100_000L
            )
        )
        repo.drainAndProcessEventsNow()
        val unmappedChat = db.getChatSummary(1, rawLid)!!
        // Must NEVER leak raw @lid digits to user-facing cleanTitle!
        assertFalse(unmappedChat.cleanTitle.contains("18899900011122"))
        assertEquals("WhatsApp Contact", unmappedChat.cleanTitle)

        // Send reply to @lid contact before mapping arrives (Scenario 3)
        val lidReply = repo.sendTextMessage(1, rawLid, "Replying before PN mapping")
        assertTrue(lidReply.ok)

        // Now verified LID <-> PN mapping arrives on incoming message (Scenario 7)
        engine.enqueueEvent(
            EngineEventEnvelope(
                type = "message",
                accountId = 1,
                chatJid = mappedPn,
                rawChatJid = rawLid,
                lidJid = rawLid,
                pnJid = mappedPn,
                mappingSource = "message_source_alt",
                messageId = "LID_MSG_2",
                senderJid = mappedPn,
                senderName = "Khaled Saleh",
                chatName = "Khaled Saleh",
                text = "Second message with verified PN",
                timestampMs = 1_700_000_200_000L
            )
        )
        // Receipt arrives with raw @lid chatJid for our earlier outgoing message!
        engine.enqueueEvent(
            EngineEventEnvelope(
                type = "receipt",
                accountId = 1,
                chatJid = rawLid,
                messageIds = listOf(lidReply.messageId),
                receiptStatus = "read"
            )
        )
        repo.drainAndProcessEventsNow()

        // Verify @lid thread seamlessly merged into canonical @s.whatsapp.net thread with all 3 messages & Read receipt!
        val mergedMsgs = db.getConversationMessages(1, mappedPn)
        assertEquals(3, mergedMsgs.size)
        assertEquals("read", db.getMessageById(1, mappedPn, lidReply.messageId)?.status)

        // Scenarios 4 & 8: Send & receive group messages
        val groupJid = "120363099887766@g.us"
        assertTrue(repo.sendTextMessage(1, groupJid, "Hello Engineering Group").ok)
        engine.enqueueEvent(
            EngineEventEnvelope(
                type = "message",
                accountId = 1,
                chatJid = groupJid,
                chatName = "Rafat Engineering",
                isGroup = true,
                messageId = "GRP_IN_1",
                senderJid = "967778889990@s.whatsapp.net",
                senderName = "Tariq",
                text = "Group reply",
                timestampMs = 1_700_000_300_000L
            )
        )

        // Scenarios 5 & 6: Receive text from saved contact & unsaved phone number
        engine.enqueueEvent(
            EngineEventEnvelope(
                type = "message",
                accountId = 1,
                chatJid = savedJid,
                messageId = "SAVED_IN_1",
                senderJid = savedJid,
                senderName = "Ahmad Al-Yemeni",
                text = "Wa Alaikum Assalam",
                timestampMs = 1_700_000_310_000L
            )
        )
        val brandNewCaller = "967776665544@s.whatsapp.net"
        engine.enqueueEvent(
            EngineEventEnvelope(
                type = "message",
                accountId = 1,
                chatJid = brandNewCaller,
                messageId = "UNSAVED_IN_1",
                senderJid = brandNewCaller,
                text = "Inquiry from new customer",
                timestampMs = 1_700_000_320_000L
            )
        )
        repo.drainAndProcessEventsNow()
        assertEquals("+967776665544", db.getChatSummary(1, brandNewCaller)?.cleanTitle)

        // Scenarios 9, 10, 11, 12: Send & Receive Image, Video, Voice Note, Document
        val mediaTypes = listOf(
            Triple("image", "photo.jpg", "image/jpeg"),
            Triple("video", "clip.mp4", "video/mp4"),
            Triple("voice", "note.m4a", "audio/mp4"),
            Triple("document", "contract.pdf", "application/pdf")
        )
        for ((mType, fName, mime) in mediaTypes) {
            val f = tempFolder.newFile("out_$fName").apply { writeBytes(ByteArray(256) { 0x42 }) }
            val mRes = repo.sendMediaMessage(
                accountId = 1,
                chatJid = savedJid,
                mediaType = mType,
                localFile = f,
                mimeType = mime,
                caption = "Caption $mType",
                durationSec = if (mType == "voice") 9 else 0
            )
            assertTrue("Outgoing $mType failed", mRes.ok)

            engine.enqueueEvent(
                EngineEventEnvelope(
                    type = "message",
                    accountId = 1,
                    chatJid = savedJid,
                    messageId = "IN_MEDIA_$mType",
                    senderJid = savedJid,
                    mediaType = mType,
                    mimeType = mime,
                    fileName = fName,
                    fileSize = 1024L,
                    durationSec = if (mType == "voice") 12 else 0,
                    isVoiceNote = mType == "voice",
                    directPath = "/v/t62/$fName",
                    mediaKeyB64 = "dGVzdGtleQ==",
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
        repo.drainAndProcessEventsNow()
        val inImg = db.getMessageById(1, savedJid, "IN_MEDIA_image")!!
        assertTrue(repo.downloadMessageMedia(1, inImg).ok)
        assertEquals("ready", db.getMessageById(1, savedJid, "IN_MEDIA_image")?.media?.downloadState)

        // Scenario 13, 14, 15: Reply, React, Delete for Me, Delete for Everyone
        val replyRes = repo.sendTextMessage(
            accountId = 1,
            chatJid = savedJid,
            text = "Quoted reply body",
            quotedMessageId = "SAVED_IN_1",
            quotedSenderJid = savedJid,
            quotedText = "Wa Alaikum Assalam"
        )
        assertTrue(replyRes.ok)
        assertEquals("SAVED_IN_1", db.getMessageById(1, savedJid, replyRes.messageId)?.quotedMessageId)

        assertTrue(repo.sendReaction(1, savedJid, savedJid, "SAVED_IN_1", "❤️").ok)
        assertTrue(db.getMessageById(1, savedJid, "SAVED_IN_1")!!.reactionsSummary.contains("❤️"))

        assertTrue(repo.deleteMessageForEveryone(1, savedJid, "967770000001@s.whatsapp.net", replyRes.messageId).ok)
        assertTrue(db.getMessageById(1, savedJid, replyRes.messageId)!!.isDeleted)

        repo.deleteMessageForMe(1, savedJid, "IN_MEDIA_document")
        assertEquals(null, db.getMessageById(1, savedJid, "IN_MEDIA_document"))

        // Scenario 16, 21, 22: Disconnect Account 2 -> offline send becomes Failed -> Accounts 1,3,4 unaffected -> Reconnect Account 2 & Retry!
        repo.disconnectAccount(2)
        repo.drainAndProcessEventsNow()
        assertEquals(AccountStatus.DISCONNECTED, db.getAccount(2)?.status)

        val offlineSend = repo.sendTextMessage(2, savedJid, "Queued while Account 2 offline")
        assertFalse(offlineSend.ok)
        val failedRow = db.getConversationMessages(2, savedJid).single()
        assertEquals("failed", failedRow.status)
        assertFalse(failedRow.transmissionAck)

        // Accounts 1, 3, 4 remain connected and send normally (Scenario 19 & 21)
        for (activeAcct in listOf(1, 3, 4)) {
            assertTrue(repo.sendTextMessage(activeAcct, savedJid, "Active slot $activeAcct").ok)
        }

        // Reconnect Account 2 & Retry failed message without creating duplicates! (Scenario 16 & 22)
        assertTrue(repo.connectAccount(2).ok)
        repo.drainAndProcessEventsNow()
        val retryRes = repo.retryFailedMessage(2, savedJid, failedRow.messageId)
        assertTrue(retryRes.ok)
        val acct2AfterRetry = db.getConversationMessages(2, savedJid)
        assertEquals(1, acct2AfterRetry.size)
        assertEquals("sent", acct2AfterRetry.single().status)
        assertTrue(acct2AfterRetry.single().transmissionAck)

        // Scenario 17: Send message and IMMEDIATELY cancel caller coroutine (simulating pressing Back mid-send!)
        engine.artificialSendDelayMs = 120L
        var capturedPendingId = ""
        val pendingReady = CompletableDeferred<String>()
        val callerJob = CoroutineScope(Dispatchers.Default).launch {
            repo.sendTextMessage(
                accountId = 1,
                chatJid = unsavedJid,
                text = "Must survive Back navigation",
                onPendingPersisted = { id ->
                    capturedPendingId = id
                    pendingReady.complete(id)
                }
            )
        }
        val pid = pendingReady.await()
        callerJob.cancel() // Simulate ViewModelCleared / Back navigation while JNI send is in-flight!
        delay(240L)
        engine.artificialSendDelayMs = 0L

        val survivedMsg = db.getMessageById(1, unsavedJid, pid)
        assertNotNull("Message lost on caller cancellation!", survivedMsg)
        assertEquals("sent", survivedMsg!!.status)
        assertEquals(capturedPendingId, survivedMsg.messageId)
    }

    @Test
    fun scenario18And20_burstOver400MessagesNewestWindowAndRestartRecovery() = runBlocking {
        assertTrue(repo.connectAccount(1).ok)
        repo.drainAndProcessEventsNow()

        val burstChat = "967779990000@s.whatsapp.net"
        val baseTs = 1_710_000_000_000L
        val startElapsed = System.currentTimeMillis()

        // Scenario 18: Ingest 1,200 messages (> 400 old limit!) and verify newest window is returned in ASC order!
        for (i in 1..1200) {
            engine.enqueueEvent(
                EngineEventEnvelope(
                    type = "message",
                    accountId = 1,
                    chatJid = burstChat,
                    messageId = "BURST_$i",
                    senderJid = burstChat,
                    senderName = "High Volume Peer",
                    text = "Burst message #$i",
                    timestampMs = baseTs + i * 100L
                )
            )
        }
        var totalDrained = 0
        while (true) {
            val n = repo.drainAndProcessEventsNow()
            if (n == 0) break
            totalDrained += n
        }
        val elapsedMs = System.currentTimeMillis() - startElapsed
        assertEquals(1200, totalDrained)
        assertTrue("1,200-message burst took too long (${elapsedMs}ms)", elapsedMs < 15_000L)

        val window300 = db.getConversationMessages(1, burstChat, limit = 300)
        assertEquals(300, window300.size)
        assertEquals("Burst message #901", window300.first().textBody)
        assertEquals("Burst message #1200", window300.last().textBody)

        // Scenario 20: Simulate an interrupted un-ACKed pending message followed by app/DB restart
        db.upsertMessageAndUpdateChat(
            record = window300.last().copy(
                messageId = "CRASH_PENDING_1",
                compositeKey = "1:$burstChat:CRASH_PENDING_1",
                isFromMe = true,
                textBody = "Interrupted before process death",
                status = "pending",
                transmissionAck = false,
                timestampMs = baseTs + 200_000L
            ),
            incrementUnread = false
        )
        val recoveredCount = db.recoverInterruptedPendingMessages()
        assertEquals(1, recoveredCount)
        assertEquals("failed", db.getMessageById(1, burstChat, "CRASH_PENDING_1")?.status)
    }

    @Test
    fun nonDestructiveDatabaseMigrationV1ToV2PreservesAllUserData() {
        val migDir = tempFolder.newFolder("db_v1_to_v2")
        val migPath = File(migDir, "legacy_v1.db").absolutePath

        // 1. Create an authentic DB_VERSION = 1 database with v1 schema & user data
        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migPath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(sqldb: SupportSQLiteDatabase) {
                        sqldb.execSQL(
                            """
                            CREATE TABLE accounts (
                                account_id INTEGER PRIMARY KEY CHECK (account_id BETWEEN 1 AND 4),
                                label TEXT NOT NULL,
                                jid TEXT NOT NULL DEFAULT '',
                                phone TEXT NOT NULL DEFAULT '',
                                push_name TEXT NOT NULL DEFAULT '',
                                status TEXT NOT NULL DEFAULT 'not_connected',
                                error_message TEXT NOT NULL DEFAULT '',
                                reconnect_attempt INTEGER NOT NULL DEFAULT 0,
                                last_connected_ms INTEGER NOT NULL DEFAULT 0,
                                has_saved_session INTEGER NOT NULL DEFAULT 0,
                                updated_at_ms INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE chats (
                                account_id INTEGER NOT NULL,
                                chat_jid TEXT NOT NULL,
                                title TEXT NOT NULL DEFAULT '',
                                is_group INTEGER NOT NULL DEFAULT 0,
                                unread_count INTEGER NOT NULL DEFAULT 0,
                                last_message_id TEXT NOT NULL DEFAULT '',
                                last_message_preview TEXT NOT NULL DEFAULT '',
                                last_message_type TEXT NOT NULL DEFAULT 'text',
                                last_sender_name TEXT NOT NULL DEFAULT '',
                                last_message_ts_ms INTEGER NOT NULL DEFAULT 0,
                                is_muted INTEGER NOT NULL DEFAULT 0,
                                is_typing INTEGER NOT NULL DEFAULT 0,
                                typing_user TEXT NOT NULL DEFAULT '',
                                PRIMARY KEY (account_id, chat_jid)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE messages (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                account_id INTEGER NOT NULL,
                                chat_jid TEXT NOT NULL,
                                message_id TEXT NOT NULL,
                                sender_jid TEXT NOT NULL DEFAULT '',
                                sender_name TEXT NOT NULL DEFAULT '',
                                is_from_me INTEGER NOT NULL DEFAULT 0,
                                is_group INTEGER NOT NULL DEFAULT 0,
                                text_body TEXT NOT NULL DEFAULT '',
                                media_type TEXT NOT NULL DEFAULT '',
                                quoted_message_id TEXT NOT NULL DEFAULT '',
                                quoted_sender_jid TEXT NOT NULL DEFAULT '',
                                quoted_text TEXT NOT NULL DEFAULT '',
                                reactions_summary TEXT NOT NULL DEFAULT '',
                                is_deleted INTEGER NOT NULL DEFAULT 0,
                                is_edited INTEGER NOT NULL DEFAULT 0,
                                status TEXT NOT NULL DEFAULT 'sent',
                                timestamp_ms INTEGER NOT NULL DEFAULT 0,
                                UNIQUE (account_id, chat_jid, message_id)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE message_media (
                                account_id INTEGER NOT NULL,
                                chat_jid TEXT NOT NULL,
                                message_id TEXT NOT NULL,
                                media_type TEXT NOT NULL,
                                local_path TEXT NOT NULL DEFAULT '',
                                mime_type TEXT NOT NULL DEFAULT '',
                                file_name TEXT NOT NULL DEFAULT '',
                                file_size INTEGER NOT NULL DEFAULT 0,
                                duration_sec INTEGER NOT NULL DEFAULT 0,
                                is_voice_note INTEGER NOT NULL DEFAULT 0,
                                direct_path TEXT NOT NULL DEFAULT '',
                                media_key_b64 TEXT NOT NULL DEFAULT '',
                                file_sha256_b64 TEXT NOT NULL DEFAULT '',
                                file_enc_sha256_b64 TEXT NOT NULL DEFAULT '',
                                download_state TEXT NOT NULL DEFAULT 'none',
                                error_message TEXT NOT NULL DEFAULT '',
                                PRIMARY KEY (account_id, chat_jid, message_id)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE contacts (
                                account_id INTEGER NOT NULL,
                                contact_jid TEXT NOT NULL,
                                full_name TEXT NOT NULL DEFAULT '',
                                first_name TEXT NOT NULL DEFAULT '',
                                push_name TEXT NOT NULL DEFAULT '',
                                business_name TEXT NOT NULL DEFAULT '',
                                is_online INTEGER NOT NULL DEFAULT 0,
                                last_seen_ms INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY (account_id, contact_jid)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE groups (
                                account_id INTEGER NOT NULL,
                                group_jid TEXT NOT NULL,
                                name TEXT NOT NULL DEFAULT '',
                                topic TEXT NOT NULL DEFAULT '',
                                owner_jid TEXT NOT NULL DEFAULT '',
                                participant_count INTEGER NOT NULL DEFAULT 0,
                                is_announce INTEGER NOT NULL DEFAULT 0,
                                is_locked INTEGER NOT NULL DEFAULT 0,
                                created_at_ms INTEGER NOT NULL DEFAULT 0,
                                updated_at_ms INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY (account_id, group_jid)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE group_members (
                                account_id INTEGER NOT NULL,
                                group_jid TEXT NOT NULL,
                                member_jid TEXT NOT NULL,
                                phone_jid TEXT NOT NULL DEFAULT '',
                                is_admin INTEGER NOT NULL DEFAULT 0,
                                is_super_admin INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY (account_id, group_jid, member_jid)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE session_metadata (
                                account_id INTEGER PRIMARY KEY CHECK (account_id BETWEEN 1 AND 4),
                                device_jid TEXT NOT NULL DEFAULT '',
                                key_alias TEXT NOT NULL DEFAULT '',
                                paired_at_ms INTEGER NOT NULL DEFAULT 0,
                                last_connected_at_ms INTEGER NOT NULL DEFAULT 0,
                                session_dir TEXT NOT NULL DEFAULT ''
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL(
                            """
                            CREATE TABLE sync_state (
                                account_id INTEGER NOT NULL,
                                sync_type TEXT NOT NULL,
                                last_chunk_index INTEGER NOT NULL DEFAULT 0,
                                progress_pct INTEGER NOT NULL DEFAULT 0,
                                imported_chats INTEGER NOT NULL DEFAULT 0,
                                imported_messages INTEGER NOT NULL DEFAULT 0,
                                status TEXT NOT NULL DEFAULT 'idle',
                                updated_at_ms INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY (account_id, sync_type)
                            )
                            """.trimIndent()
                        )
                        sqldb.execSQL("CREATE TABLE settings (setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)")

                        // Seed critical v1 user data that MUST survive v1 -> v2 upgrade!
                        sqldb.execSQL("INSERT INTO accounts (account_id, label, phone, status, has_saved_session) VALUES (1, 'VIP Work Line', '967771234567', 'connected', 1)")
                        sqldb.execSQL("INSERT INTO chats (account_id, chat_jid, title, unread_count, last_message_id, last_message_preview, last_message_ts_ms) VALUES (1, '967770009999@s.whatsapp.net', 'Sanaa Partner', 2, 'V1_MSG_99', 'Important contract V1', 1700000999000)")
                        sqldb.execSQL("INSERT INTO messages (account_id, chat_jid, message_id, sender_jid, sender_name, is_from_me, text_body, status, timestamp_ms) VALUES (1, '967770009999@s.whatsapp.net', 'V1_MSG_99', '967770009999@s.whatsapp.net', 'Sanaa Partner', 0, 'Important contract V1', 'delivered', 1700000999000)")
                    }

                    override fun onUpgrade(sqldb: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        v1Helper.writableDatabase.close()

        // 2. Open with v1.1.0 WhatsAppHubDatabase (DB_VERSION = 2) and verify zero data loss!
        val upgradedDb = WhatsAppHubDatabase(context, migPath, FrameworkSQLiteOpenHelperFactory())
        try {
            assertEquals("VIP Work Line", upgradedDb.getAccount(1)?.label)
            val preservedMsgs = upgradedDb.getConversationMessages(1, "967770009999@s.whatsapp.net")
            assertEquals(1, preservedMsgs.size)
            assertEquals("Important contract V1", preservedMsgs.single().textBody)

            // Verify new v2 features (pinning & identity_mappings) work immediately on upgraded DB
            upgradedDb.setChatPinned(1, "967770009999@s.whatsapp.net", true)
            assertTrue(upgradedDb.getChatSummary(1, "967770009999@s.whatsapp.net")!!.isPinned)
        } finally {
            upgradedDb.close()
        }
    }

    @Test
    fun portableEncryptedBackupV2RoundTripAndMalformedJsonBatchIsolation() = runBlocking {
        // 1. Verify per-element JSON fault isolation in WhatsAppEngineGateway
        val mixedJson = """
            [
              {"seq":1,"type":"message","account_id":1,"chat_jid":"967771111111@s.whatsapp.net","message_id":"M1","text":"Valid 1"},
              {"seq":2,"type":12345,"corrupt_field":{"broken":[1,2,3]}},
              {"seq":3,"type":"message","account_id":2,"chat_jid":"967772222222@s.whatsapp.net","message_id":"M3","text":"Valid 3"}
            ]
        """.trimIndent()
        val isolatedBatch = decodeEnvelopeArraySafely(mixedJson)
        assertTrue(isolatedBatch.events.size >= 2)
        assertEquals(3L, isolatedBatch.highestRawSeq)
        assertEquals("Valid 1", isolatedBatch.events.first().text)
        assertEquals("Valid 3", isolatedBatch.events.last().text)

        // 2. Verify WHUBBK02 portable encrypted backup exports & restores onto a brand-new installation!
        assertTrue(repo.connectAccount(1).ok)
        repo.drainAndProcessEventsNow()
        repo.renameAccount(1, "Rafat Executive")
        assertTrue(repo.sendTextMessage(1, "967773334444@s.whatsapp.net", "Portable backup secret message").ok)

        val archiveBytes = repo.exportPortableEncryptedBackup("StrongPassphrase#2026")
        assertEquals("WHUBBK02", archiveBytes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertFalse(String(archiveBytes, Charsets.ISO_8859_1).contains("Portable backup secret message"))

        // Restore into a completely fresh database (simulating clean v1.1.0 install on new device/key!)
        val freshDb = WhatsAppHubDatabase(context, null, FrameworkSQLiteOpenHelperFactory())
        try {
            val outcome = com.rafat.whatshub.security.EncryptedBackupManager.restorePortableOrLegacyBackup(
                encryptedArchive = archiveBytes,
                passphrase = "StrongPassphrase#2026",
                database = freshDb
            )
            assertTrue(outcome is PortableRestoreOutcome.RestoredV2)
            assertEquals("Rafat Executive", freshDb.getAccount(1)?.label)
            val restoredMsgs = freshDb.getConversationMessages(1, "967773334444@s.whatsapp.net")
            assertEquals(1, restoredMsgs.size)
            assertEquals("Portable backup secret message", restoredMsgs.single().textBody)
        } finally {
            freshDb.close()
        }

        // 3. Verify IdentityResolver strict formatting rules
        assertEquals("", IdentityResolver.formatVerifiedPhone("18899900011122@lid"))
        assertEquals("+967771234567", IdentityResolver.formatVerifiedPhone("967771234567:4@s.whatsapp.net"))
        assertEquals("WhatsApp Contact", IdentityResolver.cleanDisplayTitle("18899900011122@lid", "18899900011122"))
    }
}
