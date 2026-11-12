# PHASE 1 — COMPREHENSIVE TECHNICAL AUDIT & ROOT-CAUSE ANALYSIS
## Rafat Hub (`com.rafat.whatshub`) — Upgrade `v1.0.0` (`versionCode 1`) → `v1.1.0` (`versionCode 2`)

**Prepared for:** Rafat Al-Majidi  
**Product:** Rafat WhatsApp Hub (`Rafat Hub` / `رافت هب`)  
**Audited Baseline Commit:** `257e3d1e770f238937001dafbf05364327fb31fb` (`v1.0.0`)  
**Primary Target Device:** Samsung Galaxy S21+ 5G (`SM-G996B/DS`, Exynos, 8 GB RAM, Android 15 / One UI 7, 120 Hz, `arm64-v8a`)

---

## EXECUTIVE SUMMARY & MANDATORY SIGNING-IDENTITY DISCLOSURE

Before modifying any source code or release artifacts, a complete line-by-line inspection of all **13 Go engine files**, **33 Kotlin Android files**, **SQLCipher schema & queries**, **JNI boundary code**, and **CI signing scripts** was conducted.

### Critical Release Gate Finding — v1.0.0 Signing Key Status (Section 13 & Rule 17.6)
Per Section 13 (*"If the existing signing identity cannot be preserved, stop the release process and inform me before making changes"*):
- In the `v1.0.0` CI build (`.github/workflows/build-whatsapphub.yml:95–118`), the release APK (`WhatsAppHub/app-release.apk`, Cert SHA-256 `99720a07e2b238762297cbe123fede80ff26d8fbc748240dc010ff259906a230`) was signed with a **one-time ephemeral RSA-4096 keystore** created at `/tmp/whatshub-release.jks` using a random 160-bit password (`openssl rand -hex 20`) and **immediately destroyed via `rm -f "$KEYSTORE_FILE"`** on the CI runner so no plaintext private key would enter Git.
- Because that private key was permanently erased at the end of CI run `43010012582`, **it is cryptographically impossible to sign `v1.1.0` with the `v1.0.0` certificate**.
- Installing an APK signed with a new key over an existing `v1.0.0` installation on Android triggers `INSTALL_FAILED_UPDATE_INCOMPATIBLE` unless `v1.0.0` is uninstalled first.
- **Remediation for `v1.1.0+`:** With your approval, we will establish a **permanent, deterministic `Rafat Hub` signing identity** (stored encrypted / backed by GitHub Actions secrets) so that every future release (`1.1.0` → `1.2.0` → `2.0.0`) upgrades in-place without data loss, while also ensuring our SQLite schema migration (`DB_VERSION 1 → 2`) is 100% non-destructive for in-place upgrades.

---

## 1. ROOT-CAUSE ANALYSIS OF CRITICAL MESSAGING FAILURES

Every real-world messaging defect observed in `v1.0.0` was traced to confirmed code-level defects across the SQLite query layer, coroutine lifecycle, Go event queue, and WhatsApp LID/PN addressing.

### Defect M-1 (CRITICAL): Conversation Query Truncates Newest Messages After 400 Historical Messages
- **Location:** `WhatsAppHub/app/src/main/java/com/rafat/whatshub/database/WhatsAppHubDatabase.kt:857–869`
- **Actual Code in `v1.0.0`:**
  ```sql
  SELECT m.account_id, m.chat_jid, m.message_id, ...
    FROM messages m
    LEFT JOIN message_media mm ...
   WHERE m.account_id = ? AND m.chat_jid = ?
   ORDER BY m.timestamp_ms ASC
   LIMIT 400
  ```
- **Root Cause & Exact Failure Reproduction:**
  1. When an account pairs, `HistorySync` (`INITIAL_BOOTSTRAP` / `FULL` / `RECENT`) routinely imports **500–2,000+ historical messages** into active conversations.
  2. Because the SQL query sorts **`ORDER BY m.timestamp_ms ASC` BEFORE applying `LIMIT 400`**, SQLite returns the **400 oldest messages from months/years ago** and **truncates message #401 onward**!
  3. When the user sends a new message from Hub (or receives a new incoming message), the message is transmitted over WhatsApp (appearing on the recipient's WhatsApp Messenger) and inserted as row #401+ with today's `timestamp_ms`, **yet never appears in `ConversationScreen`** because `getConversationMessages` stops at row #400!
- **Fix for `v1.1.0`:** Query the newest page via an indexed inner `ORDER BY m.timestamp_ms DESC, m.rowid DESC LIMIT ? OFFSET ?` subquery re-ordered `ASC` for display, with smooth pagination when scrolling upward to older history.

---

### Defect M-2 (CRITICAL): Outgoing Messages Sent Without Local `Pending` Record + `viewModelScope` Cancellation After Blocking JNI Transmission
- **Locations:**
  - `ConversationViewModel.kt:129–158` (`sendText`)
  - `ConversationScreen.kt:530` (`draftText = ""` callback)
  - `HubRepository.kt:530–578` (`sendTextMessage`) and `580–654` (`sendMediaMessage`)
- **Root Cause & Exact Failure Reproduction:**
  1. In `v1.0.0`, pressing **Send** does **not** insert a local `Pending` message into SQLite first. Instead, `ConversationViewModel` launches a coroutine inside `viewModelScope` that calls `HubRepository.sendTextMessage(...)`.
  2. Inside `HubRepository.sendTextMessage`, `engine.sendText(...)` invokes `NativeBridge.nativeSendText(...)` — a **synchronous blocking CGO JNI call** (up to 25s timeout) that transmits the message over the Noise WebSocket and awaits WhatsApp's `<ack>` stanza.
  3. Crucially, **WhatsApp servers do NOT echo `waEvents.Message` back to the originating companion device** that sent the message; local persistence relies 100% on the caller saving the `SendResponse` (`res.messageId`, `res.timestampMs`).
  4. If the user presses Back (`onBack()`), switches tabs, or rotates/recreates the screen while `nativeSendText` is in flight:
     - `NativeBridge.nativeSendText` finishes transmitting to WhatsApp (**the recipient receives the message on WhatsApp Messenger!**).
     - Immediately upon returning from JNI into Kotlin `withContext(Dispatchers.IO)`, Kotlin checks coroutine cancellation on the cancelled `viewModelScope` and throws `CancellationException` **before** line 550 (`database.upsertMessageAndUpdateChat(...)`) executes!
     - Likewise, if `database.upsertMessageAndUpdateChat` throws any SQLite error after `engine.sendText` succeeded, the remote transmission succeeded while the local record is completely lost, and retrying would transmit a duplicate message to the recipient!
- **Fix for `v1.1.0`:**
  1. Execute the entire send pipeline on `repositoryScope` (`SupervisorJob() + Dispatchers.IO` + `NonCancellable` around post-send reconciliation), **never** tied to UI `viewModelScope`.
  2. **Step 1 (Before network I/O):** Generate a deterministic client message ID (using `whatsmeow`'s `cli.GenerateMessageID()` format or `local_client_id`), persist the local message row immediately with `status = 'pending'` and `transmission_ack = 0`, and clear the composer **only after** SQLite commits the `pending` row.
  3. **Step 2 (Transmit):** Pass the exact pre-generated WhatsApp `MessageID` (`whatsmeow.SendRequestExtra{ID: msgID}`) into `cli.SendMessage(ctx, to, msg, whatsmeow.SendRequestExtra{ID: preAssignedID})`! Because `whatsmeow` supports client-generated `types.MessageID` in `SendRequestExtra`, the local `message_id` and the server `message_id` are **identical from millisecond zero** — eliminating ID-swap race conditions where a fast delivery/read receipt arrives before `SendMessage` returns, and guaranteeing idempotent reconciliation even across process death!
  4. **Step 3 (Distinguish Network vs DB Failure):** Once `engine.sendText` returns `ok = true`, mark `transmission_ack = 1` in memory/journal before DB update; if DB update ever fails, retry **only** the local DB write — **never** re-transmit over the network. If `engine.sendText` fails (`ok = false`), transition `status = 'failed'` and expose manual **Retry** using the preserved row.

---

### Defect M-3 (CRITICAL): Missing Centralized WhatsApp `@lid` ↔ `@s.whatsapp.net` (PN) Identity Resolution
- **Locations:**
  - `whatsapp-engine/session/manager.go:702–785` (`processIncomingMessage`) & `816–841` (`processReceipt`)
  - `whatsapp-engine/synchronization/history.go:84–160` (`ProcessSyncData`)
  - `WhatsAppHubDatabase.kt:611–760` & `933–956`
- **Root Cause & Exact Failure Reproduction:**
  1. Modern WhatsApp (2025–2026) addresses many 1:1 conversations, receipts, and companion events using **Linked Identity JIDs (`18492048...@lid`)** instead of phone-number JIDs (`96777...@s.whatsapp.net`).
  2. `whatsmeow` provides verified LID↔PN mappings in **three places** that `v1.0.0` completely ignored:
     - `waEvents.Message.Info.MessageSource`: `SenderAlt` and `RecipientAlt` (verified alternate PN/LID JIDs attached by WhatsApp server) + `DeviceSentMeta.DestinationJID`.
     - `waHistorySync.Conversation`: `conv.GetPnJID()` and `conv.GetLidJID()` alongside `conv.GetID()`.
     - `cli.Store.LIDs` (`whatsmeow_lid_map` table per account): `GetPNForLID(ctx, lid)` and `GetLIDForPN(ctx, pn)`.
  3. Because `v1.0.0` stored raw `evt.Info.Chat` without resolving LID↔PN per account:
     - **Split Conversations:** Sending a message to `967771234567@s.whatsapp.net` stored it under `1:967771234567@s.whatsapp.net`, while the contact's reply (or primary-phone sync) arrived under `1:1849204819204@lid` — creating **two separate chat threads** for the same person on the same account! If the user stayed inside the `@s.whatsapp.net` screen, incoming replies landed in the `@lid` thread and never appeared in the open conversation!
     - **Broken Delivery/Read Receipts:** In `WhatsAppHubDatabase.kt:942`, `updateMessageReceiptStatus` ran `WHERE account_id = ? AND chat_jid = ? AND message_id = ?`. When WhatsApp delivered a receipt with `evt.Chat = ...@lid` for a message stored under `...@s.whatsapp.net`, `0` rows matched and the message remained stuck on single-tick `sent` forever!
     - **Raw `@lid` Digits in Contacts/Inbox:** Contacts and chats displayed raw opaque LID numbers (e.g. `+1849204819204`) as if they were phone numbers, violating the rule never to fabricate phone numbers from unverified protocol IDs.
- **Fix for `v1.1.0`:**
  - Build a dedicated, per-account **`IdentityResolver`** component backed by a new SQLite table `identity_mappings (account_id, lid_jid, pn_jid, verified_source, updated_at_ms)` + Go engine emission of verified `(lid_jid, pn_jid)` pairs from `cli.Store.LIDs`, `SenderAlt`, `RecipientAlt`, and `HistorySync` (`GetLidJID` / `GetPnJID`).
  - Maintain original protocol JIDs (`raw_chat_jid`, `raw_sender_jid`) on every record while resolving the canonical `chat_jid` per account consistently, safely merging split `(account_id, lid_jid)` and `(account_id, pn_jid)` threads within the **same** `account_id` (never across accounts!) when a verified mapping is confirmed, and never formatting `@lid` identifiers with a `+` phone prefix.

---

### Defect M-4 (CRITICAL): Destructive Event Polling Without ACK & Uncaught Poller Coroutine Crash
- **Locations:**
  - `whatsapp-engine/events/router.go:87–119` (`Poll` / `PollJSON`)
  - `WhatsAppEngineGateway.kt:104–108` (`pollEvents`)
  - `HubRepository.kt:158–170` (`startEventPoller`) & `182–395` (`processEngineEvents`)
- **Root Cause:**
  1. **Dequeue-Before-Persist Loss:** `Router.Poll(128)` immediately removes 128 events from Go memory (`r.queue`) **before** Kotlin decodes the JSON or commits them to SQLite. If the app process is killed mid-poll, or if `json.decodeFromString` fails on one element (`WhatsAppEngineGateway.kt:107` returns `emptyList()`), all 128 events are permanently lost.
  2. **Fatal Poller Death:** `startEventPoller()` (`HubRepository.kt:160`) has **no `try/catch`** around `processEngineEvents(events)` or individual events in `for (ev in batch)`. Any single runtime/SQLite exception kills `pollerJob` silently for the rest of the process lifetime, halting **all** incoming message delivery across all 4 accounts!
  3. **HistorySync Queue Eviction (`events/router.go:65–78`):** `defaultMaxQueueSize = 8192` drops queued messages if 4 accounts burst >8,192 historical messages simultaneously while Kotlin polls 128 items every 100ms.
- **Fix for `v1.1.0`:**
  - Replace destructive `Poll` with **peek + explicit sequence ACK (`PeekEvents(maxBatch)` → persist in durable SQLite `inbound_event_journal` / target tables → `AckEvents(upToSeq)`)**! Events remain in the Go router until Kotlin durably commits them and acknowledges `upToSeq`.
  - Decode JSON element-by-element (`JsonArray`) so a malformed single envelope is logged (sanitized) and isolated without poisoning the batch.
  - Wrap every single event handler in `runCatching` and wrap the poller loop in supervisor error recovery with backoff so `pollerJob` can **never** die.
  - Throttle/yield HistorySync emission in chunks with adaptive queue capacity so live incoming messages are never evicted by bulk history imports.

---

### Defect M-5 (HIGH): Unwrapped Primary-Device & Rich Message Containers in `ParseE2EMessage`
- **Location:** `whatsapp-engine/messaging/parser.go:46–105` & `synchronization/history.go:130`
- **Root Cause:**
  1. `UnwrapMessage` (`parser.go:46`) did not unwrap `msg.GetDeviceSentMessage().GetMessage()` (messages sent from the primary linked WhatsApp Messenger phone), `msg.GetEditedMessage().GetMessage()`, or `msg.GetGroupMentionedMessage().GetMessage()`.
  2. `ParseE2EMessage` returned `KindIgnore` for Location, Live Location, Contact vCard (`ContactMessage` / `ContactsArrayMessage`), Poll (`PollCreationMessage`), and Interactive/Button/Template messages — causing real incoming messages of those types to vanish silently instead of appearing in the conversation with an accurate descriptive body.
  3. In `synchronization/history.go:130`, `if parsed.Kind != messaging.KindMessage { continue }` dropped edited historical messages (`KindEdit`).

---

## 2. UI/UX, SCROLLING & 120 HZ PERFORMANCE AUDIT

### Defect P-1 (CRITICAL): Root-Level Recomposition & Main-Thread SQLite Reads on Every DB Write
- **Locations:**
  - `WhatsAppHubDatabase.kt:85–87` (`_changeVersion`)
  - `MainActivity.kt:48–52`
  - `ConversationViewModel.kt:57–83`, `InboxViewModel.kt`, `ContactsGroupsViewModel.kt`, `SettingsViewModel.kt`
- **Root Cause:**
  - Every single SQLite write in the entire app (`updateChatTyping`, `updatePresence`, `upsertSyncState`, `upsertMessageAndUpdateChat` on Account 4 while viewing Account 1) increments a single global `_changeVersion: StateFlow<Long>`.
  - **`MainActivity.kt:48–52` collects `repo.database.changeVersion` inside root `setContent`** and executes synchronous SQLite queries (`repo.getThemeMode()`, `repo.getLanguageCode()`) **on the Android Main UI thread** on every single tick!
  - Simultaneously, `ConversationViewModel` re-executes 5 SQLite queries and emits a brand-new `ConversationUiState` every time any unrelated account receives a presence ping or history sync chunk — causing visible **screen shaking, frame drops, and recomposition storms** on the 120 Hz Galaxy S21+ display.
- **Fix for `v1.1.0`:**
  - Replace the global `changeVersion` counter with **domain-scoped reactive invalidation channels**:
    1. `accountsFlow` (only when `accounts` / `sessions` change)
    2. `inboxInvalidations` (coalesced/conflated with 32ms frame-aware debounce during bulk HistorySync)
    3. `conversationInvalidations: SharedFlow<Pair<Int, String>>` (emits **only** `(accountId, canonicalChatJid)` so an open conversation on Account 1 **never** queries SQLite or recomposes when Account 2/3/4 updates!)
    4. `contactsGroupsInvalidations`
    5. `presenceTypingFlow` (lightweight in-memory/scoped map separated from message list state so typing/online updates **never** re-query or recompose the `LazyColumn` message items!)
    6. `appPreferencesFlow` (`StateFlow<AppPreferences>` cached in memory — **zero** main-thread SQLite reads in `MainActivity`!).

### Defect P-2 (HIGH): Forced Scroll-to-Bottom Jump When Reading History
- **Location:** `ConversationScreen.kt:148–152`
- **Actual Code:**
  ```kotlin
  LaunchedEffect(state.messages.size) {
      if (state.messages.isNotEmpty()) {
          listState.animateScrollToItem(state.messages.lastIndex)
      }
  }
  ```
- **Root Cause:** Whenever the user scrolls up to read older messages and a new message arrives, `state.messages.size` changes and forces `animateScrollToItem(lastIndex)`, yanking the viewport away from what the user is reading.
- **Fix for `v1.1.0`:** Check `isNearBottom` (`derivedStateOf` inspecting `listState.layoutInfo` within 2 items of the newest message) OR whether the newest message was just sent by the local user (`isFromMe`). Only auto-scroll when `isNearBottom || justSentByMe`; otherwise preserve exact scroll anchor (`key = { it.compositeKey }`) and show a clean floating **"New messages ↓"** badge chip to jump to bottom on tap.

### Defect P-3 (HIGH): Image Flickering & Main-Thread `File.exists()` / `File.length()` in `LazyColumn`
- **Location:** `ConversationScreen.kt:671–676`, `737–747`, `833`
- **Root Cause:**
  - `LazyColumn` `items(...)` omitted `contentType = { it.contentKind }`.
  - Inside `MessageBubble`, `File(path).exists()` and `localFile.length()` run synchronous POSIX `stat()` disk I/O on the UI thread on every recomposition.
  - `AsyncImage(model = localFile)` lacked a singleton configured `ImageLoader` (memory + disk cache) and stable `ImageRequest` cache keys (`memoryCacheKey(localPath)`), causing Coil to re-resolve models during status tick updates (`pending` → `sent` → `delivered` → `read`).

---

## 3. DATABASE INTEGRITY, MIGRATION & SECURITY AUDIT

### Defect S-1 (HIGH): Destructive `onCorruption` Callback & Missing `v1 → v2` Schema Migration
- **Location:** `WhatsAppHubDatabase.kt:55–79`
- **Root Cause:**
  - `DB_VERSION = 1` had an empty `onUpgrade` body (`seedDefaultAccounts(db)` only), and `onCorruption` automatically renamed the database file without user consent.
- **Fix for `v1.1.0`:**
  - Implement non-destructive transactional `migrateV1ToV2(db)` (`DB_VERSION = 2`) using `ALTER TABLE ... ADD COLUMN` and `CREATE TABLE IF NOT EXISTS` (`identity_mappings`, `inbound_event_journal`, `chats` pin/archive/mute/marked_unread columns, `messages` `client_msg_id` / `transmission_ack` / `raw_chat_jid` columns, plus composite indexes for search, inbox sorting, and receipt lookup by `(account_id, message_id)`), preserving 100% of `v1.0.0` rows and sessions.

### Defect S-2 (MEDIUM): Backup Restore Validation-Only Behavior (`SettingsViewModel.kt:101–116`)
- **Location:** `SettingsViewModel.kt:101–116` & `HubRepository.kt:857–869`
- **Root Cause:** `exportEncryptedBackup` zipped the raw SQLCipher file (which is tied to the old install's Android Keystore master key), and `restoreEncryptedBackupFromUri` only verified decryption in a staging directory and deleted it without importing records.
- **Fix for `v1.1.0`:** Export an encrypted JSON snapshot of accounts metadata, identity mappings, contacts, groups, chats, messages, and app settings inside the `PBKDF2-HMAC-SHA256 (210,000 iter) + AES-256-GCM` `.whbak` container (still **never** exporting raw Signal/Noise session keys in plaintext), and implement transactional idempotent restore into SQLCipher so backups genuinely restore user conversations and settings across installations.

### Security Controls Verified Sound in `v1.0.0` (Retained for `v1.1.0`)
- **SQLCipher Encryption:** Confirmed active (`libsqlcipher.so` + `SupportOpenHelperFactory(keyBytes)` with 256-bit key wrapped by `AndroidKeyStore` `AES/GCM/NoPadding`). We will add an explicit automated test verifying that reading raw `whatshub_encrypted.db` bytes without SQLCipher yields random high-entropy ciphertext with no `SQLite format 3` header.
- **Per-Slot Go Signal Store Encryption:** Confirmed active (`whatshub_sealed_device_keys`, `whatshub_sealed_prekeys`, and `WHHUB1` AES-256-GCM decorators per account `1..4`).
- **Zero External Telemetry:** Confirmed zero third-party analytics/cloud endpoints; outbound connections go strictly to WhatsApp servers via `whatsmeow`.
- **Permissions Audit:** Note `RECORD_AUDIO` was declared in `AndroidManifest.xml` for voice notes; we will verify all permissions are minimal, runtime-gated, and clearly explained, removing any unused capability or gating voice recording cleanly.

---

## 4. PHASE 2–4 UPGRADE ARCHITECTURE PLAN (AWAITING SIGNING IDENTITY CONFIRMATION)

1. **Official Branding (`Rafat Hub` / `رافت هب`, `v1.1.0`, `versionCode = 2`, `com.rafat.whatshub`)**:
   - Launcher name `Rafat Hub` (`values/strings.xml`) & `رافت هب` (`values-ar/strings.xml`), custom **RH monogram** adaptive vector icon (Teal `#14B8A6` + subtle Gold `#D4AF37` accent on `#0B0B0F` obsidian background), Android 12+ branded splash screen, and dedicated **About Screen** (`Product: Rafat WhatsApp Hub`, `Brand / Developer: Rafat Al-Majidi`, `Version: 1.1.0`).
2. **Complete Messaging Pipeline Overhaul**:
   - Pre-assigned `MessageID` + durable `Pending → Sent → Delivered → Read / Failed → Retry` state machine on `repositoryScope`.
   - Peek-and-Acknowledge (`PeekEvents` / `AckEvents`) JNI event pipeline with per-envelope isolation and crash recovery.
   - Centralized per-account `IdentityResolver` (`@lid` ↔ `@s.whatsapp.net`) across Go (`LIDStore`, `SenderAlt`, `RecipientAlt`, `HistorySync`) and Android (`identity_mappings` table + automatic same-account thread & receipt reconciliation).
   - Full `DeviceSentMessage`, `EditedMessage`, Location, Contact, and Poll message unwrapping/parsing.
   - Fixed newest-window `getConversationMessages` query with upward pagination.
3. **Complete UI/UX Redesign & Requested Features**:
   - Official palette (`#0B0B0F` Main Background, `#15151C` Card Background, `#14B8A6` Primary Accent, `#D4AF37` Secondary Accent, `#F5F5F5` Primary Text, `#9CA3AF` Secondary Text) + optional Light Theme + Font-Size Controls (`Small`, `Default`, `Large`, `Extra Large`).
   - Redesigned Main Dashboard, 4-Account Switcher & Overview Bar, Conversation Screen (scroll-position preservation, in-chat search, reply, copy, share, forward across chats within account or chosen account, delete, message details dialog, pin/archive/mute/mark-unread), Contacts Screen (clean PN/display names, zero raw `@lid` clutter), and 8-category Settings Screen + sanitized log export.
4. **Validation & Release (`v1.1.0`)**:
   - Automated Go + Kotlin/Robolectric test suites covering all **22 Messaging Scenarios**, `v1.0.0 → v1.1.0` SQLite migration integrity, SQLCipher raw-file encryption verification, 4-account isolation, and query/recomposition performance benchmarks.
