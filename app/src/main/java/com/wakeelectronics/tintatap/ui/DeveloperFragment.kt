package com.wakeelectronics.tintatap.ui

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wakeelectronics.tintatap.MainViewModel
import com.wakeelectronics.tintatap.R
import kotlinx.coroutines.launch

/** The raw NFC / ISO 15693 diagnostic log — reachable only via ⋮, and only in debug builds. */
class DeveloperFragment : Fragment(R.layout.fragment_developer) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (requireActivity() as AppCompatActivity).supportActionBar?.title = "Developer mode"
        val tv = view.findViewById<TextView>(R.id.tvLog)
        val scroll = view.findViewById<ScrollView>(R.id.svLog)
        view.findViewById<View>(R.id.btnClearLog).setOnClickListener { viewModel.clearLog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logText.collect {
                    tv.text = it
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
