package com.wakeelectronics.tintatap.nfc

/**
 * Tinta NFC wire protocol — the single Kotlin mirror of PROTOCOL.md / firmware st25_io.h.
 * Pure: no Android dependencies, so every byte here is verified by TintaProtocolTest on the JVM.
 */
object TintaProtocol {

    // "INKI" predates the inki -> Tinta rename; it stays for firmware compatibility (see PROTOCOL.md).
    val MAGIC = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte())
    const val VERSION = 1
    const val HEADER_SIZE = 16

    // ST25DV user-EEPROM byte addresses.
    const val NONCE_ADDR = 0x0004
    const val TEXT_ADDR = 0x0008   // text payload, and booking email
    const val IMAGE_ADDR = 0x0010  // 62x62 1-bit image

    // Payload limits (must match firmware).
    const val TEXT_MAX_LEN = 232
    const val EMAIL_MAX_LEN = 63
    const val IMAGE_DATA_LEN = 481

    enum class Opcode(val code: Int) {
        REFRESH(0x01),
        PAGE_0(0x11),
        DECISION(0x12),
        TEXT(0x20),
        DRAW_IMAGE(0x30),
        BOOK_SEAT(0x40);

        companion object {
            fun fromCode(code: Int): Opcode? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * 16-byte request header, little-endian. [nonce] (4 bytes) and [unixSeconds] are injected
     * rather than generated here, so the layout is deterministic and testable.
     */
    fun buildRequestHeader(
        opcode: Int,
        durationMinutes: Int,
        unixSeconds: Int,
        nonce: ByteArray
    ): ByteArray {
        require(nonce.size == 4) { "nonce must be 4 bytes, was ${nonce.size}" }
        val out = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = (opcode and 0xFF).toByte()
        out[6] = (durationMinutes and 0xFF).toByte()
        out[7] = ((durationMinutes ushr 8) and 0xFF).toByte()
        out[8] = (unixSeconds and 0xFF).toByte()
        out[9] = ((unixSeconds ushr 8) and 0xFF).toByte()
        out[10] = ((unixSeconds ushr 16) and 0xFF).toByte()
        out[11] = ((unixSeconds ushr 24) and 0xFF).toByte()
        nonce.copyInto(out, 12)
        return out
    }

    fun buildRequestHeader(opcode: Opcode, durationMinutes: Int, unixSeconds: Int, nonce: ByteArray): ByteArray =
        buildRequestHeader(opcode.code, durationMinutes, unixSeconds, nonce)

    /** UTF-8 payload truncated to [maxLen] bytes plus a trailing null terminator. */
    fun nullTerminated(text: String, maxLen: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var n = minOf(bytes.size, maxLen)
        // If the cut fell inside a multi-byte character, back off to its start so the
        // firmware never receives a truncated UTF-8 sequence (continuation byte = 10xxxxxx).
        while (n in 1 until bytes.size && (bytes[n].toInt() and 0xC0) == 0x80) n--
        val out = ByteArray(n + 1)  // last byte stays 0 = terminator
        bytes.copyInto(out, 0, 0, n)
        return out
    }
}
