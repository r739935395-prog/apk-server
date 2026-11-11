# Testing & Verification Plan (`WhatsApp Hub`)

## 1. Automated Unit & Integration Test Suites

### A. Go `whatsmeow` Engine Tests (`WhatsAppHub/whatsapp-engine/...`)
Executed with `go test -v -race ./...`:
- **`session/crypto_store_test.go`**:
  - Verifies sealing of Noise, Identity, SignedPreKey, and PreKey private keys in SQLite (`whatshub_sealed_device_keys` / `whatshub_sealed_prekeys`) while satisfying `whatsmeow`'s 32-byte `CHECK` constraints.
  - Verifies `AES-256-GCM` encryption of Signal `Sessions`, `IdentityKeys`, `SenderKeys`, and `AppStateSyncKeys` (`WHHUB1` ciphertext envelope) and confirms zero plaintext leakage in raw SQLite queries.
  - Verifies cross-account cryptographic isolation: ciphertext bound to `whatshub:acct=1:...` fails authentication if opened with Account 2's key or AAD.
  - Verifies `SQLStore.MigratePNToLID` works seamlessly with encrypted Signal sessions.
- **`session/backoff_test.go` & `session/manager_test.go`**:
  - Verifies bounded exponential backoff with jitter (`1s` -> `60s`), slot range enforcement (`1..4`), independent per-slot directory provisioning, and clean slot removal without disturbing sibling slots.
- **`events/router_test.go`**:
  - Verifies FIFO delivery, ring-buffer backpressure eviction on slow consumers, and strict rejection of unattributed (`accountId < 1 || accountId > 4`) events.
- **`messaging/parser_test.go`**:
  - Verifies extraction of text, extended text + quoted replies, image/video/voice/document/sticker metadata, reactions, protocol revokes, and message edits.
- **`synchronization/history_test.go`**:
  - Verifies `waHistorySync.HistorySync` conversation & message extraction, account attribution (`account_id`), chronological sorting, and sync progress emission.
- **`android-bridge/bridge_test.go`**:
  - Verifies JSON bridge dispatch, slot validation, and `SanitizeError` scrubbing of phone JIDs, pairing codes, and long secret tokens.

### B. Kotlin / Robolectric Unit & Integration Tests (`WhatsAppHub/app/src/test/...`)
Executed with `gradle testReleaseUnitTest`:
- **`security/SecurityAndBackupTest.kt`**:
  - Verifies `KeystoreKeyManager` generates 4 distinct 256-bit slot keys + 1 DB key, stores only wrapped ciphertext on disk, and rotates a single account slot key without changing the other 3.
  - Verifies `EncryptedBackupManager` PBKDF2-HMAC-SHA256 + AES-256-GCM round-trip encryption and rejects wrong passphrases or tampered archives.
  - Verifies `SanitizedLogger` redacts pairing codes, JIDs, and secret key fields.
- **`database/WhatsAppHubDatabaseTest.kt`**:
  - Verifies all 10 tables initialize cleanly, enforces composite keys `(account_id, chat_jid, message_id)` when all 4 accounts exchange messages with the **exact same contact JID and message ID**, prevents duplicate unread increments, sorts the unified inbox newest-first, and wipes a single account cleanly while preserving the other 3.
- **`bridge/FourAccountIntegrationTest.kt`**:
  - Verifies requesting a phone pairing code transitions the account ONLY to `PAIRING` (never falsely to `CONNECTED` prior to protocol confirmation).
  - Exercises the multi-account lifecycle across Accounts 1..4: simultaneous connection, incoming message attribution on identical contact JIDs, strict per-account outgoing send routing, single-account disconnect isolation, and network drop/restore transitions.

---

## 2. 15-Step Physical Device Test Protocol (Samsung Galaxy S21+ 5G `SM-G996B/DS`)

> **Environment Honesty Note:** Automated Go and Kotlin/Robolectric tests are executed and verified in CI (`WhatsAppHub/docs/ci-verification-report.txt`), whereas physical hardware testing on a real Samsung Galaxy S21+ 5G with 4 live WhatsApp SIM/accounts cannot be executed inside an automated build environment and must be performed on-device using the checklist below.

1. **Connect Account 1 (Green):** Open *Accounts -> Account 1 -> Link via QR* (or *Pairing Code*). Scan from Phone 1 (*WhatsApp -> Linked Devices -> Link a Device*). Confirm status reaches `Connected` and history sync updates `Unified Inbox`.
2. **Connect Account 2 (Blue):** Link Phone 2 into Slot 2. Confirm Slot 1 stays `Connected` without interruption.
3. **Connect Account 3 (Purple):** Link Phone 3 into Slot 3 via 8-character Pairing Code. Confirm status stays `Pairing` while entering code on Phone 3 and changes to `Connected` only after Phone 3 finishes linking.
4. **Connect Account 4 (Orange):** Link Phone 4 into Slot 4. Confirm persistent notification displays `4/4 accounts connected`.
5. **Receive Messages Across All 4 Accounts:** Send a text message from an external phone to Accounts 1, 2, 3, and 4. Confirm 4 separate rows appear in *Unified Inbox* with Green, Blue, Purple, and Orange badges respectively.
6. **Send Messages Through Each Account:** Open each of the 4 conversations, verify the top color bar and `"Sending as Account X"` banner above the composer, and send a reply + voice note + image. Confirm recipient sees each message arrive from the exact expected phone number.
7. **VerifyUnified Inbox Attribution & Filters:** Tap `Account 1..4`, `Unread`, `Groups`, and `Direct` chips and search by keyword; confirm zero cross-account leakage.
8. **Disconnect Account 2:** In *Accounts -> Account 2*, tap *Disconnect*. Confirm Account 2 transitions to `Disconnected`.
9. **Confirm Accounts 1, 3, and 4 Remain Active:** Send and receive messages on Accounts 1, 3, and 4 while Account 2 is disconnected; verify instant delivery.
10. **Close & Restart Application:** Swipe WhatsApp Hub away from Recents and relaunch.
11. **Verify Session Restoration:** Confirm Accounts 1, 3, and 4 automatically restore to `Connected` without re-scanning QR codes, while manually disconnected Account 2 remains `Disconnected` until *Reconnect* is tapped.
12. **Airplane Mode / Network Drop:** Reconnect Account 2 so all 4 are active, enable Airplane Mode for 20 seconds, and confirm slots transition to `Reconnecting` / `Disconnected`.
13. **Network Restoration:** Disable Airplane Mode (Wi-Fi / 5G restored) and confirm `ConnectivityMonitor` triggers automatic reconnection across all 4 slots.
14. **Verify No Message Duplication:** Have an external contact send messages while offline and after reconnect; confirm each `(account_id, chat_jid, message_id)` appears once.
15. **Verify Zero Cross-Account Mixing on Logout:** In *Accounts -> Account 4*, tap *Logout* and confirm. Verify Account 4's chats, messages, and media are wiped while Accounts 1, 2, and 3 retain all conversations and remain connected.
