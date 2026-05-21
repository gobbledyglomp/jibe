# Jibe — Android App

The Android companion app for [Jibe](https://github.com/gobbledyglomp/jibe). Discovers the daemon on your LAN via mDNS, pairs with a PIN, and provides clipboard sync, file transfer, notification mirroring, find-my-phone, and a presentation remote.

## Prerequisites

- Android Studio Meerkat or newer
- JDK 17+ (bundled with Android Studio)
- Android SDK — API 36 (installed via SDK Manager)
- A device or emulator running Android 8.0+ (API 26)

## Build — debug

```bash
cd android
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and press **Run**.

## Build — release (signed)

### 1. Generate a keystore (one-time)

```bash
keytool -genkey -v \
  -keystore jibe-release.jks \
  -alias jibe \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

Store the `.jks` file somewhere safe and **never commit it** (it is covered by `.gitignore`).

### 2. Configure signing

Add the following to `android/local.properties` (this file is gitignored):

```properties
signing.storeFile=/absolute/path/to/jibe-release.jks
signing.storePassword=<your-store-password>
signing.keyAlias=jibe
signing.keyPassword=<your-key-password>
```

Alternatively, export environment variables for CI:

```bash
export SIGNING_STORE_FILE=/path/to/jibe-release.jks
export SIGNING_STORE_PASSWORD=...
export SIGNING_KEY_ALIAS=jibe
export SIGNING_KEY_PASSWORD=...
```

### 3. Assemble

```bash
./gradlew assembleRelease
# Signed APK → app/build/outputs/apk/release/app-release.apk
```

The APK is suitable for sideloading and for attaching to a GitHub Release.

### 4. Publish to GitHub (maintainers)

```bash
# from repo root — builds, copies to android/dist/, uploads release
bash android/scripts/publish-release.sh
```

Requires [GitHub CLI](https://cli.github.com/) (`gh auth login`). Release tag: `v0.9.0-pre`, assets: `jibe-0.9.0-pre.apk` and `SHA256SUMS`.

Build only (no upload):

```bash
bash android/scripts/publish-release.sh --build-only
```

## Project structure

```
android/
├── app/
│   ├── build.gradle.kts          # App-level build config, signingConfigs
│   ├── proguard-rules.pro        # App-specific R8/ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/jibe/app/
│           ├── data/
│           │   ├── local/        # DataStore persistence (settings, feature order)
│           │   ├── model/        # Protocol message models
│           │   └── repository/   # ConnectionRepository (state machine + NSD + WS)
│           ├── network/          # JibeWebSocketClient (OkHttp, TLS trust-on-first-use)
│           ├── service/          # JibeService (foreground), RingAlertActivity, RingPlayer
│           └── ui/
│               ├── components/   # Shared composables (JibeSpinner, ReorderState)
│               ├── navigation/   # NavGraph, Route definitions
│               ├── screens/      # HomeScreen, PairingScreen, SettingsScreen, PresentationScreen
│               └── theme/        # Color, Theme
├── gradle/
│   └── libs.versions.toml        # Version catalog
└── local.properties              # SDK path + signing secrets (gitignored)
```

## Running tests

```bash
./gradlew test          # unit tests
./gradlew connectedAndroidTest   # instrumentation tests (device/emulator required)
```
