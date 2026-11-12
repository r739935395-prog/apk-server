# Rafat Hub — Release Signing & Permanent Key Custody Architecture

## 1. Permanent Signing Identity (Public Metadata Only)

All official releases of **Rafat Hub** (`com.rafat.whatshub`) starting with **`v1.1.0` (`versionCode = 2`)** are signed with a single permanent cryptographic identity:

| Property | Value |
|---|---|
| **Application ID** | `com.rafat.whatshub` |
| **Keystore Format** | `PKCS12` (`.p12`) |
| **Key Alias** | `rafat_hub_release` |
| **Subject Distinguished Name** | `CN=Rafat Al-Majidi` |
| **Key Algorithm & Strength** | `RSA` `4096`-bit (`SHA256withRSA`) |
| **Certificate Serial Number** | `df986be9b92ae2ab` |
| **Validity Window** | `2026-11-12 08:26:57 UTC` → `2054-03-30 08:26:57 UTC` (`10,000` days / ~27.4 years) |
| **Certificate SHA-256** | `55:B8:FA:6F:32:A3:C4:76:4E:73:47:5D:F3:6C:06:2C:08:30:27:C8:B3:B4:8B:61:B7:F2:AC:3B:D0:B4:76:49` |
| **Certificate SHA-1** | `1F:78:83:DF:EB:58:C2:3E:90:98:9B:29:18:B7:75:A2:7A:CC:F6:31` |
| **APK Signature Schemes** | APK Signature Scheme **v2** + **v3** enabled (16 KB page-size aligned) |

> **CRITICAL RULE:** Never regenerate or replace this signing key. Every future release (`v1.1.1`, `v1.2.0`, `v2.0.0`, …) MUST be signed with this exact `rafat_hub_release` key so Android upgrades Rafat Hub seamlessly in place without uninstalling user data or breaking paired WhatsApp sessions.

---

## 2. Encrypted Vault Custody & Offline Backup Locations

No keystore, private key, or password is ever stored inside the Git repository (enforced by `.gitignore`, `WhatsAppHub/.gitignore`, and the CI **Security Gate** step in `.github/workflows/build-whatsapphub.yml`).

Outside the Git repository, the permanent signing key is sealed inside two independent encrypted vaults under `/home/user/rafat-hub-signing-vault/` (`chmod 700`):

1. **Primary Encrypted Signing Vault**:
   - Path: `/home/user/rafat-hub-signing-vault/primary/rafat-hub-signing-primary.vault.enc`
   - Encryption: `PBKDF2-HMAC-SHA256` (600,000 iterations) + `AES-256-GCM` authenticated encryption
   - Vault SHA-256: `6a9380c996ecbd579974a31c4b92b0288e79f651b178929550e64d983224cbb5`
2. **Independent Offline Encrypted Backup Archive**:
   - Path: `/home/user/rafat-hub-signing-vault/offline-backup/rafat-hub-signing-offline-backup.tar.enc`
   - Companion Integrity Tag: `/home/user/rafat-hub-signing-vault/offline-backup/rafat-hub-signing-offline-backup.tar.enc.hmac`
   - Encryption: OpenSSL `AES-256-CBC` (`-pbkdf2 -iter 600000`) + independent `HMAC-SHA256`
   - Offline Archive SHA-256: `4c50f18e51db501344bcf237eb4cd1850645f47dfd10b9b0f68a17616999990e`
3. **Owner Private Credentials File (Strictly Outside Git)**:
   - Path: `/home/user/rafat-hub-signing-vault/OWNER_PRIVATE_CREDENTIALS.txt` (`chmod 600`)
   - **Recommended Owner Action**: Copy the entire `/home/user/rafat-hub-signing-vault/` directory onto TWO separate offline media owned exclusively by you (e.g., an encrypted USB hardware drive and a password manager such as KeePassXC / Bitwarden / 1Password).

---

## 3. Configuring GitHub Actions Secrets for Automated Signed Releases

`.github/workflows/build-whatsapphub.yml` and `WhatsAppHub/app/build.gradle.kts` are pre-wired to sign release builds in ephemeral RAM (`/dev/shm`) whenever the following 4 repository secrets are configured in **GitHub → Settings → Secrets and variables → Actions**:

| GitHub Actions Secret Name | Value Source |
|---|---|
| `RAFAT_HUB_KEYSTORE_BASE64` | Base64 single-line encoding of `rafat-hub-release.p12` (unlocked via `unlock_signing_vault.py`) |
| `RAFAT_HUB_KEYSTORE_PASSWORD` | Keystore password from `OWNER_PRIVATE_CREDENTIALS.txt` |
| `RAFAT_HUB_KEY_ALIAS` | `rafat_hub_release` |
| `RAFAT_HUB_KEY_PASSWORD` | Key password from `OWNER_PRIVATE_CREDENTIALS.txt` |

### How CI Protects the Signing Key
1. If the 4 secrets are present, CI decodes the `.p12` into `/dev/shm/rafat-hub-ci-keystore.p12` (`chmod 600`) with `trap 'rm -f "$KS_RAM"' EXIT`.
2. After signing `Rafat-Hub-v1.1.0-arm64-release.apk`, CI runs `apksigner verify --print-certs` and asserts that the APK's SHA-256 certificate matches `55:B8:FA:6F:32:A3:C4:76:4E:73:47:5D:F3:6C:06:2C:08:30:27:C8:B3:B4:8B:61:B7:F2:AC:3B:D0:B4:76:49`.
3. If secrets are not yet configured, CI **never** generates an ephemeral keystore — it outputs a 16 KB page-aligned unsigned APK (`Rafat-Hub-v1.1.0-arm64-unsigned-aligned.apk`) ready to be signed offline via `/home/user/rafat-hub-signing-vault/sign_apk_from_vault.py`.

---

## 4. Verifying Any Rafat Hub APK Signature

Run `apksigner` from Android SDK Build-Tools 35+:

```bash
apksigner verify --verbose --print-certs Rafat-Hub-v1.1.0-arm64-release.apk
```

Confirm the output displays:
- `Verified using v2 scheme (APK Signature Scheme v2): true`
- `Verified using v3 scheme (APK Signature Scheme v3): true`
- `Signer #1 certificate DN: CN=Rafat Al-Majidi`
- `Signer #1 certificate SHA-256 digest: 55b8fa6f32a3c4764e73475df36c062c083027c8b3b48b61b7f2ac3bd0b47649`
