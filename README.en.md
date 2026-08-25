# Rabbit Hole

> [!CAUTION]
> **This project is intended for research, testing, and administration of authorized infrastructure.** Use it only with networks, systems, accounts, and traffic that you own or for which the owner has granted explicit prior permission.

## Lawful use, limitations, and responsibility

This is general-purpose software. It is not intended for unauthorized access to computer information, interference with third-party systems, interception or modification of third-party traffic, use of third-party credentials, or access to resources without a lawful basis. Do not use it to distribute prohibited information or provide services to third parties without the required rights, permissions, and regulatory compliance.

Before using, modifying, or distributing the project, users must independently verify that their specific scenario is lawful, properly authorized, and compliant with applicable law, infrastructure owners' rights, and third-party platform terms. If the legal basis or scope of authorization is unclear, stop using the software until qualified legal advice is obtained.

To the extent permitted by applicable law, the software is provided “as is,” without warranties of fitness for a particular purpose, uninterrupted operation, or data preservation. Nothing in this section excludes or limits liability where such exclusion or limitation is prohibited by law.

The project cannot guarantee availability of external APIs, successful CAPTCHA processing, or compatibility with future third-party service changes.

[Русский](README.md)

An Android secure-tunnel client based on WireGuard, with TURN/DTLS transport, subscriptions, managed routing, and application updates delivered through GitHub Releases.

The project is developed as a separate application with package name `com.rabbithole`.

## Features

- WireGuard tunnels over multiple parallel TURN/DTLS streams;
- an adaptive stream pool that starts at full capacity, returns to four streams
  after prolonged inactivity, and grows gradually when traffic resumes;
- stream selection informed by queue pressure, RTT, loss, and temporary error penalties;
- bounded WireGuard packet reordering and stable packet stripes without changing
  the WireGuard packet format;
- make-before-break handover between Wi-Fi and cellular networks;
- `proxy_v2`, `proxy_v1`, and direct TURN relay transport modes;
- TURN/UDP enabled by default with automatic fallback to compatible TURN/TCP;
- optional WRAP payload protection;
- TURN credential acquisition from a call link;
- automatic CAPTCHA handling with a persistent browser profile and a system
  notification for manual verification;
- per-user configuration subscriptions, refreshed every 12 hours or manually;
- service-side subscription and connection-key revocation;
- managed direct routing for Russian networks and domains when explicitly requested by a profile;
- automatic initial download and daily refresh of `geoip.dat` and `geosite.dat`;
- profiles that require direct routing do not start if their geodata is absent or invalid;
- AdGuard DNS (`94.140.14.14`, `94.140.15.15`), enabled by default and
  controlled by a Routing checkbox;
- sharing the active VPN over Wi-Fi, USB, Bluetooth, and Ethernet, with
  transparent root routing or a local HTTP/SOCKS5 proxy without root;
- connection latency checks;
- grouped application, tunnel, TURN/CAPTCHA, subscription, and routing logs;
- opt-in detailed diagnostics for queues, socket writes, packet loss and
  reordering, and the selected TURN transport;
- update checks at application startup and on demand in Settings;
- signed APK downloads from GitHub Releases with SHA-256 and Android certificate verification.

## Installation

Stable builds are published on the [Releases](https://github.com/Haeniken/rabbithole-turn-android/releases) page.
Permanent direct link to the current APK: [rabbit-hole-latest.apk](https://github.com/Haeniken/rabbithole-turn-android/releases/latest/download/rabbit-hole-latest.apk).

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

## VPN sharing

The VPN sharing page follows Routing in Settings. With root, the application
uses the pinned VPNHotspot submodule, temporarily disables tethering hardware
offload, and transparently routes downstream interfaces through the active VPN.
It restores the previous offload setting and removes its deterministic firewall
chains on stop. Downstream IPv6 is blocked, and TCP MSS is clamped to the VPN
path MTU.

Without root, an HTTP/SOCKS5 proxy accepts clients only from an active tethering
subnet. The Windows 11 system proxy covers browsers, but not RDP or applications
that ignore proxy settings. The application therefore includes a ready-to-use
official sing-box TUN configuration for all traffic, with `strict_route`, MTU
1280, and the phone address excluded to avoid a route loop. AdGuard UDP/53
travels through SOCKS5 inside the VPN.

See [info/VPN_SHARING.md](info/VPN_SHARING.md) for implementation details,
limitations, and cleanup behavior.

## Building

The build requires JDK 17, Go 1.25, Android SDK 36, Android NDK 29, stable Rust
with the `aarch64-linux-android` and `armv7-linux-androideabi` targets, and
`cargo-ndk` 4.1.2.

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

## This project is based on

- [kiper292/wireguard-turn-android](https://github.com/kiper292/wireguard-turn-android) — the original TURN-enabled fork on which later client development was based;
- [WireGuard for Android](https://git.zx2c4.com/wireguard-android) — the original official Android client and userspace backend;
- [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) — the original TURN transport concept;
- [lionheart](https://github.com/jaykaiperson/lionheart) — one source of inherited transport code, retained here for attribution.
- [WINGSV](https://github.com/WINGS-N/WINGSV) and the pinned
  [VPNHotspot](https://github.com/WINGS-N/VPNHotspot) submodule — the root
  daemon and transparent VPN-sharing design.

Licensing notices for inherited components remain in [COPYING](COPYING), their source files, and submodules.
