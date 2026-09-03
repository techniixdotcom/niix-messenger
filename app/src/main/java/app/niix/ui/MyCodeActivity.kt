package app.niix.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.QrCodes
import app.niix.R
import app.niix.core.model.OnionAddress
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyCodeActivity : SecureActivity() {

    private var shareCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_code)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.copy_button).setOnClickListener { copy() }
        load()
    }

    /**
     * Loads the share code, and keeps retrying until the onion address exists.
     *
     * The address only becomes available once Tor has finished connecting and published the
     * hidden service, which can take a while after the app starts. Loading once meant that
     * opening this screen too early left a blank QR code and a "pending" message that never
     * updated -- the address would arrive moments later and the screen would sit there stale
     * until the user backed out and came in again. Since this is the screen people are on
     * precisely when they want to share their code, waiting silently is the wrong behaviour.
     *
     * The loop is tied to lifecycleScope, so it stops as soon as the screen goes away.
     */
    private fun load() {
        lifecycleScope.launch {
            while (isActive) {
                val data = withContext(Dispatchers.IO) {
                    container.crypto.ensureKeysInitialized()
                    val onion = container.selfOnion
                    val local = container.crypto.localIdentity(onion?.let { OnionAddress.parse(it) })
                    val identityCode = Base64.encodeToString(container.crypto.localIdentityKey(), Base64.NO_WRAP)
                    val username = container.conversations.username()
                    Data(username, local.fingerprint.displayable, onion, identityCode)
                }
                findViewById<TextView>(R.id.username).text = data.username
                findViewById<TextView>(R.id.fingerprint).text = data.fingerprint
                if (data.onion != null) {
                    shareCode = "${data.onion}|${data.identityCode}"
                    findViewById<TextView>(R.id.share_code).text = shareCode
                    QrCodes.encode(shareCode, 1024)?.let { findViewById<ImageView>(R.id.qr).setImageBitmap(it) }
                    return@launch
                }
                findViewById<TextView>(R.id.share_code).text = getString(R.string.share_code_pending)
                delay(1500)
            }
        }
    }

    private fun copy() {
        if (shareCode.isEmpty()) {
            Toast.makeText(this, getString(R.string.share_code_pending), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("niix", shareCode))
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    private data class Data(val username: String, val fingerprint: String, val onion: String?, val identityCode: String)
}
