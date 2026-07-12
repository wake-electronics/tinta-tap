package com.wakeelectronics.tintatap.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Host tests for the transport layer via a fake transceiver — no NfcV, no device. */
class NfcVWriterTest {

    private val uid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val ok = byteArrayOf(0x00)  // ISO 15693 response, error flag clear

    private class Recorder(val response: (ByteArray) -> ByteArray) : Iso15693Transceiver {
        val sent = mutableListOf<ByteArray>()
        override fun transceive(request: ByteArray): ByteArray {
            sent += request
            return response(request)
        }
    }

    @Test
    fun writePayload_frames_addressed_write_single_block() {
        val rec = Recorder { ok }
        val writer = NfcVWriter(uid, rec)
        // 5 bytes over 4-byte blocks from block 10 -> two frames, second zero-padded.
        writer.writePayload(
            10, 4,
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
        )
        assertEquals(2, rec.sent.size)
        assertArrayEquals(
            byteArrayOf(0x22, 0x21, 1, 2, 3, 4, 5, 6, 7, 8, 0x0A, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            rec.sent[0]
        )
        assertArrayEquals(
            byteArrayOf(0x22, 0x21, 1, 2, 3, 4, 5, 6, 7, 8, 0x0B, 0xEE.toByte(), 0, 0, 0),
            rec.sent[1]
        )
    }

    @Test
    fun getSystemInfo_parses_block_geometry() {
        // infoFlags=0x04 (memory size present); numBlocks byte 0x7F (+1=128), bpb byte 0x03 (+1=4)
        val resp = byteArrayOf(0x00, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0x7F, 0x03)
        val sys = NfcVWriter(uid, Recorder { resp }).getSystemInfo()
        assertEquals(128, sys.numBlocks)
        assertEquals(4, sys.bytesPerBlock)
    }

    @Test
    fun getSystemInfo_falls_back_to_defaults_when_absent() {
        val sys = NfcVWriter(uid, Recorder { byteArrayOf(0x00) })  // too short to carry geometry
            .getSystemInfo(defaultBlocks = 64, defaultBytesPerBlock = 8)
        assertEquals(64, sys.numBlocks)
        assertEquals(8, sys.bytesPerBlock)
    }

    @Test(expected = IOException::class)
    fun tag_error_flag_propagates_on_write() {
        NfcVWriter(uid, Recorder { byteArrayOf(0x01, 0x0F) })  // error flag + code
            .writePayload(0, 4, byteArrayOf(1, 2, 3, 4))
    }

    @Test(expected = IOException::class)
    fun bad_uid_length_is_rejected() {
        NfcVWriter(byteArrayOf(1, 2, 3), Recorder { byteArrayOf(0x00) })
    }
}
