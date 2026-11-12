# Rafat Hub — Safe Migration & Upgrade Guide (`v1.0.0` → `v1.1.0` and Future Releases)

## 1. Executive Summary

- **Application**: Rafat Hub (`رافت هب`) — Rafat WhatsApp Hub
- **Package Name**: `com.rafat.whatshub`
- **Previous Release**: `v1.0.0` (`versionCode = 1`, ephemeral CI certificate `SHA-256: 99:72:0A:07:...`)
- **Current Release**: `v1.1.0` (`versionCode = 2`, permanent publisher certificate `CN=Rafat Al-Majidi`, `SHA-256: 55:B8:FA:6F:32:A3:C4:76:4E:73:47:5D:F3:6C:06:2C:08:30:27:C8:B3:B4:8B:61:B7:F2:AC:3B:D0:B4:76:49`)

### Why `v1.0.0` → `v1.1.0` Requires a One-Time Clean Re-Install
Android's OS security model requires every in-place app update (`adb install -r` or tapping an APK update) for `com.rafat.whatshub` to be signed by the **exact same cryptographic private key** as the currently installed APK.

In `v1.0.0`, the CI workflow generated a temporary keystore inside `/tmp` and deleted it at the end of the build. Because that private key no longer exists anywhere, Android `PackageManager` blocks an in-place overwrite of `v1.0.0` (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

With explicit owner authorization, **Rafat Hub v1.1.0** establishes a **permanent RSA-4096 signing identity (`CN=Rafat Al-Majidi`)** stored in encrypted custody vaults outside Git:
- **Transition from `v1.0.0` to `v1.1.0`**: Requires a **one-time** pre-migration check, removal of `v1.0.0`, installation of `v1.1.0`, and re-linking of your WhatsApp account(s).
- **All future updates (`v1.1.0` → `v1.2.0` → `v2.0.0`+)**: Will install **directly in place** over `v1.1.0` with **zero uninstall**, **zero re-pairing**, and automatic non-destructive SQLite schema upgrades (`migrateV1ToV2`+).

---

## 2. Transparent Data Preservation Matrix (`v1.0.0` → `v1.1.0`)

In strict compliance with engineering honesty, the table below distinguishes what can and cannot cross Android's signature/UID boundary from `v1.0.0`, and what `v1.1.0` fixes permanently:

| Data Category | Behavior from `v1.0.0` (`99:72:0A...`) | Behavior in `v1.1.0+` (`55:B8:FA...`) |
|---|---|---|
| **WhatsApp Conversations & Recent Message History** | **Preserved on your primary WhatsApp phone(s)** and **automatically re-synced** into `v1.1.0` via WhatsApp encrypted `HistorySync` upon re-linking each account. | Stored in SQLCipher v2 **and** exportable via portable `WHUBBK02` encrypted backups across devices/installs. |
| **Contacts & Group Memberships** | **Automatically re-synced** from WhatsApp upon re-linking each account in `v1.1.0` (now with full `@lid` ↔ `@s.whatsapp.net` canonical merging). | Stored locally + included in `WHUBBK02` portable encrypted backups. |
| **Linked Companion Cryptographic Sessions (`whatsmeow` Signal/Noise keys)** | Bound to `v1.0.0`'s hardware `AndroidKeyStore` UID; **cannot** cross signature boundaries. Each active account must be re-linked once via QR or 8-character Phone Code. | Persists seamlessly across all future `v1.1.x` / `v1.2.x` in-place updates. Never exported in cleartext backups for security. |
| **Legacy `v1.0.0` `.whub` Backup Files (`WHUBBK01`)** | `v1.0.0` wrapped raw SQLCipher bytes whose 256-bit passphrase lived exclusively inside `v1.0.0`'s hardware `AndroidKeyStore`. `v1.1.0` detects `WHUBBK01` safely without crashing or corrupting data and guides you to `HistorySync`. | Upgraded to **`WHUBBK02`** (`PBKDF2-HMAC-SHA256` 210,000 iterations + `AES-256-GCM` structured JSON), fully restorable on any device using **only** your passphrase. |
| **Media Opened/Saved Externally on Phone Storage** | Any photos, videos, or PDFs already saved to `Downloads` / `Pictures` remain untouched. Media stored only inside `v1.0.0` private cache should be shared/saved before uninstalling `v1.0.0` if not still downloadable from WhatsApp. | Cached per account + re-downloadable from WhatsApp CDN while media keys remain valid. |

---

## 3. Step-by-Step Safe Migration Procedure (`v1.0.0` → `v1.1.0`)

Follow these exact steps **before** uninstalling `v1.0.0`:

### Step 1 — Record Your Account Slot Labels & Save Any Private Attachments in `v1.0.0`
1. Open your existing **WhatsApp Hub v1.0.0** app.
2. Note which WhatsApp numbers you assigned to **Account 1**, **Account 2**, **Account 3**, and **Account 4**, plus any custom slot names (e.g., *Personal*, *Business*, *Support*).
3. Check that you do not have any unsent text typed in a conversation box.
4. If you downloaded an old document or voice note in `v1.0.0` that was deleted by the sender on WhatsApp, tap **Open** on that file and save a copy to your phone's `Downloads` folder.

### Step 2 — Unlink the Old `v1.0.0` Companion Entry on Your Primary Phone(s)
WhatsApp allows up to **4 linked companion devices** per phone number. Cleaning up the old `v1.0.0` companion slot prevents stale ghost sessions:
1. On each primary phone, open **official WhatsApp** → **Settings** (`⋮`) → **Linked devices**.
2. Tap the old `WhatsApp Hub` (`v1.0.0`) linked device entry and tap **Log out**.

### Step 3 — Uninstall `v1.0.0` and Install **Rafat Hub `v1.1.0`**
1. On your Samsung Galaxy S23 Ultra (or target Android arm64 device), uninstall the old `v1.0.0` app.
2. Install **`Rafat-Hub-v1.1.0-arm64-release.apk`**.
3. Open **Rafat Hub** (`رافت هب`) and grant **Notifications** permission when prompted.
4. Go to **Settings** → **Configure Battery Unrestricted (One UI 7)** and set Rafat Hub to **Unrestricted** so Samsung One UI 7 keeps your multi-account WebSocket connections alive in the background.

### Step 4 — Re-Link Accounts 1–4 & Let `HistorySync` Populate Automatically
1. Open the **Accounts** tab in Rafat Hub `v1.1.0`.
2. Rename each slot (`Account 1..4`) to your preferred label.
3. Link each account using either:
   - **Connect / Pair QR** (scan from your primary phone → Official WhatsApp → Linked Devices → Link a Device), or
   - **Pair with Phone Code** (enter your number with country code, e.g. `967771234567`, and enter the 8-character code in Official WhatsApp → *Link with phone number instead*).
4. Keep Rafat Hub open for **30–90 seconds** after pairing while WhatsApp's `HistorySync` imports your conversations, contacts, groups, and recent messages and resolves `@lid` ↔ `@s.whatsapp.net` identities.

### Step 5 — Create Your First Portable `WHUBBK02` Encrypted Backup
1. Go to **Settings** → **Portable Encrypted Backup & Migration** → **Export Portable Backup**.
2. Enter a strong passphrase (8+ characters) and save `rafat_hub_v1_1_backup.whub` to a safe folder.
3. Unlike `v1.0.0`, this `WHUBBK02` archive is **100% portable** and can be restored onto any clean installation or replacement phone using your passphrase.
