package com.wakeelectronics.tintatap.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Asserts the wire bytes against PROTOCOL.md. If these fail, the app and firmware have diverged. */
class TintaProtocolTest {

    private val nonce = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    @Test
    fun header_layout_matches_protocol() {
        // duration 60 (0x003C), time 0x11223344, opcode BOOK_SEAT (0x40)
        val h = TintaProtocol.buildRequestHeader(
            opcode = TintaProtocol.Opcode.BOOK_SEAT,
            durationMinutes = 60,
            unixSeconds = 0x11223344,
            nonce = nonce
        )
        val expected = byteArrayOf(
            'I'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), // magic
            0x01,                                                                        // version
            0x40,                                                                        // opcode
            0x3C, 0x00,                                                                  // duration LE
            0x44, 0x33, 0x22, 0x11,                                                      // unixSeconds LE
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()                   // nonce
        )
        assertEquals(16, h.size)
        assertArrayEquals(expected, h)
    }

    @Test
    fun opcode_values_are_stable() {
        assertEquals(0x01, TintaProtocol.Opcode.REFRESH.code)
        assertEquals(0x11, TintaProtocol.Opcode.PAGE_0.code)
        assertEquals(0x12, TintaProtocol.Opcode.DECISION.code)
        assertEquals(0x20, TintaProtocol.Opcode.TEXT.code)
        assertEquals(0x30, TintaProtocol.Opcode.DRAW_IMAGE.code)
        assertEquals(0x40, TintaProtocol.Opcode.BOOK_SEAT.code)
        assertEquals(TintaProtocol.Opcode.TEXT, TintaProtocol.Opcode.fromCode(0x20))
        assertNull(TintaProtocol.Opcode.fromCode(0x99))
    }

    @Test
    fun eeprom_map_matches_protocol() {
        assertEquals(0x0004, TintaProtocol.NONCE_ADDR)
        assertEquals(0x0008, TintaProtocol.TEXT_ADDR)
        assertEquals(0x0010, TintaProtocol.IMAGE_ADDR)
        assertEquals(232, TintaProtocol.TEXT_MAX_LEN)
        assertEquals(63, TintaProtocol.EMAIL_MAX_LEN)
        assertEquals(481, TintaProtocol.IMAGE_DATA_LEN)
    }

    @Test
    fun nullTerminated_appends_terminator_and_truncates() {
        val a = TintaProtocol.nullTerminated("AB", TintaProtocol.TEXT_MAX_LEN)
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 'B'.code.toByte(), 0), a)

        // Truncated to maxLen bytes plus one terminator.
        val long = "X".repeat(300)
        val b = TintaProtocol.nullTerminated(long, TintaProtocol.TEXT_MAX_LEN)
        assertEquals(TintaProtocol.TEXT_MAX_LEN + 1, b.size)
        assertEquals(0, b.last().toInt())
    }

    @Test
    fun nullTerminated_never_splits_a_utf8_character() {
        // "ä" is 2 bytes (0xC3 0xA4). A cut at maxLen=1 must drop the whole char, not half of it.
        val cut = TintaProtocol.nullTerminated("ä", 1)
        assertArrayEquals(byteArrayOf(0), cut)  // partial char removed, terminator only

        // maxLen=2 keeps the full char plus terminator.
        val whole = TintaProtocol.nullTerminated("ä", 2)
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0xA4.toByte(), 0), whole)

        // For every cut through a mixed ASCII/emoji string the payload must stay valid UTF-8:
        // decoding then re-encoding reproduces the exact bytes (a split char would not round-trip),
        // and the result is always a prefix of the input.
        val emoji = "A😀"  // 'A' (1 byte) + U+1F600 (4 bytes) = 5 bytes
        for (max in 1..5) {
            val out = TintaProtocol.nullTerminated(emoji, max)
            val payload = out.copyOf(out.size - 1)  // drop the null terminator
            val decoded = String(payload, Charsets.UTF_8)
            assertArrayEquals("invalid UTF-8 at max=$max", decoded.toByteArray(Charsets.UTF_8), payload)
            assert(emoji.startsWith(decoded)) { "payload is not a prefix of the input at max=$max" }
        }
    }

}
