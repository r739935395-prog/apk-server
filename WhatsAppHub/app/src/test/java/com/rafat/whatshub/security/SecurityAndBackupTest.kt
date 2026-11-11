package com.rafat.whatshub.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class InMemoryAesGcmWrapper : MasterKeyWrapper {
    private val keys = mutableMapOf<String, SecretKey>()

    private fun keyFor(alias: String): SecretKey = keys.getOrPut(alias) {
        KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
    }

    override fun wrap(alias: String, plaintextKey: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(alias))
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintextKey)
        return ByteBuffer.allocate(4 + iv.size + ct.size).putInt(iv.size).put(iv).put(ct).array()
    }

    override fun unwrap(alias: String, wrappedBlob: ByteArray, aad: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(wrappedBlob)
        val ivLen = buf.int
        val iv = ByteArray(ivLen).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFor(alias), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    override fun deleteAlias(alias: String) {
        keys.remove(alias)
    }
}

class SecurityAndBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun keystoreManagerIsolatesFourSlotKeysAndPersistsOnlyCiphertext() {
        val dir = tempFolder.newFolder("envelopes")
        val wrapper = InMemoryAesGcmWrapper()
        val mgr = KeystoreKeyManager(dir, wrapper)

        val dbKey1 = mgr.getOrCreateDatabaseKey()
        val dbKey2 = mgr.getOrCreateDatabaseKey()
        assertEquals(32, dbKey1.size)
        assertArrayEquals(dbKey1, dbKey2)

        val slotKeys = (1..4).map { acct -> mgr.getOrCreateAccountSlotKeyHex(acct) }
        assertEquals(4, slotKeys.distinct().size)
        slotKeys.forEach { hex -> assertEquals(64, hex.length) }

        // Ensure raw hex or raw key bytes do not appear in wrapped envelope files
        val slot1RawBytes = slotKeys[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val onDisk = File(dir, "slot_1.key.wrapped").readBytes()
        assertFalse(onDisk.toHex().contains(slot1RawBytes.toHex()))

        // Rotating Account 2 key changes ONLY Account 2
        val rotated2 = mgr.rotateAccountSlotKey(2)
        assertNotEquals(slotKeys[1], rotated2)
        assertEquals(slotKeys[0], mgr.getOrCreateAccountSlotKeyHex(1))
        assertEquals(slotKeys[2], mgr.getOrCreateAccountSlotKeyHex(3))
        assertEquals(slotKeys[3], mgr.getOrCreateAccountSlotKeyHex(4))
    }

    @Test
    fun encryptedBackupRoundTripAndTamperRejection() {
        val srcDir = tempFolder.newFolder("src")
        val sampleDb = File(srcDir, "whatshub_encrypted.db").apply {
            writeBytes("SQLCIPHER_ENCRYPTED_PAYLOAD_BYTES_12345".toByteArray())
        }

        val archive = EncryptedBackupManager.createEncryptedBackup(
            mapOf("metadata/whatshub_encrypted.db" to sampleDb),
            "StrongPassphrase#2026"
        )
        // Verify ciphertext does not contain plaintext payload
        assertFalse(String(archive, Charsets.ISO_8859_1).contains("SQLCIPHER_ENCRYPTED_PAYLOAD_BYTES"))

        // Wrong passphrase must fail GCM authentication
        val badOut = tempFolder.newFolder("bad_restore")
        val wrongPassFailed = runCatching {
            EncryptedBackupManager.restoreEncryptedBackup(archive, "WrongPassphrase#999", badOut)
        }.isFailure
        assertTrue(wrongPassFailed)

        // Correct passphrase restores exact bytes
        val goodOut = tempFolder.newFolder("good_restore")
        val restored = EncryptedBackupManager.restoreEncryptedBackup(archive, "StrongPassphrase#2026", goodOut)
        assertEquals(listOf("metadata/whatshub_encrypted.db"), restored)
        assertArrayEquals(sampleDb.readBytes(), File(goodOut, "metadata/whatshub_encrypted.db").readBytes())
    }

    @Test
    fun sanitizedLoggerScrubsSecretsPairingCodesAndPhoneJids() {
        SanitizedLogger.clear()
        SanitizedLogger.info("Test", "Pairing code ABCD-1234 for 967771234567@s.whatsapp.net")
        SanitizedLogger.warn("Test", "noise_key = 00112233445566778899aabbccddeeff")

        val lines = SanitizedLogger.entries.value.map { it.message }
        assertTrue(lines.none { it.contains("ABCD-1234") })
        assertTrue(lines.none { it.contains("967771234567") })
        assertTrue(lines.none { it.contains("00112233445566778899aabbccddeeff") })
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
