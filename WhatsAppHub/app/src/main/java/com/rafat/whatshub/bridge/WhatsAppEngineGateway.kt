package com.rafat.whatshub.bridge

import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Testable interface over the Go whatsmeow multi-account engine.
 * Production uses [JniWhatsAppEngineGateway]; JVM/Robolectric tests can inject fakes.
 */
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

    /**
     * Non-destructively peeks queued events without removing them from Go memory.
     * Default implementation wraps [pollEvents] for legacy test doubles that do not override it.
     */
    fun peekEvents(maxBatch: Int = 128): PeekedEventBatch {
        val list = pollEvents(maxBatch)
        val maxSeq = list.maxOfOrNull { it.seq } ?: 0L
        return PeekedEventBatch(events = list, highestRawSeq = maxSeq)
    }

    /**
     * Acknowledges all queued Go events with seq <= [upToSeq] AFTER durable SQLite persistence.
     */
    fun ackEvents(upToSeq: Long): EngineResult = EngineResult(ok = true)

    fun pollEvents(maxBatch: Int = 128): List<EngineEventEnvelope>
    fun sendText(accountId: Int, payload: SendTextPayload): EngineResult
    fun sendMedia(accountId: Int, payload: SendMediaPayload): EngineResult
    fun sendReaction(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String, emoji: String): EngineResult
    fun revokeMessage(accountId: Int, chatJid: String, senderJid: String, targetMessageId: String): EngineResult
    fun downloadMedia(accountId: Int, payload: DownloadMediaPayload): EngineResult
    fun markRead(accountId: Int, chatJid: String, senderJid: String, messageIds: List<String>): EngineResult
    fun sendTyping(accountId: Int, chatJid: String, isTyping: Boolean, isAudio: Boolean = false): EngineResult
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
        coerceInputValues = true
        encodeDefaults = true
    }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
    private fun ByteArray.asString(): String = toString(Charsets.UTF_8)

    private fun decodeResult(raw: ByteArray): EngineResult {
        val text = raw.asString()
        if (text.isBlank()) return EngineResult(ok = false, error = "Empty JNI response")
        return runCatching { json.decodeFromString<EngineResult>(text) }
            .getOrElse { EngineResult(ok = false, error = "Invalid JNI JSON response") }
    }

    private fun unavailableResult(): EngineResult =
        EngineResult(ok = false, error = "Native WhatsApp engine unavailable (${NativeBridge.loadError})")

    /**
     * Element-isolated JSON array decoder so a single malformed envelope can NEVER poison
     * or drop the rest of a 128-event batch.
     */
    internal fun decodeEnvelopeArraySafely(rawJson: String): PeekedEventBatch {
        if (rawJson.isBlank() || rawJson == "[]") {
            return PeekedEventBatch(emptyList(), 0L, 0)
        }
        val array = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull()
        if (array == null) {
            SanitizedLogger.error("JNI", "PeekEvents returned non-array JSON payload (length=${rawJson.length})")
            return PeekedEventBatch(emptyList(), 0L, 1)
        }

        val out = ArrayList<EngineEventEnvelope>(array.size)
        var highestSeq = 0L
        var badCount = 0

        for (element in array) {
            val rawSeq = runCatching {
                element.jsonObject["seq"]?.jsonPrimitive?.longOrNull ?: 0L
            }.getOrDefault(0L)
            if (rawSeq > highestSeq) highestSeq = rawSeq

            runCatching {
                json.decodeFromJsonElement<EngineEventEnvelope>(element)
            }.onSuccess { env ->
                out.add(env)
                if (env.seq > highestSeq) highestSeq = env.seq
            }.onFailure { err ->
                badCount++
                SanitizedLogger.warn("JNI", "Isolated malformed event envelope seq=$rawSeq (${err.javaClass.simpleName})")
            }
        }
        return PeekedEventBatch(events = out, highestRawSeq = highestSeq, malformedCount = badCount)
    }

    override val isNativeAvailable: Boolean
        get() = NativeBridge.ensureLoaded()

    override fun engineVersion(): String {
        if (!NativeBridge.ensureLoaded()) return "unavailable"
        return NativeBridge.nativeVersion().asString()
    }

    override fun initEngine(sessionsDir: String, mediaDir: String, caCertPath: String): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeInit(sessionsDir.utf8(), mediaDir.utf8(), caCertPath.utf8()))
    }

    override fun setSlotKey(accountId: Int, keyHex: String): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeSetSlotKey(accountId, keyHex.utf8()))
    }

    override fun connectAccount(accountId: Int): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeConnectAccount(accountId))
    }

    override fun disconnectAccount(accountId: Int): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeDisconnectAccount(accountId))
    }

    override fun removeAccount(accountId: Int): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeRemoveAccount(accountId))
    }

    override fun requestPairingCode(accountId: Int, phone: String): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeRequestPairingCode(accountId, phone.utf8()))
    }

    override fun getAccountsSnapshot(): List<EngineSlotSnapshot> {
        if (!NativeBridge.ensureLoaded()) return emptyList()
        val text = NativeBridge.nativeGetAccountsSnapshot().asString()
        return runCatching { json.decodeFromString<List<EngineSlotSnapshot>>(text) }.getOrDefault(emptyList())
    }

    override fun peekEvents(maxBatch: Int): PeekedEventBatch {
        if (!NativeBridge.ensureLoaded()) return PeekedEventBatch(emptyList(), 0L)
        val text = NativeBridge.nativePeekEvents(maxBatch).asString()
        return decodeEnvelopeArraySafely(text)
    }

    override fun ackEvents(upToSeq: Long): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        if (upToSeq <= 0L) return EngineResult(ok = true, ackedCount = 0)
        return decodeResult(NativeBridge.nativeAckEvents(upToSeq))
    }

    override fun pollEvents(maxBatch: Int): List<EngineEventEnvelope> {
        if (!NativeBridge.ensureLoaded()) return emptyList()
        val text = NativeBridge.nativePollEvents(maxBatch).asString()
        return decodeEnvelopeArraySafely(text).events
    }

    override fun sendText(accountId: Int, payload: SendTextPayload): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        val body = json.encodeToString(payload).utf8()
        return decodeResult(NativeBridge.nativeSendText(accountId, body))
    }

    override fun sendMedia(accountId: Int, payload: SendMediaPayload): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        val body = json.encodeToString(payload).utf8()
        return decodeResult(NativeBridge.nativeSendMedia(accountId, body))
    }

    override fun sendReaction(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        emoji: String
    ): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
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
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
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
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        val body = json.encodeToString(payload).utf8()
        return decodeResult(NativeBridge.nativeDownloadMedia(accountId, body))
    }

    override fun markRead(
        accountId: Int,
        chatJid: String,
        senderJid: String,
        messageIds: List<String>
    ): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        if (messageIds.isEmpty()) return EngineResult(ok = true)
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
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeSendTyping(accountId, chatJid.utf8(), isTyping, isAudio))
    }

    override fun getContacts(accountId: Int): List<EngineContactDto> {
        if (!NativeBridge.ensureLoaded()) return emptyList()
        val text = NativeBridge.nativeGetContacts(accountId).asString()
        return runCatching { json.decodeFromString<List<EngineContactDto>>(text) }.getOrDefault(emptyList())
    }

    override fun getGroups(accountId: Int): List<EngineGroupDto> {
        if (!NativeBridge.ensureLoaded()) return emptyList()
        val text = NativeBridge.nativeGetGroups(accountId).asString()
        return runCatching { json.decodeFromString<List<EngineGroupDto>>(text) }.getOrDefault(emptyList())
    }

    override fun getGroupInfo(accountId: Int, groupJid: String): EngineGroupDto? {
        if (!NativeBridge.ensureLoaded()) return null
        val text = NativeBridge.nativeGetGroupInfo(accountId, groupJid.utf8()).asString()
        if (text.isBlank() || text == "{}") return null
        return runCatching { json.decodeFromString<EngineGroupDto>(text) }.getOrNull()
    }

    override fun onNetworkChanged(available: Boolean): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeOnNetworkChanged(available))
    }

    override fun setDebugLogging(enabled: Boolean): EngineResult {
        if (!NativeBridge.ensureLoaded()) return unavailableResult()
        return decodeResult(NativeBridge.nativeSetDebugLogging(enabled))
    }

    override fun shutdown(): EngineResult {
        if (!NativeBridge.ensureLoaded()) return EngineResult(ok = true)
        return decodeResult(NativeBridge.nativeShutdown())
    }
}
