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

    private fun load() {
        lifecycleScope.launch {
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
                QrCodes.encode(shareCode, 640)?.let { findViewById<ImageView>(R.id.qr).setImageBitmap(it) }
            } else {
                findViewById<TextView>(R.id.share_code).text = getString(R.string.share_code_pending)
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
