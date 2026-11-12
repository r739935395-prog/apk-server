# Rafat Hub `v1.1.0` (`versionCode = 2`) — Release Notes

## Overview
**Rafat Hub v1.1.0** (`رافت هب`) is a major reliability, data-integrity, identity-resolution, signing-continuity, and UI/UX engineering upgrade to `com.rafat.whatshub` targeting **Samsung Galaxy S23 Ultra (`SM-S918B`, Android 15 / One UI 7, `arm64-v8a`)** and modern ARM64 Android 9–15 devices.

---

## Key Upgrades & Critical Root-Cause Fixes

### 1. Permanent Publisher Signing Identity (`CN=Rafat Al-Majidi`)
- Replaced the ephemeral v1.0.0 CI keystore generator with a permanent **RSA-4096** release signing key (`alias = rafat_hub_release`, Cert SHA-256 `55:B8:FA:6F:32:A3:C4:76:4E:73:47:5D:F3:6C:06:2C:08:30:27:C8:B3:B4:8B:61:B7:F2:AC:3B:D0:B4:76:49`) sealed in dual encrypted vaults outside Git.
- Every future release (`v1.1.1`, `v1.2.0`, `v2.0.0`, …) will update **directly in place** without requiring uninstallation or losing paired WhatsApp sessions.

### 2. End-to-End Outgoing & Incoming Messaging Reliability
- **Fixed >400-Message Conversation Truncation (`WhatsAppHubDatabase.kt`)**: Replaced `ORDER BY timestamp_ms ASC LIMIT 400` (which silently hid all new incoming and outgoing messages once a chat reached 400 messages) with an indexed newest-window subquery (`ORDER BY timestamp_ms DESC, id DESC LIMIT N` re-ordered `ASC`) plus on-demand **Load earlier messages** pagination.
- **Durable `Pending → Sent → Delivered → Read / Failed → Retry` Outgoing Pipeline**:
  - Pre-generates standard WhatsApp `"3EB0..."` message IDs (`MessageIdGenerator` + `whatsmeow.SendRequestExtra{ID}`) and commits a `status = "pending"` row in SQLite **before** clearing the composer or invoking JNI transmission.
  - Executes JNI send + DB reconciliation inside a `NonCancellable` repository scope so pressing Back or rotating the screen mid-send **never** drops a message.
  - Distinguishes network failure (`transmission_ack = 0` → `Failed` with inline **Retry** button) from post-ACK local reconciliation so retrying never sends duplicate messages.
  - Startup crash-recovery pass (`recoverInterruptedPendingMessages`) safely transitions any interrupted un-ACKed pending messages to `Failed` for manual retry.
- **Fault-Isolated `Peek + Persist + Ack` Event Ingestion**:
  - Added non-destructive `PeekEvents` / `AckEvents` across JNI and raised the Go router queue to `32,768` with critical-event eviction protection (`IsCriticalEvent` never drops messages, receipts, revocations, QR/pairing codes, or identity mappings during typing/presence bursts).
  - Replaced batch-wide JSON decoding with per-element `decodeEnvelopeArraySafely` and wrapped the poller coroutine in a supervised exception-recovering loop.
- **Complete Protobuf Wrapper Unwrapping (`messaging/parser.go` & `synchronization/history.go`)**:
  - Unwraps `DeviceSentMessage` (messages sent from your primary phone), `EditedMessage` / `ProtocolMessage(MESSAGE_EDIT)`, `EphemeralMessage`, `ViewOnceMessage` / `V2` / `V2Extension`, `DocumentWithCaptionMessage`, `GroupMentionedMessage`, and `LottieStickerMessage`, plus clean text summaries for Location, Contact/vCard, Poll, and Button/List messages.

### 3. Per-Account `@lid` ↔ `@s.whatsapp.net` Canonical Identity Resolution
- Implemented per-account LID↔PN mapping in both Go (`session/manager.go` + `whatsmeow` `Store.LIDs` + `HistorySync`) and Android (`identity_mappings` table + `IdentityResolver.kt`).
- Automatically merges split `@lid` and `@s.whatsapp.net` conversations on the same account without cross-account leakage, correlates receipts/reactions/edits across JID formats via `(account_id, message_id)` fallback, and prevents raw `@lid` identifiers from ever leaking as fake phone numbers in the UI.

### 4. Non-Destructive SQLCipher v2 Storage & Portable `WHUBBK02` Encrypted Backups
- Upgraded database schema to `DB_VERSION = 2` with non-destructive `migrateV1ToV2` and non-destructive `DatabaseCorruptionHandler`.
- Introduced portable **`WHUBBK02`** (`PBKDF2-HMAC-SHA256` 210k + `AES-256-GCM`) encrypted backups capable of restoring chats, messages, contacts, groups, LID/PN mappings, and settings across clean installs and new devices.

### 5. **Rafat Hub** (`رافت هب`) Rebrand, Luxury Obsidian UI & Performance Polish
- Rebranded to **Rafat Hub** (`رافت هب`) with a custom geometric **RH** monogram adaptive icon (including Android 13–15 monochrome layer), smooth dark splash screen, and `#0B0B0F` Obsidian / `#15151C` Carbon Slate / `#14B8A6` Refined Teal / `#D4AF37` Warm Gold palette.
- Replaced global database recomposition triggers with domain-scoped & conversation-scoped flows, eliminated main-thread SQLite reads in `MainActivity`, configured Coil memory/disk media caching, preserved scroll position with a floating **"New messages ↓"** indicator, and added **Pin, Archive, Mute, Mark Unread, Forward, Delete for Me, In-Chat Search, Global Message Search, Font Scale controls, Per-Account Notification toggles, and About screen**.
