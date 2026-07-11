package com.wakeelectronics.tintatap

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wakeelectronics.tintatap.nfc.TintaProtocol.Opcode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private data class CommandSpec(
        val buttonId: Int,
        val opcode: Opcode,
        val successMessage: String
    )

    private companion object {
        const val RESULT_OVERLAY_TIMEOUT_MS = 3000L
        const val PREFS_NAME = "TintaPrefs"
        const val PREFS_KEY_BOOKING_EMAIL = "booking_email"
    }

    private val viewModel: MainViewModel by viewModels()

    private val commandSpecs = listOf(
        CommandSpec(R.id.btnCmdPage0, Opcode.PAGE_0, "Page 0 sent!"),
        CommandSpec(R.id.btnCmdDecisionMaker, Opcode.DECISION, "Decision Maker sent!"),
        CommandSpec(R.id.btnCmdTextMessage, Opcode.TEXT, "Text message sent!"),
        CommandSpec(R.id.btnCmdDrawImage, Opcode.DRAW_IMAGE, "Image sent!"),
        CommandSpec(R.id.btnCmdBookSeat, Opcode.BOOK_SEAT, "Booking request sent!")
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

    private var selectedOpcode: Opcode = Opcode.PAGE_0
    private var lastRequest: TapRequest? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideResultOverlayRunnable = Runnable {
        if (::resultOverlayView.isInitialized) resultOverlayView.visibility = View.GONE
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
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_BOOKING_EMAIL, "")?.let { saved ->
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

        findViewById<MaterialButton>(R.id.btnUndo).setOnClickListener { drawingCanvas.undo() }
        findViewById<MaterialButton>(R.id.btnInvertCanvas).setOnClickListener { drawingCanvas.invert() }
        findViewById<MaterialButton>(R.id.btnClearCanvas).setOnClickListener { drawingCanvas.clear() }

        commandToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            commandSpecByButtonId[checkedId]?.let { selectCommand(it.opcode) }
        }

        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener { logView.text = "" }

        val defaultCommand = commandSpecs.first()
        commandToggleGroup.check(defaultCommand.buttonId)
        selectCommand(defaultCommand.opcode)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) log("No NFC adapter found")

        observeViewModel()
        handleNfcIntent(intent)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.log.collect { appendLog(it) } }
                launch { viewModel.state.collect { onWriteState(it) } }
            }
        }
    }

    private fun onWriteState(state: WriteState) {
        when (state) {
            WriteState.Idle -> setCommandSelectionEnabled(true)
            WriteState.Writing -> {
                hideResultOverlay()
                setCommandSelectionEnabled(false)
            }
            is WriteState.Success -> {
                val req = lastRequest
                if (state.opcode == Opcode.BOOK_SEAT && !req?.email.isNullOrEmpty()) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREFS_KEY_BOOKING_EMAIL, req!!.email).apply()
                }
                showResultOverlay(successMessageForOpcode(state.opcode), success = true)
                signalSuccessHaptic()
                setCommandSelectionEnabled(true)
                viewModel.onResultConsumed()
            }
            WriteState.Failure -> {
                showResultOverlay("No Success - Try again!", success = false)
                signalFailureHaptic()
                setCommandSelectionEnabled(true)
                viewModel.onResultConsumed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reader mode makes it easy to test by opening the app first and tapping the tag.
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> handleTag(tag) },
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
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED && action != NfcAdapter.ACTION_TAG_DISCOVERED) return
        getTagExtra(intent)?.let { handleTag(it) }
    }

    /** Reader-mode callbacks arrive on a binder thread; capture UI inputs on the main thread. */
    private fun handleTag(tag: Tag) {
        runOnUiThread {
            val opcode = selectedOpcode
            val text = if (opcode == Opcode.TEXT) textInputEditText.text?.toString().orEmpty() else ""
            val email = if (opcode == Opcode.BOOK_SEAT) bookingEmailEditText.text?.toString()?.trim().orEmpty() else ""
            val image = if (opcode == Opcode.DRAW_IMAGE) {
                try {
                    drawingCanvas.pack()
                } catch (e: Exception) {
                    log("ERROR: canvas pack failed: ${e.message}")
                    null
                }
            } else null
            val request = TapRequest(opcode, text, email, image)
            lastRequest = request
            viewModel.onTag(tag, request)
        }
    }

    private fun selectCommand(opcode: Opcode) {
        selectedOpcode = opcode
        textInputLayout.visibility = if (opcode == Opcode.TEXT) View.VISIBLE else View.GONE
        bookingEmailLayout.visibility = if (opcode == Opcode.BOOK_SEAT) View.VISIBLE else View.GONE
        drawImageLayout.visibility = if (opcode == Opcode.DRAW_IMAGE) View.VISIBLE else View.GONE
    }

    private fun successMessageForOpcode(opcode: Opcode): String =
        commandSpecByOpcode[opcode]?.successMessage ?: "Success"

    private fun setCommandSelectionEnabled(enabled: Boolean) {
        commandToggleGroup.isEnabled = enabled
        commandSpecs.forEach { command ->
            commandToggleGroup.findViewById<MaterialButton>(command.buttonId)?.isEnabled = enabled
        }
        commandToggleGroup.alpha = if (enabled) 1.0f else 0.7f
        textInputEditText.isEnabled = enabled
        bookingEmailEditText.isEnabled = enabled
    }

    private fun signalSuccessHaptic() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun signalFailureHaptic() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun showResultOverlay(message: String, success: Boolean) {
        resultOverlayView.text = message
        resultOverlayView.setBackgroundColor(Color.parseColor(if (success) "#CC1B8B3A" else "#CCB00020"))
        resultOverlayView.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideResultOverlayRunnable)
        uiHandler.postDelayed(hideResultOverlayRunnable, RESULT_OVERLAY_TIMEOUT_MS)
    }

    private fun hideResultOverlay() {
        uiHandler.removeCallbacks(hideResultOverlayRunnable)
        resultOverlayView.visibility = View.GONE
    }

    private fun getTagExtra(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private fun appendLog(msg: String) {
        logView.append(msg)
        logView.append("\n")
    }

    private fun log(msg: String) {
        runOnUiThread { appendLog(msg) }
    }
}
