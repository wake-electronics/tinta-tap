# Tinta NFC protocol (single source of truth)

Tinta Tap writes small payloads to a Tinta device's **ST25DV04KC** user EEPROM
over **ISO 15693 (NfcV)**. The device reads them on its next wake — and an NFC
write also triggers an immediate wake on NFC-tier units — then acts, de-duplicating
by nonce.

> **Keep this in sync with the firmware.** The authoritative C definitions live in
> the Tinta firmware repository at `firmware/c/st25_io.h`. This file mirrors them so
> the app and firmware agree on the wire format. Change both together.

## Memory map (ST25 user EEPROM)

| Address | Size | Content |
|---------|------|---------|
| `0x0004` | 4 B | last-processed nonce (dedup marker) |
| `0x0008` | ≤ 232 B | text payload, null-terminated (opcode `TEXT`, and email for `BOOK_SEAT`) |
| `0x0010` | 481 B | image pixel data, 62 × 62, 1-bit, MSB-first, row-major (opcode `DRAW_IMAGE`) |
| last 16 B of tag | 16 B | request header (below) |

The text and image areas are **alternatives** — only the one for the current
opcode is written per tap.

## Request header (16 bytes, little-endian)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 | magic `"INKI"` |
| 4 | 1 | version (= `1`) |
| 5 | 1 | opcode (see below) |
| 6 | 2 | `duration_min` (1–1440) |
| 8 | 4 | `unix_seconds` |
| 12 | 4 | `nonce` (random, for dedup) |

## Opcodes

| Opcode | Name | Meaning |
|--------|------|---------|
| `0x01` | `REFRESH` | immediate refresh (page 0) |
| `0x11` | `PAGE_0` | page 0 (default / seatsurfing) |
| `0x12` | `PAGE_2` / `DECISION` | universal decision maker |
| `0x20` | `TEXT` | display text from EEPROM `0x0008` |
| `0x30` | `DRAW_IMAGE` | display image from EEPROM `0x0010` |
| `0x40` | `BOOK_SEAT` | book a free seat (booking email in the text area) |

## Transport details

- **Write Single Block** = ISO 15693 command `0x21`; flags `0x22`
  (addressed + high data rate). **Get System Info** = `0x2B` (probes block size /
  count; ST25DV04KC = 4-byte blocks).
- The `"INKI"` magic is the wire identifier; it predates the *inki → Tinta* rename
  and **stays** for firmware compatibility. Only change it in a coordinated
  protocol-version bump on both firmware and app.
