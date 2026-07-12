package com.wakeelectronics.tintatap.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.wakeelectronics.tintatap.DrawingCanvasView
import com.wakeelectronics.tintatap.MainViewModel
import com.wakeelectronics.tintatap.R
import com.wakeelectronics.tintatap.TapRequest
import com.wakeelectronics.tintatap.data.ActionStore
import com.wakeelectronics.tintatap.model.Action
import com.wakeelectronics.tintatap.model.ActionType

/** The action's detail: per-type inputs + the preview (where it helps) + the tap-cue hero. */
class ActionDetailFragment : Fragment(R.layout.fragment_detail) {

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var store: ActionStore
    private lateinit var action: Action

    private lateinit var etEmail: TextInputEditText
    private lateinit var cgDuration: ChipGroup
    private lateinit var etMessage: TextInputEditText
    private lateinit var tvPreview: TextView
    private lateinit var canvas: DrawingCanvasView
    private lateinit var tvInstantDesc: TextView
    private lateinit var tvPending: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val id = requireArguments().getString("actionId") ?: return
        store = ActionStore(requireContext())
        action = store.actions().firstOrNull { it.id == id } ?: run {
            findNavController().popBackStack(); return
        }
        store.lastUsedId = id
        (requireActivity() as AppCompatActivity).supportActionBar?.title = action.name

        etEmail = view.findViewById(R.id.etEmail)
        cgDuration = view.findViewById(R.id.cgDuration)
        etMessage = view.findViewById(R.id.etMessage)
        tvPreview = view.findViewById(R.id.tvPreview)
        canvas = view.findViewById(R.id.drawingCanvas)
        tvInstantDesc = view.findViewById(R.id.tvInstantDesc)
        tvPending = view.findViewById(R.id.tvPending)

        if (action.placeholder) {
            setupPlaceholder(view)
        } else {
            when (action.type) {
                ActionType.BOOK -> setupBook(view)
                ActionType.MESSAGE -> setupMessage(view)
                ActionType.SKETCH -> setupSketch(view)
                ActionType.PAGE, ActionType.DECISION -> setupInstant(view)
            }
            rearm()
        }
    }

    private fun setupPlaceholder(view: View) {
        view.findViewById<View>(R.id.cardInstant).visibility = View.VISIBLE
        view.findViewById<View>(R.id.cardTap).visibility = View.GONE
        tvInstantDesc.text = when (action.id) {
            "webui" -> "Would open the Tinta's Wi-Fi setup / web interface (like pressing button 3). Not available yet — needs a firmware opcode."
            "properties" -> "Would print the device's properties (from the web interface) to the e-paper. Not available yet — needs a firmware change."
            else -> "Not available yet — needs a Tinta firmware update."
        }
        viewModel.arm(null)
    }

    private fun setupBook(view: View) {
        view.findViewById<View>(R.id.cardBook).visibility = View.VISIBLE
        etEmail.setText(store.bookingEmail)
        cgDuration.check(chipFor(store.bookingDurationMin))
        etEmail.doAfterTextChanged { store.bookingEmail = it?.toString()?.trim().orEmpty(); rearm() }
        cgDuration.setOnCheckedStateChangeListener { _, _ ->
            store.bookingDurationMin = selectedDuration(); rearm()
        }
    }

    private fun setupMessage(view: View) {
        view.findViewById<View>(R.id.cardMessage).visibility = View.VISIBLE
        view.findViewById<View>(R.id.cardPreview).visibility = View.VISIBLE
        val initial = action.presetText.ifEmpty { store.messageText }
        etMessage.setText(initial)
        tvPreview.text = initial
        etMessage.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            if (action.id == "message") store.messageText = text
            tvPreview.text = text
            rearm()
        }
    }

    private fun setupSketch(view: View) {
        view.findViewById<View>(R.id.cardSketch).visibility = View.VISIBLE
        val btnDrawMode = view.findViewById<MaterialButton>(R.id.btnDrawMode)
        val brushes = listOf(
            1 to view.findViewById<MaterialButton>(R.id.btnBrushS),
            2 to view.findViewById<MaterialButton>(R.id.btnBrushM),
            3 to view.findViewById<MaterialButton>(R.id.btnBrushL)
        )
        btnDrawMode.setOnClickListener {
            canvas.drawMode = !canvas.drawMode
            btnDrawMode.text = if (canvas.drawMode) "Draw" else "Erase"
        }
        fun setBrush(radius: Int) {
            canvas.brushRadius = radius
            brushes.forEach { (r, b) -> b.strokeWidth = if (r == radius) 4 else 0 }
        }
        brushes.forEach { (r, b) -> b.setOnClickListener { setBrush(r) } }
        setBrush(2)
        view.findViewById<MaterialButton>(R.id.btnUndo).setOnClickListener { canvas.undo() }
        view.findViewById<MaterialButton>(R.id.btnInvert).setOnClickListener { canvas.invert() }
        view.findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { canvas.clear() }
        canvas.onChange = { rearm() }
    }

    private fun setupInstant(view: View) {
        view.findViewById<View>(R.id.cardInstant).visibility = View.VISIBLE
        tvInstantDesc.text = if (action.type == ActionType.DECISION) {
            "The Tinta shows a random pick on its own screen — it decides, the app can't predict it."
        } else {
            "Shows the Tinta's home screen — the same as pressing its first button."
        }
    }

    private fun rearm() {
        val req = when (action.type) {
            ActionType.BOOK -> TapRequest(action.opcode, email = store.bookingEmail, durationMinutes = store.bookingDurationMin)
            ActionType.MESSAGE -> TapRequest(action.opcode, text = etMessage.text?.toString().orEmpty())
            ActionType.SKETCH -> TapRequest(action.opcode, imageBytes = canvas.pack())
            else -> TapRequest(action.opcode)
        }
        viewModel.arm(req)
        tvPending.text = "Ready · " + pendingLabel()
    }

    private fun pendingLabel(): String = when (action.type) {
        ActionType.BOOK -> "Book " + durationLabel(store.bookingDurationMin)
        ActionType.MESSAGE -> {
            val t = etMessage.text?.toString().orEmpty()
            "Show “" + (if (t.length > 18) t.take(18) + "…" else t) + "”"
        }
        ActionType.SKETCH -> "Send sketch"
        ActionType.PAGE -> "Show Home"
        ActionType.DECISION -> "Roll a decision"
    }

    private fun chipFor(min: Int): Int = when (min) {
        30 -> R.id.chip30
        120 -> R.id.chip120
        1440 -> R.id.chipAllDay
        else -> R.id.chip60
    }

    private fun selectedDuration(): Int = when (cgDuration.checkedChipId) {
        R.id.chip30 -> 30
        R.id.chip120 -> 120
        R.id.chipAllDay -> 1440
        else -> 60
    }

    private fun durationLabel(min: Int): String = when (min) {
        30 -> "30 min"; 120 -> "2 h"; 1440 -> "all day"; else -> "60 min"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.arm(null)
        canvas.onChange = null
    }
}
