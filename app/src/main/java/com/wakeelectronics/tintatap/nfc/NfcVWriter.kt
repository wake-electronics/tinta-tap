package com.wakeelectronics.tintatap.nfc

import java.io.IOException
import kotlin.math.ceil

/**
 * The single I/O boundary: send a raw ISO 15693 request frame, get the raw response.
 * Real use wraps NfcV.transceive; tests pass a fake, so NfcVWriter itself stays pure.
 */
fun interface Iso15693Transceiver {
    fun transceive(request: ByteArray): ByteArray
}

/**
 * ISO 15693 block writer for ST25DV tags. Pure Kotlin — all I/O goes through [transceiver],
 * so the frame assembly and Get System Info parsing are host-testable.
 */
class NfcVWriter(private val uid: ByteArray, private val transceiver: Iso15693Transceiver) {

    data class SystemInfo(val numBlocks: Int, val bytesPerBlock: Int)

    init {
        if (uid.size != 8) throw IOException("Unexpected UID length: ${uid.size}")
    }

    /** ISO 15693 Get System Info (0x2B). Returns the supplied defaults if the tag omits the fields. */
    fun getSystemInfo(defaultBlocks: Int = 128, defaultBytesPerBlock: Int = 4): SystemInfo {
        return try {
            val resp = transceiveChecked(frame(CMD_GET_SYSTEM_INFO, ByteArray(0)))
            if (resp.size < 10) return SystemInfo(defaultBlocks, defaultBytesPerBlock)
            val infoFlags = resp[1].toInt() and 0xFF
            var idx = 2 + 8  // flags(1) + infoFlags(1) + uid(8)
            if (infoFlags and 0x01 != 0) idx += 1 // DSFID
            if (infoFlags and 0x02 != 0) idx += 1 // AFI
            if (infoFlags and 0x04 != 0 && resp.size >= idx + 2) {
                SystemInfo(
                    numBlocks = (resp[idx].toInt() and 0xFF) + 1,
                    bytesPerBlock = (resp[idx + 1].toInt() and 0xFF) + 1
                )
            } else {
                SystemInfo(defaultBlocks, defaultBytesPerBlock)
            }
        } catch (_: Exception) {
            SystemInfo(defaultBlocks, defaultBytesPerBlock)
        }
    }

    /** Writes [payload] across consecutive blocks of [bytesPerBlock] starting at [startBlock]. */
    fun writePayload(startBlock: Int, bytesPerBlock: Int, payload: ByteArray) {
        val blocks = ceil(payload.size / bytesPerBlock.toDouble()).toInt()
        for (i in 0 until blocks) {
            val block = ByteArray(bytesPerBlock)
            val from = i * bytesPerBlock
            val len = minOf(bytesPerBlock, payload.size - from)
            payload.copyInto(block, 0, from, from + len)
            writeSingleBlock(startBlock + i, block)
        }
    }

    private fun writeSingleBlock(block: Int, data: ByteArray) {
        val params = ByteArray(1 + data.size)
        params[0] = block.toByte()
        data.copyInto(params, 1)
        transceiveChecked(frame(CMD_WRITE_SINGLE_BLOCK, params))
    }

    /** Addressed ISO 15693 request frame: flags + command + 8-byte UID + params. */
    private fun frame(command: Int, params: ByteArray): ByteArray {
        val out = ByteArray(2 + 8 + params.size)
        out[0] = FLAGS_ADDRESSED_HIGH_RATE
        out[1] = command.toByte()
        uid.copyInto(out, 2)
        params.copyInto(out, 10)
        return out
    }

    private fun transceiveChecked(cmd: ByteArray): ByteArray {
        val resp = transceiver.transceive(cmd)
        if (resp.isEmpty()) throw IOException("Empty response")
        if (resp[0].toInt() and 0x01 != 0) {  // ISO 15693 error flag
            val code = if (resp.size > 1) resp[1].toInt() and 0xFF else -1
            throw IOException("Tag error: 0x${code.toString(16).padStart(2, '0')}")
        }
        return resp
    }

    private companion object {
        const val CMD_WRITE_SINGLE_BLOCK = 0x21
        const val CMD_GET_SYSTEM_INFO = 0x2B
        const val FLAGS_ADDRESSED_HIGH_RATE = 0x22.toByte()  // addressed (0x20) + high data rate (0x02)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
