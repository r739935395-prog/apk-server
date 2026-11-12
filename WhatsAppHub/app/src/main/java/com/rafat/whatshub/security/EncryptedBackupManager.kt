package com.rafat.whatshub.security

import com.rafat.whatshub.database.PortableBackupSnapshot
import com.rafat.whatshub.database.WhatsAppHubDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class PortableRestoreOutcome {
    data class RestoredV2(
        val importedChats: Int,
        val importedMessages: Int,
        val importedContacts: Int
    ) : PortableRestoreOutcome()

    data class LegacyV1KeystoreBound(
        val verifiedEntries: List<String>
    ) : PortableRestoreOutcome()
}

/**
 * Provides explicit user-initiated encrypted local backup & cross-install migration for Rafat Hub
 * using PBKDF2-HMAC-SHA256 (210,000 iterations) + AES-256-GCM.
 *
 * Security rule: Raw unencrypted WhatsApp Signal/Noise authentication keys are NEVER exported.
 *
 * Format versions:
 *  - WHUBBK02 (v1.1.0+): Portable encrypted JSON snapshot (accounts labels, LID/PN mappings,
 *    contacts, groups, chats, messages, settings) independent of device hardware Keystore so
 *    backups can be restored across clean installs or new devices.
 *  - WHUBBK01 (v1.0.0 legacy): Encrypted file-archive container (detected & handled safely).
 */
object EncryptedBackupManager {
    private val MAGIC_V1 = "WHUBBK01".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "WHUBBK02".toByteArray(Charsets.US_ASCII)
    private const val PORTABLE_JSON_ENTRY = "portable_snapshot_v2.json"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val MIN_PASSPHRASE_LENGTH = 8

    private val backupJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * Creates a portable v1.1.0+ (WHUBBK02) encrypted backup archive that can be restored
     * onto any Rafat Hub installation using ONLY the user's passphrase.
     */
    fun createPortableEncryptedSnapshot(snapshot: PortableBackupSnapshot, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val jsonBytes = backupJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry(PORTABLE_JSON_ENTRY))
                zos.write(jsonBytes)
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        return sealZipBytes(MAGIC_V2, zipBytes, passphrase)
    }

    /**
     * Decrypts, authenticates, and non-destructively restores a WHUBBK02 portable backup into [database],
     * or safely identifies a legacy WHUBBK01 archive without modifying existing data.
     */
    fun restorePortableOrLegacyBackup(
        encryptedArchive: ByteArray,
        passphrase: String,
        database: WhatsAppHubDatabase
    ): PortableRestoreOutcome {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val minSize = MAGIC_V2.size + SALT_BYTES + IV_BYTES + 16
        require(encryptedArchive.size > minSize) { "Invalid or truncated encrypted backup archive" }

        val header = encryptedArchive.copyOfRange(0, 8)
        return when {
            header.contentEquals(MAGIC_V2) -> {
                val zipPlain = openZipBytes(MAGIC_V2, encryptedArchive, passphrase)
                var snapshotJson: String? = null
                ZipInputStream(ByteArrayInputStream(zipPlain)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == PORTABLE_JSON_ENTRY) {
                            snapshotJson = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                val rawJson = snapshotJson ?: error("Portable snapshot payload missing in WHUBBK02 archive")
                val snapshot = backupJson.decodeFromString<PortableBackupSnapshot>(rawJson)
                val summary = database.importPortableSnapshotNonDestructive(snapshot)
                PortableRestoreOutcome.RestoredV2(
                    importedChats = summary.importedChats,
                    importedMessages = summary.importedMessages,
                    importedContacts = summary.importedContacts
                )
            }
            header.contentEquals(MAGIC_V1) -> {
                val zipPlain = openZipBytes(MAGIC_V1, encryptedArchive, passphrase)
                val entries = mutableListOf<String>()
                ZipInputStream(ByteArrayInputStream(zipPlain)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) entries.add(entry.name)
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                PortableRestoreOutcome.LegacyV1KeystoreBound(entries)
            }
            else -> throw IllegalArgumentException("Unrecognized backup header (expected WHUBBK02 or WHUBBK01 archive)")
        }
    }

    // --- Retained file-map encryption API (WHUBBK01) ---

    fun createEncryptedBackup(sourceFiles: Map<String, File>, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                for ((entryName, file) in sourceFiles) {
                    require(!entryName.contains("..")) { "Unsafe backup entry name" }
                    if (!file.exists() || !file.isFile) continue
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        }
        return sealZipBytes(MAGIC_V1, zipBytes, passphrase)
    }

    fun restoreEncryptedBackup(encryptedArchive: ByteArray, passphrase: String, targetRoot: File): List<String> {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val minSize = MAGIC_V1.size + SALT_BYTES + IV_BYTES + 16
        require(encryptedArchive.size > minSize) { "Invalid or truncated encrypted backup archive" }

        val header = encryptedArchive.copyOfRange(0, MAGIC_V1.size)
        require(header.contentEquals(MAGIC_V1)) { "Unrecognized backup header (expected encrypted WHUBBK01 archive)" }

        val zipPlain = openZipBytes(MAGIC_V1, encryptedArchive, passphrase)

        if (!targetRoot.exists()) targetRoot.mkdirs()
        val canonicalRoot = targetRoot.canonicalPath
        val restoredEntries = mutableListOf<String>()

        ZipInputStream(ByteArrayInputStream(zipPlain)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(targetRoot, entry.name)
                    val canonicalOut = outFile.canonicalPath
                    require(canonicalOut.startsWith("$canonicalRoot${File.separator}")) {
                        "Blocked Zip-Slip path traversal in backup archive"
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                    restoredEntries.add(entry.name)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return restoredEntries
    }

    private fun sealZipBytes(magic: ByteArray, zipBytes: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val secretKey = deriveAesKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        val ciphertext = cipher.doFinal(zipBytes)

        return ByteBuffer.allocate(magic.size + SALT_BYTES + IV_BYTES + ciphertext.size)
            .put(magic)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun openZipBytes(expectedMagic: ByteArray, encryptedArchive: ByteArray, passphrase: String): ByteArray {
        val buf = ByteBuffer.wrap(encryptedArchive)
        val header = ByteArray(expectedMagic.size).also { buf.get(it) }
        require(header.contentEquals(expectedMagic)) { "Backup header mismatch" }

        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val iv = ByteArray(IV_BYTES).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

        val secretKey = deriveAesKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(expectedMagic)
        return cipher.doFinal(ciphertext)
    }

    private fun deriveAesKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val chars = passphrase.toCharArray()
        return try {
            val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            chars.fill('\u0000')
        }
    }
}
