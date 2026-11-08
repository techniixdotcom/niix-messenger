package app.niix.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.R
import app.niix.core.messaging.ConversationManager.LinkResult
import app.niix.core.model.Device
import app.niix.core.model.DeviceList
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/** This account's devices: which ones there are, linking another, and removing one. */
class DevicesActivity : SecureActivity() {

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::confirmLink)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.link_new).setOnClickListener {
            scan.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(getString(R.string.scan_link_prompt))
                    setBeepEnabled(false)
                    setCaptureActivity(PortraitCaptureActivity::class.java)
                },
            )
        }
        findViewById<MaterialButton>(R.id.join_other).setOnClickListener {
            startActivity(android.content.Intent(this, LinkDeviceActivity::class.java))
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            // Only a device that hasn't been used can join another account: joining replaces its
            // identity.
            findViewById<MaterialButton>(R.id.join_other).visibility =
                if (container.conversations.canJoinAccount()) android.view.View.VISIBLE else android.view.View.GONE
            val devices = container.conversations.ownDevices()
            val thisDevice = container.conversations.thisDeviceId()
            val list = findViewById<LinearLayout>(R.id.device_list)
            list.removeAllViews()
            devices.forEach { list.addView(row(it, it.id == thisDevice)) }
        }
    }

    private fun row(device: Device, isThisDevice: Boolean): TextView = TextView(this).apply {
        val name = if (isThisDevice) getString(R.string.devices_this) else getString(R.string.devices_other, device.id)
        text = "$name\n${device.onion.value.take(16)}…"
        setTextColor(themeColor(R.attr.niixOnSurface))
        textSize = 15f
        setPadding(8, 24, 8, 24)
        if (!isThisDevice) setOnClickListener { confirmRemove(device) }
    }

    private fun confirmRemove(device: Device) {
        niixDialog(this)
            .setTitle(R.string.devices_remove_title)
            .setMessage(R.string.devices_remove_body)
            .setPositiveButton(R.string.devices_remove) { _, _ ->
                lifecycleScope.launch {
                    val removed = container.conversations.removeDevice(device.id)
                    if (removed) toast(getString(R.string.devices_removed))
                    load()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    /**
     * Linking hands the other phone this account's key, so it's confirmed first: a code shown by
     * someone else and scanned in the belief it was something harmless would give them the account.
     */
    private fun confirmLink(code: String) {
        niixDialog(this)
            .setTitle(R.string.devices_link_confirm_title)
            .setMessage(R.string.devices_link_confirm_body)
            .setPositiveButton(R.string.devices_link_confirm) { _, _ -> link(code) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun link(code: String) {
        toast(getString(R.string.devices_linking))
        lifecycleScope.launch {
            val message = when (container.conversations.linkDevice(code)) {
                LinkResult.LINKED -> getString(R.string.devices_linked)
                LinkResult.NOT_A_LINK_CODE -> getString(R.string.devices_not_link_code)
                LinkResult.TOO_MANY_DEVICES -> getString(R.string.devices_too_many, DeviceList.MAX_DEVICES)
                LinkResult.UNREACHABLE -> getString(R.string.devices_unreachable)
            }
            toast(message)
            load()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
