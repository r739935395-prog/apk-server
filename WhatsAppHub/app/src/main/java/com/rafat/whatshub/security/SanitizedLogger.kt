package com.rafat.whatshub.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticEntry(
    val timestampMs: Long,
    val accountId: Int,
    val level: String,
    val module: String,
    val message: String
) {
    fun formattedLine(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        val acctTag = if (accountId in 1..4) "Acct#$accountId" else "Hub"
        return "$ts [$level] ($acctTag/$module) $message"
    }
}

/**
 * Structured ring-buffered diagnostic logger that scrubs authentication keys,
 * long tokens, QR strings, pairing secrets, and full phone JIDs.
 */
object SanitizedLogger {
    private const val MAX_ENTRIES = 300
    private val longTokenRegex = Regex("[A-Za-z0-9+/=_-]{28,}")
    private val phoneJidRegex = Regex("(\\d{3})\\d{4,}(\\d{2})@(s\\.whatsapp\\.net|lid)")
    private val pairingCodeRegex = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
    private val blockedKeywords = listOf(
        "noise_key", "identity_key", "signed_pre_key", "adv_key",
        "media_key", "pairing_code", "qr_code", "passphrase", "sqlcipher"
    )

    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    @Volatile
    var debugEnabled: Boolean = false

    fun sanitize(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT)
        if (blockedKeywords.any { lower.contains(it) }) {
            return "[REDACTED SENSITIVE DIAGNOSTIC]"
        }
        return raw
            .replace(pairingCodeRegex, "[REDACTED_CODE]")
            .replace(phoneJidRegex, "$1****$2@$3")
            .replace(longTokenRegex, "[REDACTED_TOKEN]")
            .take(360)
    }

    fun record(
        level: String,
        module: String,
        message: String,
        accountId: Int = 0,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (level == "DEBUG" && !debugEnabled) return
        val entry = DiagnosticEntry(
            timestampMs = timestampMs,
            accountId = accountId,
            level = level,
            module = module,
            message = sanitize(message)
        )
        synchronized(this) {
            val next = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = next
        }
    }

    fun info(module: String, message: String, accountId: Int = 0) =
        record("INFO", module, message, accountId)

    fun warn(module: String, message: String, accountId: Int = 0) =
        record("WARN", module, message, accountId)

    fun error(module: String, message: String, accountId: Int = 0) =
        record("ERROR", module, message, accountId)

    fun clear() {
        synchronized(this) {
            _entries.value = emptyList()
        }
    }
}
