package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewMessageActivity : SecureActivity() {

    private lateinit var contactNameEntry: NiixTextEntry
    private lateinit var shareCodeEntry: NiixTextEntry

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { code ->
            shareCodeEntry.text = code
            selectTab(0)
            Toast.makeText(this, getString(R.string.scan_captured), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_message)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        contactNameEntry = NiixTextEntry(this, getString(R.string.hint_contact_name))
        findViewById<FrameLayout>(R.id.contact_name_container).addView(contactNameEntry)

        shareCodeEntry = NiixTextEntry(this, getString(R.string.hint_share_code), multiline = true, allowPaste = true)
        findViewById<FrameLayout>(R.id.share_code_container).addView(shareCodeEntry)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<MaterialButton>(R.id.add_button).setOnClickListener { add() }
        findViewById<MaterialButton>(R.id.scan_button).setOnClickListener { launchScanner() }
        findViewById<MaterialButton>(R.id.my_code_button).setOnClickListener {
            startActivity(Intent(this, MyCodeActivity::class.java))
        }
    }

    private fun selectTab(index: Int) {
        findViewById<TabLayout>(R.id.tabs).getTabAt(index)?.select()
        showPanel(index)
    }

    private fun showPanel(index: Int) {
        findViewById<LinearLayout>(R.id.panel_code).visibility = if (index == 0) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.panel_scan).visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            },
        )
    }

    private fun add() {
        val name = contactNameEntry.text.trim()
        val code = shareCodeEntry.text.trim()
        if (code.isEmpty()) {
            Toast.makeText(this, getString(R.string.add_contact_need_code), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parts = code.split("|", limit = 2)
                    require(parts.size == 2) { "Malformed share code" }
                    val onion = parts[0].trim()
                    val identityKey = Base64.decode(parts[1].trim(), Base64.NO_WRAP)
                    container.conversations.addContactByCode(onion, identityKey, name.ifEmpty { onion.take(8) })
                }
            }
            if (result.isSuccess) {
                Toast.makeText(this@NewMessageActivity, getString(R.string.toast_contact_added), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@NewMessageActivity, getString(R.string.toast_failed, result.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
