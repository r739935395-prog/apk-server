package com.rafat.whatshub.bridge

import android.app.Application
import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.InMemoryAesGcmWrapper
import com.rafat.whatshub.security.KeystoreKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ControlledEngineGateway : WhatsAppEngineGateway {
    override val isNativeAvailable: Boolean = true
    val slotKeys = mutableMapOf<Int, String>()
    val connectedSlots = mutableSetOf<Int>()
    val sentLog = mutableListOf<Triple<Int, String, String>>() // (accountId, chatJid, text)
    val eventQueue = ArrayDeque<EngineEventEnvelope>()
    var networkAvailable = true
    private var nextMsgSeq = 1

    override fun engineVersion(): String = "test-engine/1.0"

    override fun initEngine(sessionsDir: String, mediaDir: String, caCertPath: String): EngineResult =
        EngineResult(ok = true)

    override fun setSlotKey(accountId: Int, keyHex: String): EngineResult {
        slotKeys[accountId] = keyHex
        return EngineResult(ok = true)
    }

    override fun connectAccount(accountId: Int): EngineResult {
        connectedSlots.add(accountId)
        eventQueue.addLast(
            EngineEventEnvelope(
                type = "account_state",
                accountId = accountId,
                state = "connected",
                jid = "96777000000$accountId@s.whatsapp.net",
                phone = "96777000000$accountId",
                pushName = "Acct $accountId"
            )
        )
        return EngineResult(ok = true)
    }

    override fun disconnectAccount(accountId: Int): EngineResult {
        connectedSlots.remove(accountId)
        eventQueue.addLast(
            EngineEventEnvelope(
                type = "account_state",
                accountId = accountId,
                state = "disconnected"
            )
        )
        return EngineResult(ok = true)
    }

    override fun removeAccount(accountId: Int): EngineResult {
        connectedSlots.remove(accountId)
        eventQueue.addLast(
            EngineEventEnvelope(
                type = "account_state",
                accountId = accountId,
                state = "not_connected"
            )
        )
        return EngineResult(ok = true)
    }

    override fun requestPairingCode(accountId: Int, phone: String): EngineResult =
        EngineResult(ok = true, pairingCode = "ABCD-123$accountId")

    override fun getAccountsSnapshot(): List<EngineSlotSnapshot> =
        (1..4).map { acct ->
            EngineSlotSnapshot(
                accountId = acct,
                state = if (acct in connectedSlots) "connected" else "not_connected",
                hasSavedSession = acct in connectedSlots
            )
        }

    override fun pollEvents(maxBatch: Int): List<EngineEventEnvelope> {
        val out = mutableListOf<EngineEventEnvelope>()
        while (out.size < maxBatch && eventQueue.isNotEmpty()) {
            out.add(eventQueue.removeFirst())
        }
        return out
    }

    override fun sendText(accountId: Int, payload: SendTextPayload): EngineResult {
        if (accountId !in connectedSlots) {
            return EngineResult(ok = false, error = "Account $accountId is disconnected")
        }
        sentLog.add(Triple(accountId, payload.chatJid, payload.text))
        return EngineResult(
            ok = true,
            messageId = "OUT_${accountId}_${nextMsgSeq++}",
            timestampMs = System.currentTimeMillis()
        )
    }

    override fun sendMedia(accountId: Int, payload: SendMediaPayload): EngineResult =
        EngineResult(ok = true, messageId = "MEDIA_${accountId}_${nextMsgSeq++}", timestampMs = System.currentTimeMillis())

    override fun sendReaction(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String, emoji: String): EngineResult =
        EngineResult(ok = true, messageId = "RX_${nextMsgSeq++}")

    override fun revokeMessage(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String): EngineResult =
        EngineResult(ok = true, messageId = "REV_${nextMsgSeq++}")

    override fun downloadMedia(accountId: Int, payload: DownloadMediaPayload): EngineResult =
        EngineResult(ok = true, mediaPath = "/tmp/acct_$accountId/${payload.messageId}.jpg")

    override fun markRead(accountId: Int, chatJid: String, senderJid: String, messageIds: List<String>): EngineResult =
        EngineResult(ok = true)

    override fun sendTyping(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean): EngineResult =
        EngineResult(ok = true)

    override fun getContacts(accountId: Int): List<EngineContactDto> = emptyList()
    override fun getGroups(accountId: Int): List<EngineGroupDto> = emptyList()
    override fun getGroupInfo(accountId: Int, groupJid: String): EngineGroupDto? = null

    override fun onNetworkChanged(available: Boolean): EngineResult {
        networkAvailable = available
        return EngineResult(ok = true)
    }

    override fun setDebugLogging(enabled: Boolean): EngineResult = EngineResult(ok = true)
    override fun shutdown(): EngineResult = EngineResult(ok = true)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FourAccountIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: WhatsAppHubDatabase
    private lateinit var engine: ControlledEngineGateway
    private lateinit var repo: HubRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = WhatsAppHubDatabase(context, null, FrameworkSQLiteOpenHelperFactory())
        val keyManager = KeystoreKeyManager(tempFolder.newFolder("keys"), InMemoryAesGcmWrapper())
        engine = ControlledEngineGateway()
        repo = HubRepository(
            database = db,
            keyManager = keyManager,
            engine = engine,
            sessionsRootDir = tempFolder.newFolder("sessions"),
            mediaRootDir = tempFolder.newFolder("media")
        )
    }

    @After
    fun tearDown() {
        repo.shutdown()
        db.close()
    }

    @Test
    fun pairingCodeDoesNotFalselyMarkAccountConnectedBeforeConfirmation() = runBlocking {
        val res = repo.requestPairingCode(1, "967771112222")
        assertTrue(res.ok)
        val acct1 = db.getAccount(1)!!
        // Must be PAIRING, never CONNECTED until real PairSuccess/Connected event arrives
        assertEquals(AccountStatus.PAIRING, acct1.status)
        assertNotEquals(AccountStatus.CONNECTED, acct1.status)
    }

    @Test
    fun fifteenStepFourAccountIsolationAndRecoveryScenario() = runBlocking {
        // Steps 1-4: Connect Accounts 1, 2, 3, 4
        for (acct in 1..4) {
            assertTrue(repo.connectAccount(acct).ok)
        }
        repo.drainAndProcessEventsNow()
        for (acct in 1..4) {
            assertEquals(AccountStatus.CONNECTED, db.getAccount(acct)?.status)
        }

        // Step 5 & 7: Receive messages on all 4 accounts from the SAME contact JID and verify attribution
        val sharedContactJid = "967779998888@s.whatsapp.net"
        for (acct in 1..4) {
            engine.eventQueue.addLast(
                EngineEventEnvelope(
                    type = "message",
                    accountId = acct,
                    chatJid = sharedContactJid,
                    messageId = "IN_MSG_$acct",
                    senderJid = sharedContactJid,
                    senderName = "Shared Customer",
                    text = "Hello to slot $acct",
                    timestampMs = 1_700_000_000_000L + acct * 1000L
                )
            )
        }
        repo.drainAndProcessEventsNow()

        for (acct in 1..4) {
            val msgs = db.getConversationMessages(acct, sharedContactJid)
            assertEquals(1, msgs.size)
            assertEquals("Hello to slot $acct", msgs[0].textBody)
        }

        // Step 6: Send messages through each account to the shared contact JID — never cross-sending!
        for (acct in 1..4) {
            val sendRes = repo.sendTextMessage(acct, sharedContactJid, "Reply from Account $acct")
            assertTrue(sendRes.ok)
        }
        assertEquals(4, engine.sentLog.size)
        for (acct in 1..4) {
            val entry = engine.sentLog[acct - 1]
            assertEquals(acct, entry.first)
            assertEquals("Reply from Account $acct", entry.third)
        }

        // Steps 8-9: Disconnect Account 2 and confirm Accounts 1, 3, 4 remain Connected & able to send
        repo.disconnectAccount(2)
        repo.drainAndProcessEventsNow()
        assertEquals(AccountStatus.DISCONNECTED, db.getAccount(2)?.status)
        assertEquals(false, repo.sendTextMessage(2, sharedContactJid, "Should fail while disconnected").ok)

        for (active in listOf(1, 3, 4)) {
            assertEquals(AccountStatus.CONNECTED, db.getAccount(active)?.status)
            assertTrue(repo.sendTextMessage(active, sharedContactJid, "Still active on $active").ok)
        }

        // Steps 12-15: Simulate network loss & restore without duplicating or mixing messages
        repo.onNetworkChanged(false)
        assertEquals(false, engine.networkAvailable)
        repo.onNetworkChanged(true)
        assertEquals(true, engine.networkAvailable)

        // Verify exact message counts per account:
        // Accounts 1, 3, 4: 1 incoming + 2 sent outgoing = 3 messages
        // Account 2: 1 incoming + 1 sent outgoing + 1 failed outgoing (preserved for manual Retry in v1.1.0) = 3 messages
        assertEquals(3, db.getConversationMessages(1, sharedContactJid).size)
        val acct2Msgs = db.getConversationMessages(2, sharedContactJid)
        assertEquals(3, acct2Msgs.size)
        assertEquals(1, acct2Msgs.count { it.status == "failed" && it.textBody == "Should fail while disconnected" })
        assertEquals(3, db.getConversationMessages(3, sharedContactJid).size)
        assertEquals(3, db.getConversationMessages(4, sharedContactJid).size)
    }
}
