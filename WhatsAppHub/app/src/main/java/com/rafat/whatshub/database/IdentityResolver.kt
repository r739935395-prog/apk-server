package com.rafat.whatshub.database

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Centralized WhatsApp Identity & JID Resolution utilities for Rafat Hub v1.1.0.
 *
 * Rules enforced:
 *  1. Every mapping is strictly scoped to ONE accountId (1..4); accounts NEVER share identity mappings.
 *  2. Verified @s.whatsapp.net phone JIDs are the canonical 1:1 conversation key whenever known.
 *  3. Opaque @lid identifiers are NEVER formatted with a '+' prefix or treated as phone numbers.
 *  4. Raw protocol suffixes (@s.whatsapp.net, @lid, @g.us) are never leaked into user-facing titles.
 */
object IdentityResolver {

    private val digitsOnlyRegex = Regex("^[0-9]{7,15}$")

    fun normalizeJid(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val atIdx = trimmed.indexOf('@')
        if (atIdx < 0) {
            val cleanDigits = trimmed.removePrefix("+").replace(Regex("[^0-9]"), "")
            return if (cleanDigits.isNotEmpty()) "$cleanDigits@s.whatsapp.net" else ""
        }
        val userPart = trimmed.substring(0, atIdx).substringBefore(':')
        val serverPart = trimmed.substring(atIdx + 1).lowercase(Locale.US)
        val normalizedServer = if (serverPart == "c.us") "s.whatsapp.net" else serverPart
        return if (userPart.isNotEmpty() && normalizedServer.isNotEmpty()) {
            "$userPart@$normalizedServer"
        } else {
            trimmed
        }
    }

    fun isLidJid(jid: String): Boolean = jid.trim().endsWith("@lid", ignoreCase = true)

    fun isPhoneJid(jid: String): Boolean = jid.trim().endsWith("@s.whatsapp.net", ignoreCase = true)

    fun isGroupJid(jid: String): Boolean = jid.trim().endsWith("@g.us", ignoreCase = true)

    /**
     * Extracts and formats a verified phone number ONLY when [pnOrChatJid] is a confirmed
     * @s.whatsapp.net JID (never from an @lid identifier).
     */
    fun formatVerifiedPhone(pnOrChatJid: String): String {
        val norm = normalizeJid(pnOrChatJid)
        if (!isPhoneJid(norm)) return ""
        val digits = norm.substringBefore('@')
        if (!digitsOnlyRegex.matches(digits)) return ""
        return when {
            digits.startsWith("967") && digits.length == 12 ->
                "+967 ${digits.substring(3, 6)} ${digits.substring(6, 9)} ${digits.substring(9)}"
            digits.length > 10 ->
                "+${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
            else -> "+$digits"
        }
    }

    /**
     * Resolves a clean user-facing display name without ever leaking raw @lid/@s.whatsapp.net JIDs
     * or fabricating fake phone numbers out of opaque @lid digits.
     */
    fun cleanDisplayTitle(
        explicitNames: List<String>,
        canonicalJid: String,
        verifiedPnJid: String = "",
        isGroup: Boolean = isGroupJid(canonicalJid)
    ): String {
        for (candidate in explicitNames) {
            val t = candidate.trim()
            if (t.isNotEmpty() &&
                !t.endsWith("@lid", ignoreCase = true) &&
                !t.endsWith("@s.whatsapp.net", ignoreCase = true) &&
                !t.endsWith("@g.us", ignoreCase = true) &&
                !isBareOpaqueLidNumber(t, canonicalJid, verifiedPnJid)
            ) {
                return t
            }
        }
        if (isGroup) {
            return "WhatsApp Group"
        }
        val pnCandidate = verifiedPnJid.ifBlank { if (isPhoneJid(canonicalJid)) canonicalJid else "" }
        val formattedPhone = formatVerifiedPhone(pnCandidate)
        if (formattedPhone.isNotEmpty()) {
            return formattedPhone
        }
        if (isLidJid(canonicalJid)) {
            val shortTail = canonicalJid.substringBefore('@').takeLast(4)
            return if (shortTail.isNotEmpty()) "WhatsApp Contact • $shortTail" else "WhatsApp Contact"
        }
        return canonicalJid.substringBefore('@').ifBlank { "WhatsApp Chat" }
    }

    private fun isBareOpaqueLidNumber(candidate: String, canonicalJid: String, verifiedPnJid: String): Boolean {
        if (!isLidJid(canonicalJid) || verifiedPnJid.isNotBlank()) return false
        val lidUser = canonicalJid.substringBefore('@')
        return candidate == lidUser || candidate == "+$lidUser"
    }

    /**
     * Monotonic delivery status rank so out-of-order network callbacks never downgrade
     * 'read' or 'delivered' back to 'sent' or 'pending'.
     */
    fun statusRank(status: String): Int = when (status) {
        "failed" -> -1
        "pending" -> 0
        "sent" -> 1
        "delivered" -> 2
        "read" -> 3
        else -> 0
    }
}

/**
 * Generates deterministic WhatsApp Web-compatible message IDs ("3EB0" + 18 uppercase hex chars)
 * before network transmission so the local Pending SQLite record and WhatsApp's server stanza
 * share the exact same MessageID with zero reconciliation race conditions.
 */
object MessageIdGenerator {
    private val secureRandom = SecureRandom()

    fun nextWhatsAppMessageId(ownPhoneDigits: String = ""): String {
        val randomBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val buf = ByteBuffer.allocate(8 + 32 + 16)
        buf.putLong(System.currentTimeMillis() / 1000L)
        if (ownPhoneDigits.isNotBlank()) {
            val phoneBytes = "$ownPhoneDigits@c.us".toByteArray(Charsets.UTF_8)
            buf.put(phoneBytes, 0, minOf(phoneBytes.size, 32))
        }
        buf.put(randomBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(buf.array())
        val hex9 = StringBuilder(18)
        for (i in 0 until 9) {
            hex9.append("%02X".format(digest[i]))
        }
        return "3EB0$hex9"
    }
}
