package com.rafat.whatshub.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EngineResult(
    val ok: Boolean = false,
    val error: String = "",
    @SerialName("message_id") val messageId: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long = 0L,
    @SerialName("pairing_code") val pairingCode: String = "",
    @SerialName("media_path") val mediaPath: String = "",
    @SerialName("acked_count") val ackedCount: Int = 0
)

@Serializable
data class EngineSlotSnapshot(
    @SerialName("account_id") val accountId: Int,
    val state: String = "not_connected",
    val jid: String = "",
    val phone: String = "",
    @SerialName("push_name") val pushName: String = "",
    @SerialName("has_saved_session") val hasSavedSession: Boolean = false,
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("reconnect_attempt") val reconnectAttempt: Int = 0
)

@Serializable
data class EngineContactDto(
    val jid: String = "",
    @SerialName("lid_jid") val lidJid: String = "",
    @SerialName("pn_jid") val pnJid: String = "",
    @SerialName("full_name") val fullName: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("push_name") val pushName: String = "",
    @SerialName("business_name") val businessName: String = ""
)

@Serializable
data class EngineGroupMemberDto(
    val jid: String = "",
    @SerialName("phone_jid") val phoneJid: String = "",
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_super_admin") val isSuperAdmin: Boolean = false
)

@Serializable
data class EngineGroupDto(
    val jid: String = "",
    val name: String = "",
    val topic: String = "",
    @SerialName("owner_jid") val ownerJid: String = "",
    @SerialName("participant_count") val participantCount: Int = 0,
    @SerialName("is_announce") val isAnnounce: Boolean = false,
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("created_at_ms") val createdAtMs: Long = 0L,
    val participants: List<EngineGroupMemberDto> = emptyList()
)

@Serializable
data class EngineEventEnvelope(
    val seq: Long = 0L,
    val type: String = "",
    @SerialName("account_id") val accountId: Int = 0,
    @SerialName("timestamp_ms") val timestampMs: Long = 0L,

    val state: String = "",
    val jid: String = "",
    val phone: String = "",
    @SerialName("push_name") val pushName: String = "",
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("reconnect_attempt") val reconnectAttempt: Int = 0,

    @SerialName("qr_code") val qrCode: String = "",
    @SerialName("expires_in_sec") val expiresInSec: Int = 20,
    @SerialName("pairing_code") val pairingCode: String = "",

    @SerialName("raw_chat_jid") val rawChatJid: String = "",
    @SerialName("lid_jid") val lidJid: String = "",
    @SerialName("pn_jid") val pnJid: String = "",
    @SerialName("sender_lid_jid") val senderLidJid: String = "",
    @SerialName("sender_pn_jid") val senderPnJid: String = "",
    @SerialName("mapping_source") val mappingSource: String = "",

    @SerialName("chat_jid") val chatJid: String = "",
    @SerialName("chat_name") val chatName: String = "",
    @SerialName("message_id") val messageId: String = "",
    @SerialName("client_msg_id") val clientMessageId: String = "",
    @SerialName("composite_chat_key") val compositeChatKey: String = "",
    @SerialName("composite_msg_key") val compositeMsgKey: String = "",
    @SerialName("sender_jid") val senderJid: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("is_from_me") val isFromMe: Boolean = false,
    @SerialName("is_group") val isGroup: Boolean = false,
    val text: String = "",
    @SerialName("media_type") val mediaType: String = "",
    @SerialName("media_path") val mediaPath: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: Long = 0L,
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("is_voice_note") val isVoiceNote: Boolean = false,
    @SerialName("direct_path") val directPath: String = "",
    @SerialName("media_key_b64") val mediaKeyB64: String = "",
    @SerialName("file_sha256_b64") val fileSha256B64: String = "",
    @SerialName("file_enc_sha256_b64") val fileEncSha256B64: String = "",
    @SerialName("quoted_message_id") val quotedMessageId: String = "",
    @SerialName("quoted_sender_jid") val quotedSenderJid: String = "",
    @SerialName("quoted_text") val quotedText: String = "",
    @SerialName("edit_target_id") val editTargetId: String = "",
    @SerialName("is_history_sync") val isHistorySync: Boolean = false,
    @SerialName("unread_count") val unreadCount: Int = 0,
    val status: String = "",

    @SerialName("target_message_id") val targetMessageId: String = "",
    val emoji: String = "",
    @SerialName("message_ids") val messageIds: List<String> = emptyList(),
    @SerialName("receipt_status") val receiptStatus: String = "",
    @SerialName("is_typing") val isTyping: Boolean = false,
    @SerialName("media_kind") val mediaKind: String = "",
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen_ms") val lastSeenMs: Long = 0L,

    @SerialName("sync_type") val syncType: String = "",
    @SerialName("chunk_index") val chunkIndex: Int = 0,
    @SerialName("progress_pct") val progressPct: Int = 0,
    @SerialName("imported_chats") val importedChats: Int = 0,
    @SerialName("imported_messages") val importedMessages: Int = 0,

    val contacts: List<EngineContactDto> = emptyList(),
    val groups: List<EngineGroupDto> = emptyList(),

    @SerialName("log_level") val logLevel: String = "",
    @SerialName("log_module") val logModule: String = "",
    @SerialName("log_message") val logMessage: String = ""
)

data class PeekedEventBatch(
    val events: List<EngineEventEnvelope>,
    val highestRawSeq: Long,
    val malformedCount: Int = 0
)

@Serializable
data class SendTextPayload(
    @SerialName("chat_jid") val chatJid: String,
    @SerialName("client_msg_id") val clientMessageId: String = "",
    val text: String,
    @SerialName("quoted_message_id") val quotedMessageId: String = "",
    @SerialName("quoted_sender_jid") val quotedSenderJid: String = "",
    @SerialName("quoted_text") val quotedText: String = ""
)

@Serializable
data class SendMediaPayload(
    @SerialName("chat_jid") val chatJid: String,
    @SerialName("client_msg_id") val clientMessageId: String = "",
    @SerialName("media_type") val mediaType: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("mime_type") val mimeType: String,
    val caption: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("quoted_message_id") val quotedMessageId: String = "",
    @SerialName("quoted_sender_jid") val quotedSenderJid: String = "",
    @SerialName("quoted_text") val quotedText: String = ""
)

@Serializable
data class DownloadMediaPayload(
    @SerialName("message_id") val messageId: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("direct_path") val directPath: String,
    @SerialName("media_key_b64") val mediaKeyB64: String,
    @SerialName("file_sha256_b64") val fileSha256B64: String,
    @SerialName("file_enc_sha256_b64") val fileEncSha256B64: String
)
