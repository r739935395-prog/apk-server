# Rafat Hub (`رافت هب`) — v1.1.0

**Rafat WhatsApp Hub** (`com.rafat.whatshub`, `versionName = "1.1.0"`, `versionCode = 2`) is a native Android (`Kotlin` + `Jetpack Compose` + `SQLCipher 4.6.1`) and `Go 1.26` (`whatsmeow` CGO JNI) multi-account WhatsApp companion application engineered for **Samsung Galaxy S23 Ultra (`SM-S918B`, Android 15 / One UI 7, `arm64-v8a`)** and ARM64 Android 9–15 devices.

It enables running up to **4 independent WhatsApp accounts simultaneously** inside one unified luxury dark hub with strict per-account cryptographic, storage, media, and routing isolation.

---

## Documentation Index (`WhatsAppHub/docs/`)

1. **[Technical Audit & Root-Cause Analysis (`TECHNICAL_AUDIT_V1_1.md`)](docs/TECHNICAL_AUDIT_V1_1.md)** — Complete Phase 1 forensic audit of v1.0.0 messaging, database, identity, and signing defects.
2. **[Release Notes (`RELEASE_NOTES_V1_1_0.md`)](docs/RELEASE_NOTES_V1_1_0.md)** — Summary of all v1.1.0 engineering upgrades and UI capabilities.
3. **[Safe Migration Guide (`MIGRATION_GUIDE_V1_0_TO_V1_1.md`)](docs/MIGRATION_GUIDE_V1_0_TO_V1_1.md)** — Step-by-step migration instructions from `v1.0.0` to `v1.1.0` and `WHUBBK02` portable encrypted backups.
4. **[Signing & Permanent Key Custody (`SIGNING_AND_KEY_CUSTODY.md`)](docs/SIGNING_AND_KEY_CUSTODY.md)** — Public certificate fingerprint (`55:B8:FA:6F...`), GitHub Secrets setup, and dual encrypted vault custody rules.
5. **[Security, Protocol Limitations & One UI 7 Setup (`SECURITY_AND_PROTOCOL_LIMITATIONS.md`)](docs/SECURITY_AND_PROTOCOL_LIMITATIONS.md)** — SQLCipher/Keystore architecture, supported vs unsupported WhatsApp features, unofficial protocol risk disclosure, and Samsung battery optimization setup.
6. **[QA & Verification Report (`QA_VERIFICATION_REPORT_V1_1_0.md`)](docs/QA_VERIFICATION_REPORT_V1_1_0.md)** — Exact automated test results, APK binary verification metrics, and honest physical-device testing status.

---

## Architecture Highlights

- **4-Slot Isolated Go Companion Engine (`whatsapp-engine/`)**:
  - `session/`: Per-account `SealedStore` encrypting Signal/Noise keys with hardware-backed Android Keystore slot keys + `@lid` ↔ `@s.whatsapp.net` canonical identity resolution.
  - `events/`: `32,768`-slot critical-preserving queue with non-destructive `Peek` + explicit `Ack` and PII-redacted logging.
  - `messaging/` & `synchronization/`:Deterministic `"3EB0..."` client message ID correlation, full wrapper unwrapping (`DeviceSentMessage`, `EditedMessage`, `ViewOnce`, `Ephemeral`, `GroupMentioned`), media encryption/decryption, and batched `HistorySync`.
- **Android Application Layer (`app/`)**:
  - `WhatsAppHubDatabase` (`DB_VERSION = 2` on `SQLCipher 4.6.1`, 16 KB page-size aligned): Non-destructive schema migration, `identity_mappings` with transactional `@lid`→`@s.whatsapp.net` conversation merge, `inbound_event_journal`, newest-window message pagination, and `WHUBBK02` portable encrypted snapshots.
  - `HubRepository`: `NonCancellable` `Pending → Sent → Delivered → Read / Failed → Retry` outgoing pipeline and supervised poller.
  - `Jetpack Compose Material 3`: Luxury Deep Obsidian (`#0B0B0F`) + Refined Teal (`#14B8A6`) + Warm Gold (`#D4AF37`) theme, distinct 4-account badges (`#10B981`, `#3B82F6`, `#8B5CF6`, `#F59E0B`), and full Arabic RTL / English LTR support.
