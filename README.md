# LoveSpark (LoveSpouse)

A clean, standalone Android remote for LoveSpouse-type broadcast toys — **functionally
equivalent to the original app, rewritten from scratch**, with none of the account
cruft: **no sign-up, no login, no messaging, no ads/spam, no in-app purchases, no
analytics, no cloud**. It just controls the toy.

Built with Kotlin + Jetpack Compose. The 2.4G RF packet encoder is reimplemented in
pure Kotlin, so there is **no dependency on the vendor's `libble.so`** or any vendor
service.

## What it does

- **9 firmware patterns** — Heartbeat, Wave, Cha-cha, Surging, Dance, Sea wave, Climax,
  Entwine, Leap — shown as a compact 3×3 grid. Each selects the real firmware mode the
  toy plays internally (not an app-side approximation).
- **STOP** to halt the active pattern.
- **Record tab** — hold a 1–9 touch scale to drive intensity live; release to pause.
  Save what you played as a named custom pattern. Custom patterns persist on-device and
  appear next to the presets.

## How it works

The toy is driven over **BLE advertising** ("2.4G" broadcast) — no GATT connection, no
pairing. Each command is a short payload inside the advertisement's manufacturer data
(company id `0xFF`, service `ae8f`, prefix `wbMSE`), encoded as the nRF24-style on-air
packet the toy's receiver expects (preamble `71 0F 55`, bit-reversed address,
CRC16-CCITT `0x1021`/`0xFFFF`, dual LFSR whitening seeds 63 then 37).

- **Firmware patterns** are a single mode byte (`0x01`–`0x09`) held on-air; the toy's
  firmware generates the rhythm.
- **Manual / recorded** control uses the intensity byte (`0x10 | gear`, gear `1`–`9`;
  `0x00` = off).

The pattern→byte map was recovered by decoding the original app's own BLE output
(HCI snoop), so the presets match the originals exactly:

| Pattern   | Byte | Pattern  | Byte | Pattern | Byte |
|-----------|------|----------|------|---------|------|
| Heartbeat | 0x01 | Entwine  | 0x04 | Dance   | 0x07 |
| Wave      | 0x02 | Climax   | 0x05 | Surging | 0x08 |
| Leap      | 0x03 | Sea wave | 0x06 | Cha-cha | 0x09 |

## Requirements

- Android phone that supports **BLE peripheral advertising** (most do).
- A LoveSpouse-type broadcast toy that uses the default `wbMSE` prefix. Private-protocol
  toys use a per-device prefix (from the setup QR); set it via
  `Broadcaster(context, prefix = ...)`.
- Android Studio / JDK 17 to build. compileSdk 34, minSdk 24, Kotlin 1.9.24, AGP 8.5.2.

## Build & install

```bash
# point at your SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant the Bluetooth permissions on first launch (`BLUETOOTH_ADVERTISE` /
`BLUETOOTH_CONNECT`; location only on Android ≤ 11).

## Project layout

```
app/src/main/java/com/example/lovespouse/
  MainActivity.kt        Compose UI: 3×3 pattern grid, STOP, Record tab
  ble/Protocol.kt        UUIDs, prefix, command builders
  ble/Broadcaster.kt     BLE advertising (holds a command on-air)
  ble/Patterns.kt        the 9 firmware presets (name → mode byte)
  ble/CustomPatterns.kt  persistence for recorded patterns
  rf/RfPayloadEncoder.kt pure-Kotlin nRF24 packet encoder
```

A connected-GATT path (`GattController`, `LoveSpouseCrypto`) is also implemented for
toys that expose a GATT server; it is not wired into the current broadcast-only UI.

## Interoperability & legal

This is an independent reimplementation of the wire protocol for **interoperability
with a device you own**. It ships no vendor code or assets and talks to no vendor
servers. "LoveSpouse" and any product names are trademarks of their respective owners;
this project is not affiliated with or endorsed by them.

For personal use with your own hardware. **No warranty — of multiple orgasms,
fitness for any particular purpose, or anything else.** Use at your own risk.

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md) — do whatever you want with it
(use, modify, share, fork), **as long as it's not commercial**.
