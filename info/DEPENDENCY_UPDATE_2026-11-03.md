# Dependency update — 2026-11-03

This update is included in Android 1.2.3. Android remains on compileSdk 36 / AGP 8.13.2.

## Android / JVM

| Dependency | Before | Selected |
|---|---|---|
| androidx-activity-ktx | 1.12.2 | 1.13.0 |
| androidx-annotation | 1.9.1 | 1.11.0 |
| androidx-appcompat | 1.7.1 | 1.8.0 |
| androidx-collection | 1.5.0 | 1.7.0 |
| androidx-constraintlayout | 2.2.1 | 2.2.2 |
| androidx-core-ktx | 1.17.0 | 1.18.0 |
| androidx-datastore-preferences | 1.2.0 | 1.3.0 |
| androidx-fragment-ktx | 1.8.9 | 1.9.1 |
| androidx-lifecycle-runtime-ktx | 2.10.0 | 2.11.0 |
| androidx-webkit | 1.14.0 | 1.18.0 |
| androidx-work-runtime-ktx | 2.11.0 | 2.12.0 |
| google-material | 1.13.0 | 1.14.0 |
| kotlinx-coroutines-android | 1.10.2 | 1.11.0 |
| ktor-io | 3.5.0 | 3.6.1 |
| okio | 3.10.2 | 3.18.2 |
| wire-runtime | 6.4.0 | 6.4.7 |
| kotlin | 2.3.0 | 2.3.21 |

Kotlin stays on the compatible 2.3 line (2.3.21). Core 1.18.0 is the newest selected version compatible with SDK 36; Core 1.19.1 requires SDK 37 and AGP 9.1. The librootkotlinx prerelease stays pinned to the version expected by the vendored VPNHotspot integration. Wire is updated within major 6. AGP 9 migration and Wire 7 migration are outside this test APK.

Version sources: [Google Maven](https://dl.google.com/dl/android/maven2/master-index.xml), [Maven Central](https://repo.maven.apache.org/maven2/), [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html). Requirements were checked in the published AAR metadata as well as by Gradle.

## Go transport

| Module | Before | Selected |
|---|---|---|
| github.com/bogdanfinn/fhttp | v0.6.8 | v0.7.3 |
| github.com/pion/dtls/v3 | v3.0.10 | v3.1.15 |
| github.com/pion/turn/v5 | v5.0.2 | v5.1.3 |
| golang.org/x/crypto | v0.46.0 | v0.58.0 |
| golang.org/x/sys | v0.39.0 | v0.49.0 |
| golang.org/x/net | v0.48.0 | v0.62.0 |
| golang.org/x/text | v0.32.0 | v0.43.0 |
| golang.zx2c4.com/wireguard | v0.0.0-20250521234502-f333402bd9cb | v0.0.0-20261024220952-b3bc704ef0ee |

The new fhttp requires Go 1.27. The module requirement, CI workflows and build documentation are updated together. go.mod/go.sum are normalized with go mod tidy. Unused gorilla/websocket was removed by tidy.

Sources: published module metadata through the [Go module proxy](https://proxy.golang.org/), [fhttp](https://github.com/bogdanfinn/fhttp), [Pion DTLS](https://github.com/pion/dtls), [Pion TURN](https://github.com/pion/turn).

## Validation

- Credential restart/expiry/revocation/quota tests with the Go race detector passed.
- Real Android ARM64 c-shared JNI library compiled with the updated dependencies.
- Fresh anonymous VK credential fetch and full TURN → WRAP → DTLS → Noah health echo succeeded over TCP and UDP with the updated Go dependencies.
- Final Android build passed: 53 UI tests and 17 tunnel tests, zero failures; lint completed with zero errors (823 warnings).
- APK signature verified (v2), package `com.rabbithole.debug`, test version `1.2.3-credentials-test-debug`, minSdk 24, targetSdk 36. Both ARM64 and ARMv7 native libraries contain the new credential-cache and TCP-recovery code.

The cache write policy was subsequently reduced from 48 to 4 saves per warm 16-stream/two-identity cycle. Fetch serialization now supports cancellation while queued and continues to allow exactly one active provider request. Tests cover durable occupancy, revocation, write retry, queued and active cancellation, provider errors and panics, and 32 competing requests. CI and release workflows run these tests with the race detector.
