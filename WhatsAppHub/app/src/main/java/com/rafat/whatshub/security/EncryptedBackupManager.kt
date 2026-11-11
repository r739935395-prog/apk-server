package com.rafat.whatshub.security

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

/**
 * Provides explicit user-initiated encrypted local backup & restore of WhatsApp Hub's
 * metadata database and settings using PBKDF2-HMAC-SHA256 (210,000 iterations) + AES-256-GCM.
 *
 * Security rule: Raw unencrypted authentication credentials are NEVER exported.
 */
object EncryptedBackupManager {
    private val MAGIC = "WHUBBK01".toByteArray(Charsets.US_ASCII)
    private const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val MIN_PASSPHRASE_LENGTH = 8

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

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val secretKey = deriveAesKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val ciphertext = cipher.doFinal(zipBytes)

        return ByteBuffer.allocate(MAGIC.size + SALT_BYTES + IV_BYTES + ciphertext.size)
            .put(MAGIC)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun restoreEncryptedBackup(encryptedArchive: ByteArray, passphrase: String, targetRoot: File): List<String> {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val minSize = MAGIC.size + SALT_BYTES + IV_BYTES + 16
        require(encryptedArchive.size > minSize) { "Invalid or truncated encrypted backup archive" }

        val buf = ByteBuffer.wrap(encryptedArchive)
        val header = ByteArray(MAGIC.size).also { buf.get(it) }
        require(header.contentEquals(MAGIC)) { "Unrecognized backup header (expected encrypted WHUBBK01 archive)" }

        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val iv = ByteArray(IV_BYTES).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

        val secretKey = deriveAesKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val zipPlain = cipher.doFinal(ciphertext)

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
