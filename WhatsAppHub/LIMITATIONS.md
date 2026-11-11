# Known Limitations & Operational Disclosures (`WhatsApp Hub`)

1. **Unofficial Protocol Library (`whatsmeow`) & Account Restriction Risk**
   - WhatsApp Hub connects as a WhatsApp Web / Companion Multi-Device client using `go.mau.fi/whatsmeow`. It is **not** an official Meta/WhatsApp product.
   - WhatsApp may change its wire protocol at any time or restrict/ban accounts that engage in automated bulk messaging, spam, scraping, or suspicious pairing patterns. Use WhatsApp Hub strictly for normal personal/human messaging.

2. **Unsupported Protocol Features (Intentionally Not Claimed)**
   - **Voice & Video Calls:** WhatsApp Web/companion VoIP (`call` stanzas / WebRTC SRTP) is not supported by `whatsmeow` and is not implemented.
   - **Status / Stories Posting & Broadcast List Management:** Creating/editing broadcast lists, WhatsApp Channels administration, and Live Location streaming are not supported.
   - **Cloud Backup (Google Drive):** Intentionally omitted for privacy; local encrypted passphrase backups (`.whbak`) are provided instead.

3. **History Synchronization Boundaries**
   - Companion devices receive only the historical message batches that the primary phone chooses to upload during `HistorySync` (`INITIAL_BOOTSTRAP` and `RECENT`). Years-old messages that the primary phone does not include in its companion history bundle, or expired media blobs no longer hosted on WhatsApp's CDN, cannot be retrieved.
   - Keep the primary phone unlocked and on Wi-Fi for 1–2 minutes immediately after linking a new slot so initial history sync completes cleanly.

4. **WhatsApp 14-Day Primary Phone Activity Requirement**
   - Per WhatsApp's multi-device architecture, if an account's primary phone remains completely offline for more than **14 days**, WhatsApp automatically logs out all linked companion sessions (`Authentication expired`), requiring re-pairing.

5. **Android 15 / Samsung One UI 7 Background Execution**
   - Samsung One UI 7 ("Sleeping apps" / "Deep sleeping apps" / `Freecess`) can freeze background sockets even when a foreground service is active unless the user exempts the app.
   - Open **Settings -> Notifications & Background Execution -> Configure Samsung Battery Optimization** inside WhatsApp Hub and set battery usage to **Unrestricted** on your Galaxy S21+ 5G (`SM-G996B/DS`).

6. **Physical Device Testing Scope**
   - All Go engine unit/race tests (`go test -race ./...`), Kotlin/Robolectric multi-account SQLite & repository integration tests (`gradle testReleaseUnitTest`), ARM64 NDK cross-compilation (`libwhatshub_engine.so`), and APK v2/v3 signing verification were executed in automated Linux CI. Live interactive testing on a physical Samsung Galaxy S21+ 5G with 4 active WhatsApp phone numbers was not available in the automated build environment and should be validated using the 15-step checklist in `TESTING.md`.
