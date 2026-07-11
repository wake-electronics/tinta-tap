# Tinta Tap

Android companion app for **Tinta** — the low-power, battery-powered e-paper
signage device by [Wake Electronics](https://wake-electronics.com).

Tinta Tap uses **NFC (ISO 15693 / NfcV)** to write small commands to a Tinta
device's ST25DV NFC tag **while the device is powered off** (the tag is energised
by the phone's RF field). On its next wake the device applies the command: switch
page, show a text message or a hand-drawn image, book a desk, and more.

- **Requires** an Android phone with NFC. iOS is not supported (CoreNFC cannot
  write to ISO 15693 tags).
- **NFC wire protocol:** see [PROTOCOL.md](PROTOCOL.md) — the single source of
  truth, kept in sync with the Tinta firmware.
- **License:** Apache-2.0 (see [LICENSE](LICENSE)).

## Status

Early. This repository starts from a working **demonstrator** (a single-screen
NFC writer) and is being grown into a product app. See the project's design notes
for the roadmap.

## Build

Standard Android Studio / Gradle project (Kotlin).

```
./gradlew assembleDebug
```

NFC needs a **real device** (the emulator has no NFC). Copy `local.properties`
with your `sdk.dir`, or let Android Studio create it.

## Distribution

Planned, F-Droid-first: an own F-Droid repository plus submission to f-droid.org,
with a signed GitHub-release APK as a fallback. The app is FOSS-clean (only
AndroidX + Material, no Google Play Services or trackers).
