package com.rafat.whatshub.database

import kotlinx.serialization.Serializable

/**
 * Strongly-typed models representing the encrypted SQLite domain tables in Rafat Hub v1.1.0.
 * Every account-owned record carries accountId in 1..4 and enforces strict composite isolation.
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

@Serializable
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

@Serializable
data class SessionMetadataRecord(
    val accountId: Int,
    val deviceJid: String,
    val keyAlias: String,
    val pairedAtMs: Long,
    val lastConnectedAtMs: Long,
    val sessionDir: String
)

@Serializable
data class IdentityMappingRecord(
    val accountId: Int,
    val lidJid: String,
    val pnJid: String,
    val mappingSource: String = "verified",
    val updatedAtMs: Long = 0L
)

@Serializable
data class ContactRecord(
    val accountId: Int,
    val contactJid: String,
    val fullName: String,
    val firstName: String,
    val pushName: String,
    val businessName: String,
    val isOnline: Boolean,
    val lastSeenMs: Long,
    val lidJid: String = "",
    val pnJid: String = ""
) {
    val displayName: String
        get() = IdentityResolver.cleanDisplayTitle(
            explicitNames = listOf(fullName, pushName, businessName, firstName),
            canonicalJid = contactJid,
            verifiedPnJid = pnJid
        )

    val formattedPhone: String
        get() = IdentityResolver.formatVerifiedPhone(
            pnJid.ifBlank { if (IdentityResolver.isPhoneJid(contactJid)) contactJid else "" }
        )

    val isUnmappedLidOnly: Boolean
        get() = IdentityResolver.isLidJid(contactJid) && pnJid.isBlank()
}

@Serializable
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
    val typingSender: String,
    val isPinned: Boolean = false,
    val pinnedAtMs: Long = 0L,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val markedUnread: Boolean = false,
    val lidJid: String = "",
    val pnJid: String = ""
) {
    val effectiveUnreadCount: Int
        get() = if (unreadCount > 0) unreadCount else if (markedUnread) 1 else 0

    val cleanTitle: String
        get() = IdentityResolver.cleanDisplayTitle(
            explicitNames = listOf(title),
            canonicalJid = chatJid,
            verifiedPnJid = pnJid,
            isGroup = isGroup
        )
}

@Serializable
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

@Serializable
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
    val media: MessageMediaRecord? = null,
    val rawChatJid: String = "",
    val clientMessageId: String = "",
    val transmissionAck: Boolean = false,
    val errorReason: String = ""
) {
    val contentKind: String
        get() = when {
            isDeleted -> "deleted"
            mediaType.isNotBlank() -> "media_$mediaType"
            quotedMessageId.isNotBlank() -> "quoted_text"
            else -> "text"
        }
}

data class GlobalMessageSearchHit(
    val accountId: Int,
    val chatJid: String,
    val chatTitle: String,
    val messageId: String,
    val senderName: String,
    val textSnippet: String,
    val timestampMs: Long,
    val isFromMe: Boolean
)

@Serializable
data class GroupMemberRecord(
    val accountId: Int,
    val groupJid: String,
    val memberJid: String,
    val phoneJid: String,
    val isAdmin: Boolean,
    val isSuperAdmin: Boolean
)

@Serializable
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

@Serializable
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

@Serializable
data class PortableBackupSnapshot(
    val formatVersion: Int = 2,
    val appVersion: String = "1.1.0",
    val exportedAtMs: Long,
    val accounts: List<AccountRecord> = emptyList(),
    val identityMappings: List<IdentityMappingRecord> = emptyList(),
    val contacts: List<ContactRecord> = emptyList(),
    val groups: List<GroupRecord> = emptyList(),
    val chats: List<ChatSummaryRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val settings: Map<String, String> = emptyMap()
)

data class AppPreferencesState(
    val themeMode: String = "amoled",
    val languageCode: String = "en",
    val fontScaleKey: String = "default", // "small", "default", "large", "xlarge"
    val notificationsEnabled: Boolean = true,
    val notificationShowPreview: Boolean = true,
    val accountNotifications: Map<Int, Boolean> = mapOf(1 to true, 2 to true, 3 to true, 4 to true),
    val debugLogsEnabled: Boolean = false
) {
    val fontScaleMultiplier: Float
        get() = when (fontScaleKey) {
            "small" -> 0.90f
            "large" -> 1.12f
            "xlarge" -> 1.24f
            else -> 1.00f
        }
}

object AccountPalette {
    // Distinct per-account color identity (Green, Blue, Purple, Gold-Amber) harmonized with Rafat Hub dark UI
    const val ACCOUNT_1_GREEN = 0xFF10B981L
    const val ACCOUNT_2_BLUE = 0xFF3B82F6L
    const val ACCOUNT_3_PURPLE = 0xFFA855F7L
    const val ACCOUNT_4_ORANGE = 0xFFF59E0BL

    fun colorHexFor(accountId: Int): Long = when (accountId) {
        1 -> ACCOUNT_1_GREEN
        2 -> ACCOUNT_2_BLUE
        3 -> ACCOUNT_3_PURPLE
        4 -> ACCOUNT_4_ORANGE
        else -> ACCOUNT_1_GREEN
    }

    fun defaultLabelFor(accountId: Int): String = "Account $accountId"
}
