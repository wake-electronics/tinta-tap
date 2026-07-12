package com.wakeelectronics.tintatap.nfc

import com.wakeelectronics.tintatap.nfc.TintaProtocol.EMAIL_MAX_LEN
import com.wakeelectronics.tintatap.nfc.TintaProtocol.IMAGE_ADDR
import com.wakeelectronics.tintatap.nfc.TintaProtocol.Opcode
import com.wakeelectronics.tintatap.nfc.TintaProtocol.TEXT_ADDR
import com.wakeelectronics.tintatap.nfc.TintaProtocol.TEXT_MAX_LEN

/** One payload segment to write at a fixed ST25 EEPROM byte address. */
class EepromWrite(val address: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EepromWrite && address == other.address && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * address + data.contentHashCode()

    override fun toString(): String = "EepromWrite(0x%04X, ${data.size} bytes)".format(address)
}

/**
 * Turns an opcode + user inputs into the fixed-address payload writes for a tap.
 * The 16-byte request header is written separately (at the tag tail, sized from the tag geometry).
 */
object TintaCommands {

    fun dataWrites(
        opcode: Opcode,
        text: String = "",
        email: String = "",
        imageBytes: ByteArray? = null
    ): List<EepromWrite> = when (opcode) {
        Opcode.TEXT ->
            listOf(EepromWrite(TEXT_ADDR, TintaProtocol.nullTerminated(text, TEXT_MAX_LEN)))

        Opcode.BOOK_SEAT -> {
            val addr = email.trim()
            require(addr.isNotEmpty()) { "No booking email configured" }
            listOf(EepromWrite(TEXT_ADDR, TintaProtocol.nullTerminated(addr, EMAIL_MAX_LEN)))
        }

        Opcode.DRAW_IMAGE -> {
            val img = requireNotNull(imageBytes) { "No image data" }
            listOf(EepromWrite(IMAGE_ADDR, img))
        }

        else -> emptyList()
    }
}
