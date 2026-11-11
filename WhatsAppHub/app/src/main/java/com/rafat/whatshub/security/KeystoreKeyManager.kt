package com.rafat.whatshub.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction over master-key wrapping so Android Keystore hardware keys are used on device
 * while JVM unit tests can test envelope wrapping, tamper detection, and per-account key isolation.
 */
interface MasterKeyWrapper {
    fun wrap(alias: String, plaintextKey: ByteArray, aad: ByteArray): ByteArray
    fun unwrap(alias: String, wrappedBlob: ByteArray, aad: ByteArray): ByteArray
    fun deleteAlias(alias: String)
}

class AndroidKeystoreWrapper : MasterKeyWrapper {
    private val keyStoreType = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val gcmTagBits = 128

    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(keyStoreType).apply { load(null) }
        val existing = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun wrap(alias: String, plaintextKey: ByteArray, aad: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey(alias)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintextKey)
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun unwrap(alias: String, wrappedBlob: ByteArray, aad: ByteArray): ByteArray {
        require(wrappedBlob.size > 16) { "Corrupted wrapped key blob" }
        val buf = ByteBuffer.wrap(wrappedBlob)
        val ivLen = buf.int
        require(ivLen in 12..16 && wrappedBlob.size > 4 + ivLen) { "Invalid GCM IV length" }
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val ciphertext = ByteArray(buf.remaining())
        buf.get(ciphertext)

        val ks = KeyStore.getInstance(keyStoreType).apply { load(null) }
        val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Missing Android Keystore alias: $alias")
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, GCMParameterSpec(gcmTagBits, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    override fun deleteAlias(alias: String) {
        val ks = KeyStore.getInstance(keyStoreType).apply { load(null) }
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
    }
}

/**
 * Manages isolated 256-bit keys for:
 *  - SQLCipher application database ("whatshub_sqlcipher_master")
 *  - Four independent Go whatsmeow account stores ("whatshub_slot_1" .. "whatshub_slot_4")
 *
 * Raw 32-byte keys are never persisted in plaintext on disk.
 */
class KeystoreKeyManager(
    private val keyStorageDir: File,
    private val wrapper: MasterKeyWrapper = AndroidKeystoreWrapper()
) {
    private val secureRandom = SecureRandom()

    init {
        if (!keyStorageDir.exists()) {
            keyStorageDir.mkdirs()
        }
    }

    @Synchronized
    fun getOrCreateDatabaseKey(): ByteArray {
        val file = File(keyStorageDir, "sqlcipher_db.key.wrapped")
        val alias = "whatshub_sqlcipher_master_v1"
        val aad = "whatshub:db:v1".toByteArray(Charsets.UTF_8)
        return loadOrGenerate32ByteKey(file, alias, aad)
    }

    @Synchronized
    fun getOrCreateAccountSlotKeyHex(accountId: Int): String {
        require(accountId in 1..4) { "Invalid accountId $accountId: must be 1..4" }
        val file = File(keyStorageDir, "slot_${accountId}.key.wrapped")
        val alias = "whatshub_account_slot_${accountId}_v1"
        val aad = "whatshub:slot:$accountId:v1".toByteArray(Charsets.UTF_8)
        val raw = loadOrGenerate32ByteKey(file, alias, aad)
        return try {
            raw.joinToString("") { "%02x".format(it) }
        } finally {
            raw.fill(0)
        }
    }

    @Synchronized
    fun rotateAccountSlotKey(accountId: Int): String {
        require(accountId in 1..4) { "Invalid accountId $accountId: must be 1..4" }
        val file = File(keyStorageDir, "slot_${accountId}.key.wrapped")
        val alias = "whatshub_account_slot_${accountId}_v1"
        if (file.exists()) {
            file.delete()
        }
        wrapper.deleteAlias(alias)
        return getOrCreateAccountSlotKeyHex(accountId)
    }

    private fun loadOrGenerate32ByteKey(file: File, alias: String, aad: ByteArray): ByteArray {
        if (file.exists() && file.length() > 16) {
            return wrapper.unwrap(alias, file.readBytes(), aad)
        }
        val fresh = ByteArray(32)
        secureRandom.nextBytes(fresh)
        val wrapped = wrapper.wrap(alias, fresh, aad)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(file)) {
            file.writeBytes(wrapped)
            tmp.delete()
        }
        return fresh
    }

    companion object {
        fun fromContext(context: Context): KeystoreKeyManager {
            val dir = File(context.noBackupFilesDir, "keystore_envelopes")
            return KeystoreKeyManager(dir)
        }

        /**
         * Exports Android's trusted system CA certificates (including Android 14/15 Conscrypt APEX roots)
         * into a private PEM bundle consumed by Go's crypto/x509 TLS verifier via SSL_CERT_FILE.
         */
        fun exportAndroidSystemCaBundle(context: Context): String {
            return try {
                val outDir = File(context.noBackupFilesDir, "cacerts")
                if (!outDir.exists()) outDir.mkdirs()
                val pemFile = File(outDir, "android_system_roots.pem")
                val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val sb = StringBuilder(65536)
                val aliases = ks.aliases()
                val encoder = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII))
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                    sb.append("-----BEGIN CERTIFICATE-----\n")
                    sb.append(encoder.encodeToString(cert.encoded))
                    sb.append("\n-----END CERTIFICATE-----\n")
                }
                if (sb.isNotEmpty()) {
                    pemFile.writeText(sb.toString(), Charsets.US_ASCII)
                    pemFile.absolutePath
                } else {
                    ""
                }
            } catch (_: Throwable) {
                ""
            }
        }
    }
}
