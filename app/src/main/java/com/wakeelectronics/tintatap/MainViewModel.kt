package com.wakeelectronics.tintatap

import android.nfc.Tag
import android.nfc.tech.NfcV
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wakeelectronics.tintatap.nfc.NfcVWriter
import com.wakeelectronics.tintatap.nfc.TintaCommands
import com.wakeelectronics.tintatap.nfc.TintaProtocol
import com.wakeelectronics.tintatap.nfc.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.math.ceil

sealed interface WriteState {
    data object Idle : WriteState
    data object Writing : WriteState
    data class Success(val opcode: TintaProtocol.Opcode) : WriteState
    data object Failure : WriteState
}

/** UI inputs captured on the main thread at tap time and handed to the ViewModel. */
class TapRequest(
    val opcode: TintaProtocol.Opcode,
    val text: String = "",
    val email: String = "",
    val imageBytes: ByteArray? = null,
    val durationMinutes: Int = 60
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow<WriteState>(WriteState.Idle)
    val state: StateFlow<WriteState> = _state.asStateFlow()

    private val _log = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val log: SharedFlow<String> = _log.asSharedFlow()

    // Explicit post-success cooldown. Reader mode re-discovers the tag immediately after a
    // successful write; a re-fire would carry a fresh nonce that the firmware cannot dedup, so
    // the guard has to live on the client. (Kept from the original; now in one named place.)
    @Volatile private var lastWriteSuccessMs = 0L

    /** The request the active detail screen has prepared; a tap fires this. */
    @Volatile private var pending: TapRequest? = null
    fun arm(request: TapRequest?) { pending = request }

    fun onTag(tag: Tag) {
        val request = pending ?: run { _log.tryEmit("No action selected"); return }
        if (_state.value is WriteState.Writing) {
            _log.tryEmit("Busy: still processing previous tap")
            return
        }
        if (System.currentTimeMillis() - lastWriteSuccessMs < COOLDOWN_MS) return  // debounce, silent as before

        _state.value = WriteState.Writing
        viewModelScope.launch(Dispatchers.IO) {
            val ok = performWrite(tag, request)
            withContext(Dispatchers.Main) {
                if (ok) {
                    lastWriteSuccessMs = System.currentTimeMillis()
                    _state.value = WriteState.Success(request.opcode)
                } else {
                    _state.value = WriteState.Failure
                }
            }
        }
    }

    /** The UI calls this after it has shown a terminal state, returning the machine to Idle. */
    fun onResultConsumed() {
        val s = _state.value
        if (s is WriteState.Success || s is WriteState.Failure) {
            _state.value = WriteState.Idle
        }
    }

    private fun performWrite(tag: Tag, req: TapRequest): Boolean {
        _log.tryEmit("\nTag discovered")
        _log.tryEmit("  techs=${tag.techList.joinToString()}")
        _log.tryEmit("  uid=${tag.id.toHex()} (Tag.getId)")

        val nfcv = NfcV.get(tag)
        if (nfcv == null) {
            _log.tryEmit("No NfcV on this tag")
            return false
        }
        return try {
            nfcv.connect()
            val writer = NfcVWriter(tag.id) { req -> nfcv.transceive(req) }
            val sys = writer.getSystemInfo()
            _log.tryEmit("SystemInfo: numBlocks=${sys.numBlocks} bytesPerBlock=${sys.bytesPerBlock}")
            _log.tryEmit("Command: ${req.opcode.name} (0x${"%02X".format(req.opcode.code)})")

            for (w in TintaCommands.dataWrites(req.opcode, req.text, req.email, req.imageBytes)) {
                val startBlock = w.address / sys.bytesPerBlock
                val blocks = ceil(w.data.size / sys.bytesPerBlock.toDouble()).toInt()
                _log.tryEmit(
                    "Payload: ${w.data.size} bytes @0x${"%04X".format(w.address)} -> blocks $startBlock..${startBlock + blocks - 1}"
                )
                writer.writePayload(startBlock, sys.bytesPerBlock, w.data)
            }

            val nonce = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val unixSeconds = (System.currentTimeMillis() / 1000L).toInt()
            val header = TintaProtocol.buildRequestHeader(req.opcode, req.durationMinutes, unixSeconds, nonce)
            val headerBlocks = ceil(header.size / sys.bytesPerBlock.toDouble()).toInt()
            val headerStart = (sys.numBlocks - headerBlocks).coerceAtLeast(0)
            _log.tryEmit("Request: ${header.toHex()}")
            _log.tryEmit("Target: blocks $headerStart..${headerStart + headerBlocks - 1}")
            writer.writePayload(headerStart, sys.bytesPerBlock, header)
            _log.tryEmit("Write: OK")
            true
        } catch (e: Exception) {
            _log.tryEmit("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            runCatching { nfcv.close() }
        }
    }

    private companion object {
        const val COOLDOWN_MS = 10_000L
    }
}
