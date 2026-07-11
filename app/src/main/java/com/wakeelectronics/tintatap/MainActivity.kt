package com.wakeelectronics.tintatap

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException
import java.security.SecureRandom
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private data class CommandSpec(
        val buttonId: Int,
        val opcode: Int,
        val logLabel: String,
        val successMessage: String
    )

    private companion object {
        const val OPCODE_PAGE_0 = 0x11
        const val OPCODE_DECISION_MAKER = 0x12
        const val OPCODE_TEXT_MESSAGE = 0x20
        const val OPCODE_DRAW_IMAGE = 0x30
        const val OPCODE_BOOK_SEAT = 0x40
        const val RESULT_OVERLAY_TIMEOUT_MS = 3000L

        /** ST25DV EEPROM address where text/email payload is written (before request slot). */
        const val TEXT_EEPROM_ADDR = 0x0008
        /** ST25DV EEPROM address where image pixel data is written. */
        const val IMAGE_EEPROM_ADDR = 0x0010
        /** Maximum text length in bytes (must match firmware ST25_TEXT_MAX_LEN). */
        const val TEXT_MAX_LEN = 232
        /** Maximum email length in bytes (must match firmware nfc_booking_email size). */
        const val EMAIL_MAX_LEN = 63

        const val PREFS_NAME = "TintaPrefs"
        const val PREFS_KEY_BOOKING_EMAIL = "booking_email"
    }

    private val commandSpecs = listOf(
        CommandSpec(
            buttonId = R.id.btnCmdPage0,
            opcode = OPCODE_PAGE_0,
            logLabel = "Page 0",
            successMessage = "Page 0 sent!"
        ),
        CommandSpec(
            buttonId = R.id.btnCmdDecisionMaker,
            opcode = OPCODE_DECISION_MAKER,
            logLabel = "Decision Maker",
            successMessage = "Decision Maker sent!"
        ),
        CommandSpec(
            buttonId = R.id.btnCmdTextMessage,
            opcode = OPCODE_TEXT_MESSAGE,
            logLabel = "Text Message",
            successMessage = "Text message sent!"
        ),
        CommandSpec(
            buttonId = R.id.btnCmdDrawImage,
            opcode = OPCODE_DRAW_IMAGE,
            logLabel = "Draw Image",
            successMessage = "Image sent!"
        ),
        CommandSpec(
            buttonId = R.id.btnCmdBookSeat,
            opcode = OPCODE_BOOK_SEAT,
            logLabel = "Book Seat",
            successMessage = "Booking request sent!"
        )
    )
    private val commandSpecByButtonId = commandSpecs.associateBy { it.buttonId }
    private val commandSpecByOpcode = commandSpecs.associateBy { it.opcode }

    private var nfcAdapter: NfcAdapter? = null

    private lateinit var logView: TextView
    private lateinit var commandToggleGroup: MaterialButtonToggleGroup
    private lateinit var resultOverlayView: TextView
    private lateinit var textInputLayout: TextInputLayout
    private lateinit var textInputEditText: TextInputEditText
    private lateinit var bookingEmailLayout: TextInputLayout
    private lateinit var bookingEmailEditText: TextInputEditText
    private lateinit var drawImageLayout: LinearLayout
    private lateinit var drawingCanvas: DrawingCanvasView
    private lateinit var btnDrawModeToggle: MaterialButton
    @Volatile private var selectedOpcode: Int = OPCODE_PAGE_0
    @Volatile private var writeInProgress: Boolean = false
    @Volatile private var lastWriteSuccessMs: Long = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideResultOverlayRunnable = Runnable {
        if (::resultOverlayView.isInitialized) {
            resultOverlayView.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.tvLog)
        commandToggleGroup = findViewById(R.id.toggleCommand)
        resultOverlayView = findViewById(R.id.tvResultOverlay)
        textInputLayout = findViewById(R.id.tilTextMessage)
        textInputEditText = findViewById(R.id.etTextMessage)
        bookingEmailLayout = findViewById(R.id.tilBookingEmail)
        bookingEmailEditText = findViewById(R.id.etBookingEmail)
        drawImageLayout = findViewById(R.id.llDrawImage)
        drawingCanvas = findViewById(R.id.drawingCanvas)
        btnDrawModeToggle = findViewById(R.id.btnDrawModeToggle)

        // Pre-fill email from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREFS_KEY_BOOKING_EMAIL, "")?.let { saved ->
            if (saved.isNotEmpty()) bookingEmailEditText.setText(saved)
        }

        btnDrawModeToggle.setOnClickListener {
            drawingCanvas.drawMode = !drawingCanvas.drawMode
            btnDrawModeToggle.text = if (drawingCanvas.drawMode) "Draw" else "Erase"
        }

        val btnBrushS = findViewById<MaterialButton>(R.id.btnBrushS)
        val btnBrushM = findViewById<MaterialButton>(R.id.btnBrushM)
        val btnBrushL = findViewById<MaterialButton>(R.id.btnBrushL)
        val brushButtons = listOf(1 to btnBrushS, 2 to btnBrushM, 3 to btnBrushL)
        fun updateBrushUI(radius: Int) {
            drawingCanvas.brushRadius = radius
            brushButtons.forEach { (r, btn) -> btn.strokeWidth = if (r == radius) 4 else 0 }
        }
        brushButtons.forEach { (r, btn) -> btn.setOnClickListener { updateBrushUI(r) } }
        updateBrushUI(2)  // default: M

        val btnUndo = findViewById<MaterialButton>(R.id.btnUndo)
        btnUndo.setOnClickListener { drawingCanvas.undo() }

        findViewById<MaterialButton>(R.id.btnInvertCanvas).setOnClickListener {
            drawingCanvas.invert()
        }
        findViewById<MaterialButton>(R.id.btnClearCanvas).setOnClickListener {
            drawingCanvas.clear()
        }

        commandToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            commandSpecByButtonId[checkedId]?.let { command ->
                selectCommand(command.opcode)
            }
        }

        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            logView.text = ""
        }

        val defaultCommand = commandSpecs.first()
        commandToggleGroup.check(defaultCommand.buttonId)
        selectCommand(defaultCommand.opcode)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            log("No NFC adapter found")
        }

        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Reader mode makes it easy to test by opening the app first and tapping the tag.
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> onTagDiscovered(tag) },
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideResultOverlayRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED && action != NfcAdapter.ACTION_TAG_DISCOVERED) {
            return
        }

        val tag = getTagExtra(intent) ?: return
        onTagDiscovered(tag)
    }

    private fun onTagDiscovered(tag: Tag) {
        if (writeInProgress) {
            log("Busy: still processing previous tap")
            return
        }
        // Prevent phantom re-writes: reader mode rediscovers the tag immediately
        // after a successful write, causing a duplicate write with the old opcode.
        val cooldownMs = 10_000L
        if (System.currentTimeMillis() - lastWriteSuccessMs < cooldownMs) {
            return
        }
        writeInProgress = true
        setCommandSelectionEnabled(false)
        hideResultOverlay()

        val uid = tag.id
        log("\nTag discovered")
        log("  techs=${tag.techList.joinToString()}")
        log("  uid=${uid.toHex()} (Tag.getId)")

        val nfcv = NfcV.get(tag)
        if (nfcv == null) {
            log("No NfcV on this tag")
            showResultOverlay("No Success - Try again!", success = false)
            signalFailureHaptic()
            writeInProgress = false
            setCommandSelectionEnabled(true)
            return
        }

        // Capture UI state on the calling thread (NFC write runs on a background thread)
        val opcodeUsed = selectedOpcode
        val textMessage = if (opcodeUsed == OPCODE_TEXT_MESSAGE) {
            textInputEditText.text?.toString()?.take(TEXT_MAX_LEN) ?: ""
        } else {
            ""
        }
        val bookingEmail = if (opcodeUsed == OPCODE_BOOK_SEAT) {
            bookingEmailEditText.text?.toString()?.trim()?.take(EMAIL_MAX_LEN) ?: ""
        } else {
            ""
        }
        val imagePixels = try {
            if (opcodeUsed == OPCODE_DRAW_IMAGE) drawingCanvas.pack() else null
        } catch (e: Exception) {
            log("ERROR: canvas pack failed: ${e.message}")
            showResultOverlay("No Success - Try again!", success = false)
            signalFailureHaptic()
            writeInProgress = false
            setCommandSelectionEnabled(true)
            return
        }

        Thread {
            var writeSucceeded = false
            try {
                nfcv.connect()

                val sys = tryGetSystemInfo(nfcv, uid)
                val bytesPerBlock = sys?.bytesPerBlock ?: 4
                val numBlocks = sys?.numBlocks ?: 128
                log("SystemInfo: numBlocks=$numBlocks bytesPerBlock=$bytesPerBlock")

                log("Command: ${opcodeLabel(opcodeUsed)} (0x${"%02X".format(opcodeUsed)})")

                // For draw image opcode, write pixel data first
                if (opcodeUsed == OPCODE_DRAW_IMAGE && imagePixels != null) {
                    val imageBytes = imagePixels
                    val imageStartBlock = IMAGE_EEPROM_ADDR / bytesPerBlock
                    val imageBlocksNeeded = ceil(imageBytes.size / bytesPerBlock.toDouble()).toInt()
                    log("Image: ${imageBytes.size} bytes → blocks $imageStartBlock..${imageStartBlock + imageBlocksNeeded - 1}")
                    writePayload(nfcv, uid, imageStartBlock, bytesPerBlock, imageBytes)
                    log("Image write: OK")
                }

                // For book seat opcode, write email to text payload area first
                if (opcodeUsed == OPCODE_BOOK_SEAT) {
                    if (bookingEmail.isEmpty()) {
                        log("Book Seat: no email set, aborting")
                        throw IOException("No booking email configured")
                    }
                    val emailBytes = bookingEmail.toByteArray(Charsets.UTF_8)
                    val emailPayload = ByteArray(minOf(emailBytes.size + 1, EMAIL_MAX_LEN + 1))
                    System.arraycopy(emailBytes, 0, emailPayload, 0, minOf(emailBytes.size, EMAIL_MAX_LEN))
                    val emailStartBlock = TEXT_EEPROM_ADDR / bytesPerBlock
                    log("Email: \"$bookingEmail\" (${emailBytes.size} bytes)")
                    writePayload(nfcv, uid, emailStartBlock, bytesPerBlock, emailPayload)
                    log("Email write: OK")
                }

                // For text message opcode, write text payload first
                if (opcodeUsed == OPCODE_TEXT_MESSAGE) {
                    val textBytes = textMessage.toByteArray(Charsets.UTF_8)
                    // Null-terminated text payload (up to TEXT_MAX_LEN bytes)
                    val textPayload = ByteArray(minOf(textBytes.size + 1, TEXT_MAX_LEN + 1))
                    System.arraycopy(textBytes, 0, textPayload, 0, minOf(textBytes.size, TEXT_MAX_LEN))
                    // Last byte is already 0 (null terminator)

                    val textStartBlock = TEXT_EEPROM_ADDR / bytesPerBlock
                    val textBlocksNeeded = ceil(textPayload.size / bytesPerBlock.toDouble()).toInt()

                    log("Text: \"${textMessage.take(40)}${if (textMessage.length > 40) "..." else ""}\" (${textBytes.size} bytes)")
                    log("Text target: blocks $textStartBlock..${textStartBlock + textBlocksNeeded - 1}")

                    writePayload(nfcv, uid, textStartBlock, bytesPerBlock, textPayload)
                    log("Text write: OK")
                }

                // Write INKI request header to last 16 bytes
                val payload = buildBookingRequest(opcode = opcodeUsed, durationMinutes = 60)
                val blocksNeeded = ceil(payload.size / bytesPerBlock.toDouble()).toInt()
                val startBlock = (numBlocks - blocksNeeded).coerceAtLeast(0)

                log("Request: ${payload.toHex()}")
                log("Target: blocks $startBlock..${startBlock + blocksNeeded - 1}")

                writePayload(nfcv, uid, startBlock, bytesPerBlock, payload)
                log("Write: OK")
                writeSucceeded = true
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try {
                    nfcv.close()
                } catch (_: Exception) {
                }
                if (writeSucceeded) {
                    lastWriteSuccessMs = System.currentTimeMillis()
                    if (opcodeUsed == OPCODE_BOOK_SEAT && bookingEmail.isNotEmpty()) {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(PREFS_KEY_BOOKING_EMAIL, bookingEmail).apply()
                    }
                    showResultOverlay(successMessageForOpcode(opcodeUsed), success = true)
                    signalSuccessHaptic()
                } else {
                    showResultOverlay("No Success - Try again!", success = false)
                    signalFailureHaptic()
                }
                writeInProgress = false
                setCommandSelectionEnabled(true)
            }
        }.start()
    }

    private fun writePayload(
        nfcv: NfcV,
        uid: ByteArray,
        startBlock: Int,
        bytesPerBlock: Int,
        payload: ByteArray
    ) {
        val blocksNeeded = ceil(payload.size / bytesPerBlock.toDouble()).toInt()
        for (i in 0 until blocksNeeded) {
            val block = startBlock + i
            val sliceStart = i * bytesPerBlock
            val sliceEnd = minOf((i + 1) * bytesPerBlock, payload.size)
            val blockData = ByteArray(bytesPerBlock)
            System.arraycopy(payload, sliceStart, blockData, 0, sliceEnd - sliceStart)
            writeSingleBlock(nfcv, uid, block, blockData)
        }
    }

    private data class SystemInfo(
        val uid: ByteArray,
        val numBlocks: Int?,
        val bytesPerBlock: Int?
    )

    private fun tryGetSystemInfo(nfcv: NfcV, uid: ByteArray): SystemInfo? {
        // ISO15693 Get System Info (0x2B)
        return try {
            val resp = transceiveChecked(nfcv, iso15693Cmd(uid, 0x2B.toByte(), byteArrayOf()))
            // resp[0]=flags, resp[1]=infoFlags, resp[2..9]=uid, then optional fields
            if (resp.size < 10) return null
            val infoFlags = resp[1].toInt() and 0xFF
            var idx = 2
            val uidFromTag = resp.copyOfRange(idx, idx + 8)
            idx += 8

            if (infoFlags and 0x01 != 0) idx += 1 // DSFID
            if (infoFlags and 0x02 != 0) idx += 1 // AFI

            var numBlocks: Int? = null
            var bytesPerBlock: Int? = null
            if (infoFlags and 0x04 != 0) {
                if (resp.size >= idx + 2) {
                    numBlocks = (resp[idx].toInt() and 0xFF) + 1
                    bytesPerBlock = (resp[idx + 1].toInt() and 0xFF) + 1
                }
                idx += 2
            }

            SystemInfo(uidFromTag, numBlocks, bytesPerBlock)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSingleBlock(nfcv: NfcV, uid: ByteArray, block: Int, data: ByteArray) {
        // ISO15693 Write Single Block (0x21)
        val params = ByteArray(1 + data.size)
        params[0] = block.toByte()
        System.arraycopy(data, 0, params, 1, data.size)
        transceiveChecked(nfcv, iso15693Cmd(uid, 0x21.toByte(), params))
    }

    private fun iso15693Cmd(uid: ByteArray, command: Byte, params: ByteArray): ByteArray {
        // Flags: addressed (0x20) + high data rate (0x02) = 0x22
        if (uid.size != 8) throw IOException("Unexpected UID length: ${uid.size}")

        val flags = 0x22.toByte()
        val out = ByteArray(2 + 8 + params.size)
        out[0] = flags
        out[1] = command
        System.arraycopy(uid, 0, out, 2, 8)
        System.arraycopy(params, 0, out, 10, params.size)
        return out
    }

    private fun transceiveChecked(nfcv: NfcV, cmd: ByteArray): ByteArray {
        val resp = nfcv.transceive(cmd)
        if (resp.isEmpty()) throw IOException("Empty response")

        val flags = resp[0].toInt() and 0xFF
        val isError = (flags and 0x01) != 0
        if (isError) {
            val code = if (resp.size > 1) resp[1].toInt() and 0xFF else -1
            throw IOException("Tag error: 0x${code.toString(16).padStart(2, '0')}")
        }

        return resp
    }

    private fun buildBookingRequest(opcode: Int, durationMinutes: Int): ByteArray {
        val out = ByteArray(16)
        out[0] = 'I'.code.toByte()
        out[1] = 'N'.code.toByte()
        out[2] = 'K'.code.toByte()
        out[3] = 'I'.code.toByte()
        out[4] = 0x01
        out[5] = (opcode and 0xFF).toByte()
        out[6] = (durationMinutes and 0xFF).toByte()
        out[7] = ((durationMinutes ushr 8) and 0xFF).toByte()

        val unixSeconds = (System.currentTimeMillis() / 1000L).toInt()
        out[8] = (unixSeconds and 0xFF).toByte()
        out[9] = ((unixSeconds ushr 8) and 0xFF).toByte()
        out[10] = ((unixSeconds ushr 16) and 0xFF).toByte()
        out[11] = ((unixSeconds ushr 24) and 0xFF).toByte()

        val nonce = ByteArray(4)
        SecureRandom().nextBytes(nonce)
        System.arraycopy(nonce, 0, out, 12, 4)

        return out
    }

    private fun opcodeLabel(opcode: Int): String {
        return commandSpecByOpcode[opcode]?.logLabel ?: "unknown"
    }

    private fun successMessageForOpcode(opcode: Int): String {
        return commandSpecByOpcode[opcode]?.successMessage ?: "Success"
    }

    private fun selectCommand(opcode: Int) {
        selectedOpcode = opcode
        textInputLayout.visibility = if (opcode == OPCODE_TEXT_MESSAGE) View.VISIBLE else View.GONE
        bookingEmailLayout.visibility = if (opcode == OPCODE_BOOK_SEAT) View.VISIBLE else View.GONE
        drawImageLayout.visibility = if (opcode == OPCODE_DRAW_IMAGE) View.VISIBLE else View.GONE
    }

    private fun setCommandSelectionEnabled(enabled: Boolean) {
        runOnUiThread {
            commandToggleGroup.isEnabled = enabled
            commandSpecs.forEach { command ->
                commandToggleGroup.findViewById<MaterialButton>(command.buttonId)?.isEnabled = enabled
            }
            commandToggleGroup.alpha = if (enabled) 1.0f else 0.7f
            textInputEditText.isEnabled = enabled
            bookingEmailEditText.isEnabled = enabled
        }
    }

    private fun signalSuccessHaptic() {
        runOnUiThread {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun signalFailureHaptic() {
        runOnUiThread {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun showResultOverlay(message: String, success: Boolean) {
        runOnUiThread {
            resultOverlayView.text = message
            resultOverlayView.setBackgroundColor(
                Color.parseColor(
                    if (success) "#CC1B8B3A" else "#CCB00020"
                )
            )
            resultOverlayView.visibility = View.VISIBLE
            uiHandler.removeCallbacks(hideResultOverlayRunnable)
            uiHandler.postDelayed(hideResultOverlayRunnable, RESULT_OVERLAY_TIMEOUT_MS)
        }
    }

    private fun hideResultOverlay() {
        runOnUiThread {
            uiHandler.removeCallbacks(hideResultOverlayRunnable)
            resultOverlayView.visibility = View.GONE
        }
    }

    private fun getTagExtra(intent: Intent): Tag? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg)
            logView.append("\n")
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
