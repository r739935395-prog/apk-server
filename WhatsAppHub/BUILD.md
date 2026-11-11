# Build Instructions (`WhatsApp Hub`)

## Prerequisites

- **OS:** Linux x86_64 or macOS
- **JDK:** OpenJDK / Temurin **21**
- **Go:** **1.26.0+** (`go.mau.fi/whatsmeow` requires `go >= 1.26.0` and `CGO_ENABLED=1`)
- **Android SDK:** `platforms;android-35`, `build-tools;35.0.0`
- **Android NDK:** `27.2.12479018` (r27c, provides `aarch64-linux-android29-clang` and 16 KB page alignment support)
- **Gradle:** **8.9**

---

## Step 1: Run Go Engine Unit & Race Tests

```bash
cd WhatsAppHub/whatsapp-engine
go mod tidy
CGO_ENABLED=1 go test -v -race ./...
```

---

## Step 2: Cross-Compile `libwhatshub_engine.so` for Android ARM64 (`arm64-v8a`)

```bash
cd WhatsAppHub/whatsapp-engine

NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT_DIR="../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT_DIR"

GOOS=android \
GOARCH=arm64 \
CGO_ENABLED=1 \
CC="$NDK_BIN/aarch64-linux-android29-clang" \
CXX="$NDK_BIN/aarch64-linux-android29-clang++" \
CGO_CFLAGS="-O2 -DSQLITE_TEMP_STORE=3" \
CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -llog" \
go build -trimpath -buildmode=c-shared \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o "$OUT_DIR/libwhatshub_engine.so" \
  ./cmd/libwhatshub

rm -f "$OUT_DIR/libwhatshub_engine.h"
```

---

## Step 3: Run Android Unit & Integration Tests (JUnit 4 + Robolectric)

```bash
cd WhatsAppHub
gradle testReleaseUnitTest --stacktrace
```

---

## Step 4: Sign & Assemble Release APK (`WhatsAppHub/app-release.apk`)

Never commit signing keys or passwords to source control. Supply them via environment variables at build time:

```bash
keytool -genkeypair \
  -v \
  -keystore /tmp/whatshub-release.jks \
  -alias whatshub \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$WHATSHUB_KEYSTORE_PASSWORD" \
  -keypass "$WHATSHUB_KEY_PASSWORD" \
  -dname "CN=WhatsApp Hub, OU=Mobile Engineering, O=Rafat, L=Ibb, C=YE"

cd WhatsAppHub
WHATSHUB_KEYSTORE_FILE=/tmp/whatshub-release.jks \
WHATSHUB_KEYSTORE_PASSWORD="$WHATSHUB_KEYSTORE_PASSWORD" \
WHATSHUB_KEY_ALIAS=whatshub \
WHATSHUB_KEY_PASSWORD="$WHATSHUB_KEY_PASSWORD" \
gradle assembleRelease --stacktrace

cp app/build/outputs/apk/release/app-release.apk ./app-release.apk
```

---

## Step 5: Verify APK Signature & Manifest Badging

```bash
"$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging WhatsAppHub/app-release.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs WhatsAppHub/app-release.apk
```

---

## Step 6: Install on Samsung Galaxy S21+ 5G (`SM-G996B/DS`)

1. Copy `WhatsAppHub/app-release.apk` to the device or install via ADB:
   ```bash
   adb install -r WhatsAppHub/app-release.apk
   ```
2. Launch **WhatsApp Hub**, grant **Notifications** permission (`POST_NOTIFICATIONS`), and in **Settings -> Notifications & Background Execution**, tap **Configure Samsung Battery Optimization** to set **WhatsApp Hub -> Unrestricted** on One UI 7 for reliable background multi-account connectivity.
