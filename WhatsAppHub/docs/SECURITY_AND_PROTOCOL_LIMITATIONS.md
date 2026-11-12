# Rafat Hub v1.1.0 — Security Architecture, Protocol Limitations & Device Optimization

## 1. Defense-in-Depth Local Security Architecture

1. **Encrypted Application Database (`SQLCipher 4.6.1`, `DB_VERSION = 2`)**:
   - `whatshub_encrypted.db` encrypts all conversations, messages, LID↔PN identity mappings, contacts, groups, sync checkpoints, and settings using 256-bit AES (`SQLCipher 4.6.1`, 16 KB page-size aligned for Android 15).
   - The 256-bit database passphrase is generated via `SecureRandom` and wrapped using a hardware-backed `AES-256-GCM` master key inside `AndroidKeyStore` (`KeystoreKeyManager`).
   - Non-destructive `DatabaseCorruptionHandler` prevents SQLCipher from silently deleting or overwriting the user's database file on transient I/O errors.
2. **Per-Account Cryptographic Isolation (`Account 1..4`)**:
   - Each account slot (`1..4`) uses its own isolated directory (`files/sessions/account_{1..4}/session.db` and `files/media/account_{1..4}/`) and its own independent 256-bit `AES-256-GCM` key unwrapped from `AndroidKeyStore`.
   - Inside Go (`whatsapp-engine/session/crypto_store.go`), `SealedStore` encrypts Signal identity private keys, Noise keys, pre-keys, signed pre-keys, sender keys, and session ratchets at rest with domain-separated AAD (`whatshub-slot-{N}:{table}`).
3. **Portable Encrypted Backup (`WHUBBK02`)**:
   - Uses `PBKDF2-HMAC-SHA256` (`210,000` iterations, 16-byte random salt) + `AES-256-GCM` (12-byte random nonce, 128-bit authentication tag, header AAD `"WHUBBK02"`).
   - Exports structured metadata (accounts labels, LID↔PN mappings, contacts, groups, chats, messages, settings) while **never** exporting raw Signal/Noise authentication keys.
4. **Network & Logging Hardening**:
   - `android:usesCleartextTraffic="false"` and `android:allowBackup="false"` enforced in `AndroidManifest.xml`.
   - `SanitizedLogger` (Kotlin) and `SanitizeLogLine` (Go) redact message contents, QR tokens, pairing codes, Noise/Signal keys, and full phone numbers from all diagnostic logs.

---

## 2. Unofficial Protocol Risk & Supported vs Unsupported Features

### Mandatory Risk Disclosure
**Rafat Hub** uses the open-source `whatsmeow` Go implementation of WhatsApp's Multi-Device WebSocket Linked Companion protocol. It is **not** affiliated with, endorsed by, or supported by WhatsApp LLC or Meta Platforms, Inc.
- Using any unofficial linked-device client may violate WhatsApp's Terms of Service and carries inherent risk of temporary restriction or account suspension.
- **No "anti-ban" guarantee exists or is claimed.** Rafat Hub uses conservative exponential backoff with jitter (`session/backoff.go`), avoids automated bulk sending, and never falsifies UI states.

### Supported Features in `v1.1.0`
- Up to **4 independent WhatsApp accounts** active simultaneously with strict per-account isolation.
- QR Code pairing & 8-character Phone Pairing Code linking.
- 1:1 Direct chats (saved contacts, unsaved phone numbers, and WhatsApp `@lid` privacy accounts with automatic `@lid` ↔ `@s.whatsapp.net` canonical merging) and Group chats (`@g.us`).
- Outgoing & incoming text, quoted replies, emoji reactions, message edits, delete for everyone, local delete for me, forward across chats/accounts, images, videos, audio, voice notes (`.m4a`), documents, stickers, and structured summaries for location/contact/poll messages.
- Explicit `Pending → Sent (✓) → Delivered (✓✓) → Read (✓✓)` and `Failed → Manual Retry` outgoing lifecycle.
- Pin, Archive, Mute, Mark Read/Unread, Global Search, and In-Conversation Search.
- Bilingual **Arabic (RTL)** & **English (LTR)** UI, Font Scale control, and Portable Encrypted Backups (`WHUBBK02`).

### Unsupported WhatsApp Features (Explicitly Disclosed)
- **Voice & Video Calls (VoIP)**: WhatsApp's WebRTC call signaling on linked companions is proprietary and not supported in `whatsmeow`. No fake call buttons exist in Rafat Hub.
- **WhatsApp Status / Stories & Channels / Newsletters**: Viewing or posting Status updates and Channels is not supported in v1.1.0.
- **WhatsApp Payments, Live Location Streaming & Interactive Shop Flows**: Received polls/locations/contacts display clean readable text summaries, while live interactive payment/catalog flows require your primary WhatsApp phone.

---

## 3. Samsung Galaxy S23 Ultra (`SM-S918B`, Android 15 / One UI 7) Background Setup

Samsung One UI 7 aggressively puts background apps into *Deep Sleep* unless explicitly exempted. For instant incoming message notifications across all 4 accounts:
1. Open **Rafat Hub** → **Settings** → tap **Configure Battery Unrestricted (One UI 7)** and select **Unrestricted**.
2. In Samsung **Settings → Battery → Background usage limits**, ensure **Rafat Hub** is **not** listed under *Sleeping apps* or *Deep sleeping apps* (optionally add it to **Never auto sleeping apps**).
3. Keep the silent **Rafat Hub Multi-Account Service** foreground notification enabled so Android 15 (`FOREGROUND_SERVICE_DATA_SYNC`) preserves WebSocket connectivity.
