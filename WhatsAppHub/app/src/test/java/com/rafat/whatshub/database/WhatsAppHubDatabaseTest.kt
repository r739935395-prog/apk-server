package com.rafat.whatshub.database

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhatsAppHubDatabaseTest {

    private lateinit var db: WhatsAppHubDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = WhatsAppHubDatabase(
            context = context,
            dbName = null, // In-memory SQLite database with full FK & UNIQUE enforcement
            factory = FrameworkSQLiteOpenHelperFactory()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun seedsFourDistinctColorCodedAccounts() {
        val accounts = db.getAccounts()
        assertEquals(4, accounts.size)
        assertEquals(AccountPalette.ACCOUNT_1_GREEN, accounts[0].colorHex)
        assertEquals(AccountPalette.ACCOUNT_2_BLUE, accounts[1].colorHex)
        assertEquals(AccountPalette.ACCOUNT_3_PURPLE, accounts[2].colorHex)
        assertEquals(AccountPalette.ACCOUNT_4_ORANGE, accounts[3].colorHex)
    }

    @Test
    fun isolatesIdenticalChatAndMessageIdsAcrossAllFourAccountsAndDeduplicates() {
        val sharedChat = "967771234567@s.whatsapp.net"
        val sharedMsgId = "BAE5SHARED001"

        for (acct in 1..4) {
            val inserted = db.upsertMessageAndUpdateChat(
                record = MessageRecord(
                    accountId = acct,
                    chatJid = sharedChat,
                    messageId = sharedMsgId,
                    compositeKey = "$acct:$sharedChat:$sharedMsgId",
                    senderJid = sharedChat,
                    senderName = "Same Contact",
                    isFromMe = false,
                    isGroup = false,
                    textBody = "Message for Account $acct",
                    mediaType = "",
                    quotedMessageId = "",
                    quotedSenderJid = "",
                    quotedText = "",
                    reactionsSummary = "",
                    isDeleted = false,
                    isEdited = false,
                    status = "delivered",
                    timestampMs = 1_700_000_000_000L + (acct * 1000L)
                ),
                incrementUnread = true
            )
            assertTrue(inserted)
        }

        // Duplicate delivery on Account 1 must return false and not double unread_count
        val dupInserted = db.upsertMessageAndUpdateChat(
            record = MessageRecord(
                accountId = 1,
                chatJid = sharedChat,
                messageId = sharedMsgId,
                compositeKey = "1:$sharedChat:$sharedMsgId",
                senderJid = sharedChat,
                senderName = "Same Contact",
                isFromMe = false,
                isGroup = false,
                textBody = "Message for Account 1",
                mediaType = "",
                quotedMessageId = "",
                quotedSenderJid = "",
                quotedText = "",
                reactionsSummary = "",
                isDeleted = false,
                isEdited = false,
                status = "delivered",
                timestampMs = 1_700_000_001_000L
            ),
            incrementUnread = true
        )
        assertFalse(dupInserted)

        // Unified inbox must contain 4 distinct conversations sorted newest first (Account 4 -> 3 -> 2 -> 1)
        val inbox = db.getUnifiedInboxChats()
        assertEquals(4, inbox.size)
        assertEquals(listOf(4, 3, 2, 1), inbox.map { it.accountId })
        assertEquals(1, inbox.first { it.accountId == 1 }.unreadCount)

        // Filtering by Account 3 returns strictly Account 3's conversation and message
        val acct3Msgs = db.getConversationMessages(3, sharedChat)
        assertEquals(1, acct3Msgs.size)
        assertEquals("Message for Account 3", acct3Msgs[0].textBody)

        // Wiping Account 2 leaves Accounts 1, 3, and 4 intact
        db.wipeSingleAccountData(2)
        val remainingInbox = db.getUnifiedInboxChats()
        assertEquals(listOf(4, 3, 1), remainingInbox.map { it.accountId })
    }
}
