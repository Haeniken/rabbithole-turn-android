# Rabbit Hole

[Русский](README.md)

An Android secure-tunnel client based on WireGuard, with TURN/DTLS transport, subscriptions, managed routing, and application updates delivered through GitHub Releases.

The project is developed as a separate application with package name `com.rabbithole`.

## Features

- WireGuard tunnels over multiple parallel TURN/DTLS streams;
- `proxy_v2`, `proxy_v1`, and direct TURN relay transport modes;
- optional WRAP payload protection;
- TURN credential acquisition from a call link;
- automatic CAPTCHA handling with a system notification for manual verification;
- per-user configuration subscriptions, refreshed every 12 hours or manually;
- service-side subscription and connection-key revocation;
- managed direct routing for Russian networks and domains when explicitly requested by a profile;
- automatic initial download and daily refresh of `geoip.dat` and `geosite.dat`;
- profiles that require direct routing do not start if their geodata is absent or invalid;
- connection latency checks;
- grouped application, tunnel, TURN/CAPTCHA, subscription, and routing logs;
- update checks at application startup and on demand in Settings;
- signed APK downloads from GitHub Releases with SHA-256 and Android certificate verification.

## Installation

Stable builds are published on the [Releases](https://github.com/Haeniken/rabbithole-turn-android/releases) page.

Android must allow the application to install APK updates from this source. Every public release must use the same release signing key. A locally installed debug build uses a different certificate and must be removed before installing the first public release.

## Adding a connection

The `+` button on the main screen supports:

1. adding a subscription URL;
2. importing a configuration or archive;
3. scanning a QR code;
4. creating a tunnel manually.

A normal tap selects a connection. A long press opens edit and delete actions.

TURN metadata and connection lifecycle details are documented in [info/TURN_INTEGRATION_DETAILS.md](info/TURN_INTEGRATION_DETAILS.md). A sanitized configuration template is available at [info/config_example.conf](info/config_example.conf).

## Routing geodata

The default sources are the current files published by [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat):

- `geoip.dat` for Russian networks;
- `geosite.dat` for the Russian domain category.

Direct routing is enabled only for profiles whose downloaded configuration requests it. Other subscriptions and manually imported profiles retain normal tunnel routing behavior. Download locations and manual refresh are available in Settings.

Geodata, profiles, subscriptions, and preferences are stored in the application's internal storage and are removed by Android when the application is uninstalled.

## Building

The build requires JDK 17, Go 1.25, Android SDK 36, and Android NDK 29.

```bash
git clone --recurse-submodules https://github.com/Haeniken/rabbithole-turn-android.git
cd rabbithole-turn-android
./gradlew :ui:assembleDebug
```

A local release build without signing variables falls back to the debug key and is intended only for development. Production signing uses:

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Then run:

```bash
./gradlew :ui:assembleRelease
```

## CI and releases

- `Android CI` runs unit tests, lint, and a debug build.
- `Android Release` builds with Gradle, signs with the release key stored in GitHub Secrets, verifies the signature, and uploads the APK and SHA-256 as a downloadable Actions artifact.
- a `v<version>` tag also creates a GitHub Release. The tag must match `wireguardVersionName` in `gradle.properties`.

Required GitHub Secrets:

```text
ANDROID_SIGNING_KEYSTORE_BASE64
ANDROID_SIGNING_KEYSTORE_PASSWORD
ANDROID_SIGNING_KEY_ALIAS
ANDROID_SIGNING_KEY_PASSWORD
```

Never commit a private signing key or its passwords.

## Release checks

```bash
./gradlew :ui:testDebugUnitTest :tunnel:testDebugUnitTest :ui:lintDebug :ui:assembleRelease
```

## Limitations and responsibility

Use TURN transport only with infrastructure that you or the service operator are authorized to access. Follow the terms of the involved platforms and the laws applicable in your jurisdiction.

The project cannot guarantee availability of external APIs, successful CAPTCHA processing, or compatibility with future third-party service changes.

## This project is based on

- [kiper292/wireguard-turn-android](https://github.com/kiper292/wireguard-turn-android) — the original TURN-enabled fork on which later client development was based;
- [WireGuard for Android](https://git.zx2c4.com/wireguard-android) — the original official Android client and userspace backend;
- [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) — the original TURN transport concept;
- [lionheart](https://github.com/jaykaiperson/lionheart) — one source of inherited transport code, retained here for attribution.

Licensing notices for inherited components remain in [COPYING](COPYING), their source files, and submodules.
