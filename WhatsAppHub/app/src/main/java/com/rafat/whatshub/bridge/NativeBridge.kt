package com.rafat.whatshub.bridge

import com.rafat.whatshub.security.SanitizedLogger

/**
 * Direct JNI bindings to Go's libwhatshub_engine.so (compiled with -buildmode=c-shared for arm64-v8a).
 * All strings cross JNI as UTF-8 ByteArrays to avoid Modified-UTF-8 surrogate issues on emojis & Arabic text.
 */
object NativeBridge {
    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String = ""
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (isLoaded) return true
        return try {
            System.loadLibrary("whatshub_engine")
            isLoaded = true
            loadError = ""
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            SanitizedLogger.error("JNI", "Failed to load libwhatshub_engine.so: $loadError")
            false
        }
    }

    @JvmStatic
    external fun nativeVersion(): ByteArray

    @JvmStatic
    external fun nativeInit(sessionsDir: ByteArray, mediaDir: ByteArray, caCertPath: ByteArray): ByteArray

    @JvmStatic
    external fun nativeSetSlotKey(accountId: Int, keyHex: ByteArray): ByteArray

    @JvmStatic
    external fun nativeConnectAccount(accountId: Int): ByteArray

    @JvmStatic
    external fun nativeDisconnectAccount(accountId: Int): ByteArray

    @JvmStatic
    external fun nativeRemoveAccount(accountId: Int): ByteArray

    @JvmStatic
    external fun nativeRequestPairingCode(accountId: Int, phone: ByteArray): ByteArray

    @JvmStatic
    external fun nativeGetAccountsSnapshot(): ByteArray

    @JvmStatic
    external fun nativePollEvents(maxBatch: Int): ByteArray

    @JvmStatic
    external fun nativeSendText(accountId: Int, payloadJson: ByteArray): ByteArray

    @JvmStatic
    external fun nativeSendMedia(accountId: Int, payloadJson: ByteArray): ByteArray

    @JvmStatic
    external fun nativeSendReaction(
        accountId: Int,
        chatJid: ByteArray,
        senderJid: ByteArray,
        targetMessageId: ByteArray,
        emoji: ByteArray
    ): ByteArray

    @JvmStatic
    external fun nativeRevokeMessage(
        accountId: Int,
        chatJid: ByteArray,
        senderJid: ByteArray,
        targetMessageId: ByteArray
    ): ByteArray

    @JvmStatic
    external fun nativeDownloadMedia(accountId: Int, payloadJson: ByteArray): ByteArray

    @JvmStatic
    external fun nativeMarkRead(
        accountId: Int,
        chatJid: ByteArray,
        senderJid: ByteArray,
        msgIdsCsv: ByteArray
    ): ByteArray

    @JvmStatic
    external fun nativeSendTyping(
        accountId: Int,
        chatJid: ByteArray,
        isTyping: Boolean,
        isAudio: Boolean
    ): ByteArray

    @JvmStatic
    external fun nativeGetContacts(accountId: Int): ByteArray

    @JvmStatic
    external fun nativeGetGroups(accountId: Int): ByteArray

    @JvmStatic
    external fun nativeGetGroupInfo(accountId: Int, groupJid: ByteArray): ByteArray

    @JvmStatic
    external fun nativeOnNetworkChanged(available: Boolean): ByteArray

    @JvmStatic
    external fun nativeSetDebugLogging(enabled: Boolean): ByteArray

    @JvmStatic
    external fun nativeShutdown(): ByteArray
}
