# Security Architecture (`WhatsApp Hub`)

## 1. Encryption at Rest

### A. Android Keystore Envelope Key Hierarchy (`KeystoreKeyManager.kt`)
- Five independent hardware-backed `AES/GCM/NoPadding` 256-bit master keys are generated inside `AndroidKeyStore`:
  - `whatshub_master_sqlcipher_db` — wraps the 32-byte SQLCipher database key (`files/keystore_envelopes/db_sqlcipher.key.wrapped`).
  - `whatshub_master_slot_1..4` — wraps each slot's independent 32-byte session store key (`files/keystore_envelopes/slot_{1..4}.key.wrapped`).
- Every wrapped envelope uses context-bound GCM Additional Authenticated Data (`whatshub-envelope-v1:<alias>`). Raw key bytes are never written to `SharedPreferences`, SQLite, or log files.
- When an account is logged out / removed (`removeAccount(slot)`), its Keystore alias is destroyed and rotated, rendering any deleted blocks cryptographically unrecoverable.

### B. Encrypted Application Database (`WhatsAppHubDatabase.kt`)
- All application tables (`accounts`, `account_sessions`, `chats`, `messages`, `media_files`, `contacts`, `groups`, `group_members`, `sync_state`, `app_settings`) reside inside `whatshub_encrypted.db`, encrypted with **SQLCipher for Android (`net.zetetic:sqlcipher-android:4.6.1`)** using 256-bit AES.
- Every SQL statement uses parameterized bindings (`?`), preventing SQL injection even when contact push names, group subjects, or message bodies contain arbitrary quotes or control characters.

### C. Encrypted Per-Account `whatsmeow` Signal Stores (`session/crypto_store.go`)
- Each account slot (`account_1..4/session.db`) seals all long-term and ephemeral cryptographic material with its slot-specific 256-bit AES-GCM key:
  - Noise private key, Signal Identity private key, and Signed PreKey private key are sealed in `whatshub_sealed_device_keys` (storing random non-secret 32-byte placeholders in `whatsmeow_device` to satisfy `whatsmeow`'s schema constraints).
  - PreKey private keys are sealed in `whatshub_sealed_prekeys`.
  - Signal ratchet `Sessions`, peer `IdentityKeys`, group `SenderKeys`, and `AppStateSyncKeys` are wrapped by `AES-256-GCM` decorators (`WHHUB1` header + 96-bit random nonce + 128-bit authentication tag) with AAD `whatshub:acct=<accountId>:scope=<tableScope>`.

---

## 2. Backup Security (`EncryptedBackupManager.kt`)

- Android cloud/ADB auto-backup is disabled (`android:allowBackup="false"` and restrictive `data_extraction_rules.xml`).
- Optional local backups in *Settings -> Encrypted Local Backup* require a user passphrase (minimum 8 characters), derive a 256-bit key via **PBKDF2-HMAC-SHA256 (210,000 iterations + 16-byte CSPRNG salt)**, and encrypt the archive with **AES-256-GCM (`WHBAK01` magic + AAD)** while enforcing canonical path checks against Zip-Slip traversal on restore.
- Raw Signal/Noise session keys wrapped to the device's hardware Android Keystore are **never** exported in plaintext.

---

## 3. Zero Telemetry & Sanitized Diagnostics (`SanitizedLogger.kt`)

- WhatsApp Hub connects **exclusively** to WhatsApp's official WebSocket/CDN endpoints (`web.whatsapp.com`, `mmg.whatsapp.net`) through `whatsmeow`. No analytics SDKs, crash-reporting clouds, or third-party servers exist in the codebase.
- SQLCipher native logging is silenced (`NoopTarget`), Go `whatsmeow` client/database loggers default to `waLog.Noop`, and `SanitizedLogger` + `bridge.SanitizeError` scrub phone numbers/JIDs, 8-character pairing codes, hex/base64 tokens, and cryptographic field names before displaying diagnostic entries.
