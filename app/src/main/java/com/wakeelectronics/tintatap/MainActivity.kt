package com.wakeelectronics.tintatap

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.appbar.MaterialToolbar
import com.wakeelectronics.tintatap.data.ActionStore
import com.wakeelectronics.tintatap.nfc.TintaProtocol
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var navController: NavController
    private lateinit var resultOverlayView: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        if (::resultOverlayView.isInitialized) resultOverlayView.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = navHost.navController
        setupActionBarWithNavController(navController)

        resultOverlayView = findViewById(R.id.tvResultOverlay)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        observeViewModel()

        if (savedInstanceState == null) {
            // Open on the last-used action, ready to tap.
            val store = ActionStore(this)
            val last = store.lastUsedId
            if (last != null && store.actions().any { it.id == last }) {
                navController.navigate(R.id.action_home_to_detail, bundleOf("actionId" to last))
            }
            handleNfcIntent(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_dev_mode).isVisible = BuildConfig.DEBUG  // dev log: debug builds only
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_dev_mode) {
            navController.navigate(R.id.developerFragment); true
        } else super.onOptionsItemSelected(item)

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp() || super.onSupportNavigateUp()

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { onWriteState(it) } }
                launch { viewModel.log.collect { Log.d("TintaTap", it) } }
            }
        }
    }

    private fun onWriteState(state: WriteState) {
        when (state) {
            WriteState.Idle -> {}
            WriteState.Writing -> hideResultOverlay()
            is WriteState.Success -> {
                val msg = if (state.opcode == TintaProtocol.Opcode.BOOK_SEAT) "Booked!" else "Sent!"
                showResultOverlay(msg, true); haptic(true); viewModel.onResultConsumed()
            }
            WriteState.Failure -> { showResultOverlay("No Success - Try again!", false); haptic(false); viewModel.onResultConsumed() }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this, { tag -> viewModel.onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideOverlay)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED && action != NfcAdapter.ACTION_TAG_DISCOVERED) return
        getTagExtra(intent)?.let { viewModel.onTag(it) }
    }

    private fun getTagExtra(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private fun haptic(ok: Boolean) {
        window.decorView.performHapticFeedback(
            if (ok) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.LONG_PRESS
        )
    }

    private fun showResultOverlay(message: String, ok: Boolean) {
        resultOverlayView.text = message
        resultOverlayView.setBackgroundColor(
            ContextCompat.getColor(this, if (ok) R.color.tinta_green else R.color.tinta_red)
        )
        resultOverlayView.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideOverlay)
        uiHandler.postDelayed(hideOverlay, 3000)
    }

    private fun hideResultOverlay() {
        uiHandler.removeCallbacks(hideOverlay)
        resultOverlayView.visibility = View.GONE
    }
}
