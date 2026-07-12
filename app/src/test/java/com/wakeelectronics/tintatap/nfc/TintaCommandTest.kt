package com.wakeelectronics.tintatap.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests for the command layer: opcode + inputs -> the fixed-address EEPROM writes. */
class TintaCommandTest {

    @Test
    fun text_writes_null_terminated_to_text_area() {
        val w = TintaCommands.dataWrites(TintaProtocol.Opcode.TEXT, text = "Hi")
        assertEquals(1, w.size)
        assertEquals(TintaProtocol.TEXT_ADDR, w[0].address)
        assertArrayEquals(byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), 0), w[0].data)
    }

    @Test
    fun text_is_truncated_to_max_len_plus_terminator() {
        val w = TintaCommands.dataWrites(TintaProtocol.Opcode.TEXT, text = "Z".repeat(500))
        assertEquals(TintaProtocol.TEXT_MAX_LEN + 1, w[0].data.size)
        assertEquals(0, w[0].data.last().toInt())
    }

    @Test
    fun bookSeat_writes_trimmed_email_to_text_area() {
        val w = TintaCommands.dataWrites(TintaProtocol.Opcode.BOOK_SEAT, email = "  a@b.co  ")
        assertEquals(TintaProtocol.TEXT_ADDR, w[0].address)
        assertArrayEquals("a@b.co".toByteArray() + 0.toByte(), w[0].data)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bookSeat_without_email_is_rejected() {
        TintaCommands.dataWrites(TintaProtocol.Opcode.BOOK_SEAT, email = "   ")
    }

    @Test
    fun drawImage_writes_pixels_to_image_area() {
        val img = ByteArray(TintaProtocol.IMAGE_DATA_LEN) { 0x5A.toByte() }
        val w = TintaCommands.dataWrites(TintaProtocol.Opcode.DRAW_IMAGE, imageBytes = img)
        assertEquals(TintaProtocol.IMAGE_ADDR, w[0].address)
        assertArrayEquals(img, w[0].data)
    }

    @Test(expected = IllegalArgumentException::class)
    fun drawImage_without_data_is_rejected() {
        TintaCommands.dataWrites(TintaProtocol.Opcode.DRAW_IMAGE, imageBytes = null)
    }

    @Test
    fun page0_and_decision_have_no_data_writes() {
        assertTrue(TintaCommands.dataWrites(TintaProtocol.Opcode.PAGE_0).isEmpty())
        assertTrue(TintaCommands.dataWrites(TintaProtocol.Opcode.DECISION).isEmpty())
    }
}
