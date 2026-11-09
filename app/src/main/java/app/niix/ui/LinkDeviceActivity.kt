package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import app.niix.ConnectivityService
import app.niix.QrCodes
import app.niix.R
import app.niix.core.storage.SettingsStore
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown on a new device that's joining an existing account: a QR code for one of the account's
 * devices to scan. The code holds this device's address and a one-time secret; whoever scans it
 * sends the account over Tor, sealed with that secret.
 */
class LinkDeviceActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link_device)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        ConnectivityService.start(this)
        showInvite()
        lifecycleScope.launch {
            container.conversations.linked.collect {
                container.refreshAccountOnion()
                Toast.makeText(this@LinkDeviceActivity, getString(R.string.link_done), Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this@LinkDeviceActivity, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                finish()
            }
        }
    }

    /**
     * Backing out means using this device on its own for now. It can still be linked later from
     * Settings › Security › Linked devices, as long as it hasn't been used.
     */
    private fun leave() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.storage.settings.remove(SettingsStore.KEY_LINK_PENDING) }
            startActivity(Intent(this@LinkDeviceActivity, HomeActivity::class.java))
            finish()
        }
    }

    /** The invite needs this device's address, which only exists once Tor is up. */
    private fun showInvite() {
        lifecycleScope.launch {
            while (isActive) {
                val invite = container.conversations.startLinkInvite()
                if (invite != null) {
                    QrCodes.encode(invite.encode(), 1024)?.let { qr ->
                        findViewById<ImageView>(R.id.qr).apply {
                            setImageBitmap(qr)
                            visibility = View.VISIBLE
                        }
                    }
                    findViewById<TextView>(R.id.status).setText(R.string.link_waiting_scan)
                    return@launch
                }
                delay(1500)
            }
        }
    }

    override fun onDestroy() {
        container.conversations.cancelLinkInvite()
        super.onDestroy()
    }
}
