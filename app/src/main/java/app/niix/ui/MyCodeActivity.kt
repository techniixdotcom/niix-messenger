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
import app.niix.AppContainer
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
     * Loads the share code, retrying until the onion address exists.
     *
     * The address only appears once Tor has connected and published the onion service, which can
     * take a while after startup. People open this screen precisely to share their code, so keep
     * trying instead of showing a blank QR code. Tied to lifecycleScope, so it stops when the
     * screen goes away.
     */
    private fun load() {
        lifecycleScope.launch {
            while (isActive) {
                val data = withContext(Dispatchers.IO) {
                    val code = container.shareCode()
                    val local = container.crypto.localIdentity(container.accountOnion?.let { OnionAddress.parse(it) })
                    Data(container.conversations.username(), local.fingerprint.displayable, code)
                }
                findViewById<TextView>(R.id.username).text = data.username
                findViewById<TextView>(R.id.fingerprint).text = data.fingerprint
                if (data.shareCode != null) {
                    shareCode = data.shareCode
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

    private data class Data(val username: String, val fingerprint: String, val shareCode: String?)
}

/**
 * What a contact needs to add you: "<account address>|<identity key>". Null until Tor has published
 * this device's address, which can take a while after startup.
 */
internal fun AppContainer.shareCode(): String? {
    crypto.ensureKeysInitialized()
    val onion = accountOnion ?: return null
    return "$onion|" + Base64.encodeToString(crypto.localIdentityKey(), Base64.NO_WRAP)
}
