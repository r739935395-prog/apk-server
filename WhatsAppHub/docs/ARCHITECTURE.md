# Architecture (`WhatsApp Hub`)

## 1. High-Level Data & Control Flow

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI (Material 3)                      │
│  UnifiedInboxScreen │ AccountsScreen │ ConversationScreen │ Settings    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ StateFlow<...> + strict (accountId, chatJid)
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         HubRepository (Kotlin)                          │
│  • Validates accountId in 1..4 on every command & incoming event        │
│  • Polls EngineEventEnvelope batches on Dispatchers.IO                  │
│  • Coordinates KeystoreKeyManager, WhatsAppHubDatabase & EngineGateway  │
└──────────────┬───────────────────────────────────────┬──────────────────┘
               │                                       │
               ▼                                       ▼
┌──────────────────────────────┐     ┌────────────────────────────────────┐
│ WhatsAppHubDatabase (Kotlin) │     │ NativeBridge (JNI jbyteArray UTF-8)│
│ • SQLCipher 4.6.1 AES-256    │     │ • Modified-UTF-8 safe emoji/Arabic │
│ • 10 tables, composite PKs   │     └─────────────────┬──────────────────┘
│   (account_id, chat_jid, ..) │                       │ CGO (arm64-v8a)
└──────────────────────────────┘                       ▼
                                     ┌────────────────────────────────────┐
                                     │   libwhatshub_engine.so (Go 1.26)  │
                                     │  session.Manager (Slots 1, 2, 3, 4)│
                                     │  ┌──────┐ ┌──────┐ ┌──────┐ ┌────┐ │
                                     │  │Slot 1│ │Slot 2│ │Slot 3│ │Sl 4│ │
                                     │  └──┬───┘ └──┬───┘ └──┬───┘ └─┬──┘ │
                                     │     ▼        ▼        ▼       ▼    │
                                     │  EncryptedDeviceContainer (AES-GCM)│
                                     │  account_1..4/session.db (SQLite3) │
                                     └────────────────────────────────────┘
```

## 2. Strict 4-Account Isolation Rules

1. **Authentication & Signal Ratchet Isolation (`Go`)**:
   - Each slot `N ∈ {1,2,3,4}` owns its own directory `files/whatsapp_sessions/account_N/session.db` and its own `*whatsmeow.Client`.
   - `KeystoreKeyManager` generates a distinct 32-byte key per slot (`whatshub_master_slot_1..4`) wrapped under Android Keystore hardware-backed AES-256-GCM.
   - `session.OpenEncryptedSlotContainer` seals private Noise/Identity/SignedPreKey/PreKey scalars in `whatshub_sealed_device_keys` and `whatshub_sealed_prekeys`, and wraps `Sessions`, `IdentityKeys`, `SenderKeys`, and `AppStateSyncKeys` with `AES-256-GCM` using AAD `whatshub:acct=<N>:scope=<tableScope>` so ciphertexts copied across accounts fail GCM authentication immediately while preserving `SQLStore.MigratePNToLID` compatibility within the same account.

2. **Database Isolation (`Kotlin / SQLCipher`)**:
   - All 10 tables (`accounts`, `account_sessions`, `chats`, `messages`, `media_files`, `contacts`, `groups`, `group_members`, `sync_state`, `app_settings`) use parameterized SQL queries (`?` placeholders) and composite primary keys prefixed by `account_id`:
     - `chats`: `PRIMARY KEY (account_id, chat_jid)`
     - `messages`: `PRIMARY KEY (account_id, chat_jid, message_id)`
     - `media_files`: `PRIMARY KEY (account_id, chat_jid, message_id)`
     - `contacts`: `PRIMARY KEY (account_id, contact_jid)`
     - `groups`: `PRIMARY KEY (account_id, group_jid)`
     - `group_members`: `PRIMARY KEY (account_id, group_jid, member_jid)`

3. **JNI UTF-8 Safety**:
   - Android's JNI `NewStringUTF` / `GetStringUTFChars` expects Modified UTF-8 and crashes under ART `CheckJNI` when WhatsApp messages or push names contain 4-byte supplementary emojis (`U+10000..U+10FFFF`).
   - Every JNI boundary method in `NativeBridge.kt` and `jni_android.go` exchanges raw UTF-8 `jbyteArray` (`ByteArray`) buffers, guaranteeing lossless, crash-free transport of Arabic script and all Unicode emoji sequences.

4. **Android 15 (`targetSdk 35`) & 16 KB Page Size Readiness**:
   - `libwhatshub_engine.so` is linked with `-Wl,-z,max-page-size=16384`, and `net.zetetic:sqlcipher-android:4.6.1` ships 16 KB-aligned native libraries.
   - `WhatsAppHubService` runs with `foregroundServiceType="remoteMessaging|dataSync"` and pairs with `ConnectivityMonitor` (`NetworkCallback`) and `SessionWatchdogWorker` (`WorkManager`) to recover connections cleanly after Wi-Fi/5G handoffs on One UI 7.
