package com.rafat.whatshub.database

/**
 * Strongly-typed models representing the 10 SQLite tables in WhatsApp Hub.
 * Every account-owned record carries accountId in 1..4 and enforces composite identity.
 */

enum class AccountStatus(val wireValue: String) {
    NOT_CONNECTED("not_connected"),
    CONNECTING("connecting"),
    WAITING_FOR_QR("waiting_for_qr"),
    PAIRING("pairing"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    DISCONNECTED("disconnected"),
    AUTH_EXPIRED("auth_expired"),
    CONNECTION_ERROR("connection_error");

    companion object {
        fun fromWire(raw: String?): AccountStatus =
            entries.firstOrNull { it.wireValue == raw } ?: NOT_CONNECTED
    }
}

data class AccountRecord(
    val accountId: Int,
    val label: String,
    val colorHex: Long,
    val jid: String,
    val phone: String,
    val pushName: String,
    val status: AccountStatus,
    val errorMessage: String,
    val reconnectAttempt: Int,
    val hasSavedSession: Boolean,
    val updatedAtMs: Long
)

data class SessionMetadataRecord(
    val accountId: Int,
    val deviceJid: String,
    val keyAlias: String,
    val pairedAtMs: Long,
    val lastConnectedAtMs: Long,
    val sessionDir: String
)

data class ContactRecord(
    val accountId: Int,
    val contactJid: String,
    val fullName: String,
    val firstName: String,
    val pushName: String,
    val businessName: String,
    val isOnline: Boolean,
    val lastSeenMs: Long
) {
    val displayName: String
        get() = listOf(fullName, pushName, businessName, firstName)
            .firstOrNull { it.isNotBlank() }
            ?: contactJid.substringBefore('@')
}

data class ChatSummaryRecord(
    val accountId: Int,
    val chatJid: String,
    val compositeKey: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessageId: String,
    val lastMessagePreview: String,
    val lastMessageTsMs: Long,
    val lastSenderName: String,
    val unreadCount: Int,
    val isTyping: Boolean,
    val typingSender: String
)

data class MessageMediaRecord(
    val accountId: Int,
    val chatJid: String,
    val messageId: String,
    val mediaType: String,
    val localPath: String,
    val mimeType: String,
    val fileName: String,
    val fileSize: Long,
    val durationSec: Int,
    val isVoiceNote: Boolean,
    val directPath: String,
    val mediaKeyB64: String,
    val fileSha256B64: String,
    val fileEncSha256B64: String,
    val downloadState: String, // "none", "downloading", "ready", "failed"
    val errorMessage: String
)

data class MessageRecord(
    val accountId: Int,
    val chatJid: String,
    val messageId: String,
    val compositeKey: String,
    val senderJid: String,
    val senderName: String,
    val isFromMe: Boolean,
    val isGroup: Boolean,
    val textBody: String,
    val mediaType: String,
    val quotedMessageId: String,
    val quotedSenderJid: String,
    val quotedText: String,
    val reactionsSummary: String,
    val isDeleted: Boolean,
    val isEdited: Boolean,
    val status: String, // "pending", "sent", "delivered", "read", "failed"
    val timestampMs: Long,
    val media: MessageMediaRecord? = null
)

data class GroupMemberRecord(
    val accountId: Int,
    val groupJid: String,
    val memberJid: String,
    val phoneJid: String,
    val isAdmin: Boolean,
    val isSuperAdmin: Boolean
)

data class GroupRecord(
    val accountId: Int,
    val groupJid: String,
    val name: String,
    val topic: String,
    val ownerJid: String,
    val participantCount: Int,
    val isAnnounce: Boolean,
    val isLocked: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val members: List<GroupMemberRecord> = emptyList()
)

data class SyncStateRecord(
    val accountId: Int,
    val syncType: String,
    val lastChunkIndex: Int,
    val progressPct: Int,
    val importedChats: Int,
    val importedMessages: Int,
    val status: String,
    val updatedAtMs: Long
)

object AccountPalette {
    // Mandatory consistent visual identity for each of the 4 accounts:
    // Account 1: Green, Account 2: Blue, Account 3: Purple, Account 4: Orange
    const val ACCOUNT_1_GREEN = 0xFF00C853L
    const val ACCOUNT_2_BLUE = 0xFF2979FFL
    const val ACCOUNT_3_PURPLE = 0xFFAA00FFL
    const val ACCOUNT_4_ORANGE = 0xFFFF6D00L

    fun colorHexFor(accountId: Int): Long = when (accountId) {
        1 -> ACCOUNT_1_GREEN
        2 -> ACCOUNT_2_BLUE
        3 -> ACCOUNT_3_PURPLE
        4 -> ACCOUNT_4_ORANGE
        else -> ACCOUNT_1_GREEN
    }

    fun defaultLabelFor(accountId: Int): String = "Account $accountId"
}
