package com.rafat.whatshub.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface WhatsAppEngineGateway {
    val isNativeAvailable: Boolean
    fun engineVersion(): String
    fun initEngine(sessionsDir: String, mediaDir: String, caCertPath: String): EngineResult
    fun setSlotKey(accountId: Int, keyHex: String): EngineResult
    fun connectAccount(accountId: Int): EngineResult
    fun disconnectAccount(accountId: Int): EngineResult
    fun removeAccount(accountId: Int): EngineResult
    fun requestPairingCode(accountId: Int, phone: String): EngineResult
    fun getAccountsSnapshot(): List<EngineSlotSnapshot>
    fun pollEvents(maxBatch: Int = 128): List<EngineEventEnvelope>
    fun sendText(accountId: Int, payload: SendTextPayload): EngineResult
    fun sendMedia(accountId: Int, payload: SendMediaPayload): EngineResult
    fun sendReaction(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String, emoji: String): EngineResult
    fun revokeMessage(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String): EngineResult
    fun downloadMedia(accountId: Int, payload: DownloadMediaPayload): EngineResult
    fun markRead(accountId: Int, chatJid: String, senderJid: String, messageIds: List<String>): EngineResult
    fun sendTyping(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean): EngineResult
    fun getContacts(accountId: Int): List<EngineContactDto>
    fun getGroups(accountId: Int): List<EngineGroupDto>
    fun getGroupInfo(accountId: Int, groupJid: String): EngineGroupDto?
    fun onNetworkChanged(available: Boolean): EngineResult
    fun setDebugLogging(enabled: Boolean): EngineResult
    fun shutdown(): EngineResult
}

class JniWhatsAppEngineGateway : WhatsAppEngineGateway {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    override val isNativeAvailable: Boolean
        get() = NativeBridge.ensureLoaded()

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
    private fun ByteArray.asUtf8(): String = String(this, Charsets.UTF_8)

    private fun unavailableResult(): EngineResult =
        EngineResult(
            ok = false,
            error = "Native Go engine unavailable (${NativeBridge.loadError.ifBlank { "libwhatshub_engine.so not loaded" }})"
        )

    private fun decodeResult(bytes: ByteArray): EngineResult {
        val raw = bytes.asUtf8()
        if (raw.isBlank()) return EngineResult(ok = false, error = "Empty response from native engine")
        return runCatching { json.decodeFromString<EngineResult>(raw) }
            .getOrElse { EngineResult(ok = false, error = "Malformed engine response") }
    }

    override fun engineVersion(): String {
        if (!isNativeAvailable) return "Native library not loaded (${NativeBridge.loadError})"
        return NativeBridge.nativeVersion().asUtf8()
    }

    override fun initEngine(sessionsDir: String, mediaDir: String, caCertPath: String): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeInit(sessionsDir.utf8(), mediaDir.utf8(), caCertPath.utf8()))
    }

    override fun setSlotKey(accountId: Int, keyHex: String): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeSetSlotKey(accountId, keyHex.utf8()))
    }

    override fun connectAccount(accountId: Int): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeConnectAccount(accountId))
    }

    override fun disconnectAccount(accountId: Int): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeDisconnectAccount(accountId))
    }

    override fun removeAccount(accountId: Int): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeRemoveAccount(accountId))
    }

    override fun requestPairingCode(accountId: Int, phone: String): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeRequestPairingCode(accountId, phone.utf8()))
    }

    override fun getAccountsSnapshot(): List<EngineSlotSnapshot> {
        if (!isNativeAvailable) return emptyList()
        val raw = NativeBridge.nativeGetAccountsSnapshot().asUtf8()
        return runCatching { json.decodeFromString<List<EngineSlotSnapshot>>(raw) }.getOrDefault(emptyList())
    }

    override fun pollEvents(maxBatch: Int): List<EngineEventEnvelope> {
        if (!isNativeAvailable) return emptyList()
        val raw = NativeBridge.nativePollEvents(maxBatch).asUtf8()
        if (raw == "[]" || raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<EngineEventEnvelope>>(raw) }.getOrDefault(emptyList())
    }

    override fun sendText(accountId: Int, payload: SendTextPayload): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        val encoded = json.encodeToString(payload).utf8()
        return decodeResult(NativeBridge.nativeSendText(accountId, encoded))
    }

    override fun sendMedia(accountId: Int, payload: SendMediaPayload): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        val encoded = json.encodeToString(payload).utf8()
        return decodeResult(NativeBridge.nativeSendMedia(accountId, encoded))
    }

    override fun sendReaction(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        emoji: String
    ): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(
            NativeBridge.nativeSendReaction(
                accountId,
                chatJid.utf8(),
                senderJid.utf8(),
                targetMessageId.utf8(),
                emoji.utf8()
            )
        )
    }

    override fun revokeMessage(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        targetMessageId: String
    ): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(
            NativeBridge.nativeRevokeMessage(
                accountId,
                chatJid.utf8(),
                senderJid.utf8(),
                targetMessageId.utf8()
            )
        )
    }

    override fun downloadMedia(accountId: Int, payload: DownloadMediaPayload): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        val encoded = json.encodeToString(payload).utf8()
        return decodeResult(NativeBridge.nativeDownloadMedia(accountId, encoded))
    }

    override fun markRead(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        messageIds: List<String>
    ): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(
            NativeBridge.nativeMarkRead(
                accountId,
                chatJid.utf8(),
                senderJid.utf8(),
                messageIds.joinToString(",").utf8()
            )
        )
    }

    override fun sendTyping(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeSendTyping(accountId, chatJid.utf8(), isTyping, isAudio))
    }

    override fun getContacts(accountId: Int): List<EngineContactDto> {
        if (!isNativeAvailable) return emptyList()
        val raw = NativeBridge.nativeGetContacts(accountId).asUtf8()
        return runCatching { json.decodeFromString<List<EngineContactDto>>(raw) }.getOrDefault(emptyList())
    }

    override fun getGroups(accountId: Int): List<EngineGroupDto> {
        if (!isNativeAvailable) return emptyList()
        val raw = NativeBridge.nativeGetGroups(accountId).asUtf8()
        return runCatching { json.decodeFromString<List<EngineGroupDto>>(raw) }.getOrDefault(emptyList())
    }

    override fun getGroupInfo(accountId: Int, groupJid: String): EngineGroupDto? {
        if (!isNativeAvailable) return null
        val raw = NativeBridge.nativeGetGroupInfo(accountId, groupJid.utf8()).asUtf8()
        if (raw == "{}" || raw.isBlank()) return null
        return runCatching { json.decodeFromString<EngineGroupDto>(raw) }.getOrNull()
    }

    override fun onNetworkChanged(available: Boolean): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeOnNetworkChanged(available))
    }

    override fun setDebugLogging(enabled: Boolean): EngineResult {
        if (!isNativeAvailable) return unavailableResult()
        return decodeResult(NativeBridge.nativeSetDebugLogging(enabled))
    }

    override fun shutdown(): EngineResult {
        if (!isNativeAvailable) return EngineResult(ok = true)
        return decodeResult(NativeBridge.nativeShutdown())
    }
}
