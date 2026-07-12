package com.wakeelectronics.tintatap.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.wakeelectronics.tintatap.MainViewModel
import com.wakeelectronics.tintatap.R
import com.wakeelectronics.tintatap.TapRequest
import com.wakeelectronics.tintatap.data.ActionStore

/**
 * Stub detail (phase 2): identifies the action, records it as last-used, and arms the
 * ViewModel so a tap fires it. Phase 3 replaces this with per-type inputs + preview.
 */
class ActionDetailFragment : Fragment(R.layout.fragment_detail) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val id = requireArguments().getString("actionId") ?: return
        val store = ActionStore(requireContext())
        val action = store.actions().firstOrNull { it.id == id } ?: run {
            findNavController().popBackStack(); return
        }
        store.lastUsedId = id
        (requireActivity() as AppCompatActivity).supportActionBar?.title = action.name
        view.findViewById<TextView>(R.id.tvDetailName).text = action.name

        viewModel.arm(TapRequest(opcode = action.opcode, text = action.presetText))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.arm(null)
    }
}
