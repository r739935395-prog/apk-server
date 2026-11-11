# WhatsApp Hub (`com.rafat.whatshub`)

**WhatsApp Hub** is a standalone, local-first Android application built in **Kotlin + Jetpack Compose (Material 3)** with a native **Go `whatsmeow` ARM64 JNI engine (`libwhatshub_engine.so`)** and **SQLCipher / Android Keystore AES-256-GCM encryption**. It manages up to **4 strictly isolated personal WhatsApp companion accounts** simultaneously inside a single unified inbox on Android 10–15 (`minSdk 29`, `targetSdk 35`), optimized for the **Samsung Galaxy S21+ 5G (`SM-G996B/DS`, Exynos, ARM64 `arm64-v8a`, 8 GB RAM, Android 15 / One UI 7)**.

> **Unofficial Protocol & Account Risk Disclosure:** WhatsApp Hub uses the open-source [`go.mau.fi/whatsmeow`](https://github.com/tulir/whatsmeow) library, which speaks WhatsApp Web's multi-device Noise/Signal protocol. It is **not** affiliated with, endorsed by, or supported by WhatsApp or Meta. Automated bulk messaging, spam, or abnormal connection churn can trigger temporary or permanent account restrictions from WhatsApp.

---

## Key Capabilities

1. **4 Strictly Isolated Companion Accounts (`Account 1..4`)**
   - Separate per-slot directories (`files/whatsapp_sessions/account_{1..4}/session.db`), separate Android Keystore-wrapped 256-bit AES-GCM keys, separate `whatsmeow.Client` Noise/WebSocket connections, and separate `account_id`-scoped media folders (`files/media/account_{1..4}/`).
   - Connect via **QR Code Pairing** (rendered on-device via ZXing) or **8-Character Phone Pairing Code** (`Client.PairPhone`).
   - 9 explicit lifecycle states: `Not connected`, `Connecting`, `Waiting for QR scan`, `Pairing`, `Connected`, `Reconnecting`, `Disconnected`, `Authentication expired`, and `Connection error`.
   - Disconnect, Reconnect, Relabel, Logout & Wipe single slot, or Replace an account without affecting the other 3 accounts.

2. **Color-Coded Unified Inbox**
   - Consistent account color identity across cards, badges, avatars, and conversation headers:
     - **Account 1:** `#00C853` (Green)
     - **Account 2:** `#2979FF` (Blue)
     - **Account 3:** `#AA00FF` (Purple)
     - **Account 4:** `#FF6D00` (Orange)
   - Real-time search across contact/group names and message text, plus one-tap filters for `All / Account 1..4`, `Unread`, `Groups`, and `Direct`.

3. **Full Messaging & Media Support**
   - Every chat opens strictly under `(account_id, chat_jid)` with a persistent color header and `"Sending as Account X"` ownership banner above the composer.
   - Multiline text, quoted replies, emoji reactions (`BuildReaction`), delete-for-everyone (`BuildRevoke`), delivery/read ticks (`✓`, `✓✓`), and live typing/online presence indicators.
   - Send and non-blockingly download **Images**, **Videos**, **Audio files**, **Voice Notes (PTT recorded via `MediaRecorder` AAC/M4A + played via `MediaPlayer`)**, **Documents**, and **WebP Stickers**.

4. **History Synchronization & Deduplication**
   - Processes `events.HistorySync` (`INITIAL_BOOTSTRAP`, `RECENT`, `FULL`, `ON_DEMAND`, `PUSH_NAME`) per account, persisting progress in `sync_state` and enforcing composite `PRIMARY KEY (account_id, chat_jid, message_id)` so interrupted syncs or reconnect replays never create duplicate messages.

5. **Android 15 Foreground Reliability & Security**
   - `WhatsAppHubService` (`foregroundServiceType="remoteMessaging|dataSync"`), `ConnectivityManager.NetworkCallback` instant network recovery, `BootCompletedReceiver`, and `WorkManager` `SessionWatchdogWorker`.
   - Zero external servers, zero telemetry, `android:allowBackup="false"`, SQLCipher AES-256 database encryption, Android Keystore AES-256-GCM envelope keys, sanitized diagnostic logging, and user-initiated PBKDF2-HMAC-SHA256 + AES-256-GCM local backups.
   - Full **Arabic (RTL)** and **English (LTR)** localization with **Dark**, **AMOLED Black**, and **Light** Material 3 themes.

---

## Project Layout

```text
WhatsAppHub/
├── app-release.apk                  # Signed ARM64 release APK
├── README.md                        # Overview & quickstart
├── BUILD.md                         # Reproducible Go NDK + Gradle build instructions
├── TESTING.md                       # Executed test results & 15-step 4-account verification plan
├── SECURITY.md                      # Cryptographic design & threat boundaries
├── LIMITATIONS.md                   # Honest protocol, Android 15 / One UI 7, & testing boundaries
├── docs/
│   ├── FEASIBILITY.md               # Phase 1 10-point technical feasibility analysis
│   ├── ARCHITECTURE.md              # End-to-end Go JNI <-> Kotlin SQLCipher architecture
│   └── ci-verification-report.txt   # Automated build, test, badging & apksigner output
├── whatsapp-engine/                 # Go 1.26 whatsmeow multi-account JNI shared library
│   ├── session/                     # Slot manager, AES-256-GCM store wrappers, backoff
│   ├── messaging/                   # Protobuf parser, media upload/download, receipts
│   ├── synchronization/             # HistorySync processor & sync checkpoint emitter
│   ├── events/                      # Bounded ring-buffer event router with account attribution
│   ├── android-bridge/              # JSON control plane & secret sanitizer
│   └── cmd/libwhatshub/             # CGO JNI jbyteArray exports (-buildmode=c-shared)
└── app/                             # Android Kotlin + Jetpack Compose application
    └── src/
        ├── main/java/com/rafat/whatshub/
        │   ├── accounts/            # 4-slot pairing, QR & pairing-code UI + ViewModel
        │   ├── inbox/               # Unified Inbox screen + search/account/type filters
        │   ├── conversations/       # Account-isolated chat screen, media & voice notes
        │   ├── contacts/            # Contacts & Groups directories + ViewModels
        │   ├── settings/            # RTL/LTR, Theme, Battery, Cache, Encrypted Backup
        │   ├── bridge/              # NativeBridge JNI + WhatsAppEngineGateway
        │   ├── database/            # SQLCipher 10-table parameterized persistence
        │   ├── repository/          # Central HubRepository & event ingestion loop
        │   ├── security/            # KeystoreKeyManager, EncryptedBackupManager, SanitizedLogger
        │   └── services/            # WhatsAppHubService, ConnectivityMonitor, Watchdog
        └── test/java/com/rafat/whatshub/
            ├── security/            # Keystore isolation, backup tamper, & log scrubbing tests
            ├── database/            # Robolectric 4-account SQLite isolation & dedup tests
            └── bridge/              # 15-step 4-account integration & pairing-code tests
```
