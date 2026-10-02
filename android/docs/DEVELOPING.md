# Developing uSee

Native Android app: Kotlin, Jetpack Compose, Material 3. No backend.

## Build

Requirements: JDK 17+ (CI uses 21), Android SDK platform 37 and build-tools 37.
Toolchain: Gradle 9.8 (checksum-pinned wrapper), Android Gradle Plugin 9.4 with
built-in Kotlin 2.4, Jetpack Compose BOM 2026.09.

`compileSdk` is 37 (required by AndroidX Core 1.19) while `targetSdk` stays at
36: apps targeting 37 must hold `ACCESS_LOCAL_NETWORK` for LAN sockets, mDNS and
UDP. Raise `targetSdk` only together with requesting that permission and an
on-device test of sensor discovery.

```bash
cd android
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lintRelease
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
python3 tools/upstream_contract.py   # wire contract vs. RuView sources (must exit 0)
```

Release signing reads `android/keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`) or the `USEE_KEYSTORE_FILE`,
`USEE_KEYSTORE_PASSWORD`, `USEE_KEY_ALIAS` and `USEE_KEY_PASSWORD`
environment variables. Without them the release APK is signed with the debug
key, which is fine for testing but not for distribution. Keystores are
git-ignored.

## Layout

```
app/src/main/java/com/usee/scanner/
  core/        pure Kotlin: packet decoders, motion estimator, WiFi math, OUI hints,
               IPv4 helpers, UpstreamContract.kt (generated)
  data/        Android adapters: WifiManager scanner, LAN discovery (mDNS + probes),
               UDP CSI receiver, sensing-server WebSocket client, settings
  ui/          AppViewModel (source arbitration), theme, components, screens
app/src/test/  unit tests for decoders, estimator, WiFi math, server frame parsing
tools/         upstream_contract.py (contract extractor / generator)
```

## Sensor sources

| Source | Discovery | Data |
|---|---|---|
| RuView sensing server | mDNS `_ruview._tcp`, read-only `GET /health` sweep, manual entry | `ws://host:8765/ws/sensing`, optional `Authorization: Bearer` |
| ESP32 node | UDP frames to the phone, read-only `GET :8032/ota/status`, Espressif OUI / `ruview-*` SSID hints | `0xC5110001` CSI, `0xC5110002` vitals, `0xC5110004` fused vitals |
| Phone | always | RSSI of the connected link (~3 Hz) and of scanned APs |

Auto mode prefers server → ESP32 → phone, counting a source as live while it
delivered data within the last 5 s.

## Upstream contract

Ports, paths, packet magics, the mDNS service type and the JSON/struct fields
the app decodes are extracted from the RuView sources by
`tools/upstream_contract.py` into `upstream-contract.lock.json`, and the
constants are generated into `core/UpstreamContract.kt`. Never edit that file
by hand:

```bash
python3 android/tools/upstream_contract.py            # check: 0 ok, 3 auto-updatable, 4 needs review
python3 android/tools/upstream_contract.py --update   # rewrite lock + Kotlin constants
```

A changed packed C struct, a changed CSI frame layout, or a removed field the
app reads returns 4. Update the decoder in `core/CsiPackets.kt` or
`data/SensingServerClient.kt` and its tests, then run `--update`.

## Honesty rules

The motion thresholds in `core/MotionEstimator.kt` are heuristics (CLAIMED,
not MEASURED). Never present WiFi sensing as camera-grade. A built APK or a
JVM render is not device evidence; WiFi behaviour must be checked on a real
phone.
