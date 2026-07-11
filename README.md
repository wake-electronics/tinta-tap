# Tinta Tap

Android companion app for **Tinta** — the low-power, battery-powered e-paper
signage device.

Tinta Tap uses **NFC (ISO 15693 / NfcV)** to write short commands to a Tinta
device's ST25DV NFC tag **while the device is powered off** (the tag is energised
by the phone's RF field). Subsequently, the Tinta circuit is powered and applies the command: switch
page, show a text message or a hand-drawn image, book a desk, and more.

- **Requires** an Android phone with NFC. iOS is not supported.
- **NFC wire protocol:** see [PROTOCOL.md](PROTOCOL.md) — definition of the employed protocol, kept in sync with the Tinta firmware.
- **License:** Apache-2.0 (see [LICENSE](LICENSE)).

## Status

Early. This repository starts from a working **demonstrator** (a single-screen
NFC writer) and is being grown eventually. 

## Build

Standard Android Studio / Gradle project (Kotlin).

```
./gradlew assembleDebug
```
