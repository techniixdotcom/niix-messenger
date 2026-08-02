package app.niix.ui

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.niix.CalculatorMemory
import app.niix.ConnectivityService
import app.niix.R
import app.niix.core.storage.SettingsStore
import com.google.android.material.appbar.MaterialToolbar
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : SecureActivity() {

    private val pickProfile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onProfilePicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val allowlist = findViewById<MaterialSwitch>(R.id.switch_allowlist)
        val privacy = findViewById<MaterialSwitch>(R.id.switch_notif_privacy)
        allowlist.isChecked = container.storage.settings.getBool(SettingsStore.KEY_ALLOWLIST_ONLY, false)
        privacy.isChecked = container.storage.settings.getBool(SettingsStore.KEY_NOTIFICATION_PRIVACY, true)

        allowlist.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_ALLOWLIST_ONLY, checked)
        }
        privacy.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_NOTIFICATION_PRIVACY, checked)
            ConnectivityService.start(this)
        }

        val screenshots = findViewById<MaterialSwitch>(R.id.switch_screenshots)
        screenshots.isChecked = container.storage.settings.getBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, false)
        screenshots.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, checked)
            if (checked) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }

        findViewById<LinearLayout>(R.id.row_profile_photo).setOnClickListener { pickProfile.launch("image/*") }
        findViewById<LinearLayout>(R.id.row_remove_photo).setOnClickListener { removeProfile() }
        findViewById<LinearLayout>(R.id.row_duress).setOnClickListener { duressDialog() }
        findViewById<LinearLayout>(R.id.row_export).setOnClickListener { backupDialog(true) }
        findViewById<LinearLayout>(R.id.row_import).setOnClickListener { backupDialog(false) }
    }

    private fun duressDialog() {
        val field = passwordField()
        val storeInMemory = CheckBox(this).apply {
            text = getString(R.string.setting_store_duress_memory)
            isChecked = false
        }
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(field)
            addView(storeInMemory)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_set_duress)
            .setMessage(R.string.duress_explanation)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < 6) { chars.fill('\u0000'); toast(getString(R.string.lock_too_short, 6)); return@setPositiveButton }
                val alsoStoreInMemory = storeInMemory.isChecked
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        try {
                            val success = container.storage.appLock.setDuressPasscode(chars)
                            if (success) {
                                if (alsoStoreInMemory) {
                                    CalculatorMemory.store(this@SettingsActivity, String(chars))
                                } else {
                                    CalculatorMemory.clear(this@SettingsActivity)
                                }
                            }
                            success
                        } finally {
                            chars.fill('\u0000')
                        }
                    }
                    toast(if (ok) getString(R.string.toast_duress_set) else getString(R.string.toast_failed, ""))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun backupDialog(exporting: Boolean) {
        val field = passwordField()
        val file = File(getExternalFilesDir(null), "niix-backup.niix")
        AlertDialog.Builder(this)
            .setTitle(if (exporting) R.string.setting_export else R.string.setting_import)
            .setMessage(getString(R.string.backup_path_hint, file.absolutePath))
            .setView(pad(field))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < 6) { chars.fill('\u0000'); toast(getString(R.string.lock_too_short, 6)); return@setPositiveButton }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            if (exporting) container.backup().export(chars, file) else container.backup().import(chars, file)
                            Result.success(Unit)
                        } catch (t: Throwable) { Result.failure(t) } finally { chars.fill('\u0000') }
                    }
                    toast(
                        when {
                            result.isFailure -> getString(R.string.toast_failed, result.exceptionOrNull()?.message ?: "")
                            exporting -> getString(R.string.toast_exported)
                            else -> getString(R.string.toast_imported)
                        },
                    )
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun passwordField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun pad(view: EditText) = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(48, 24, 48, 8)
        addView(view)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun onProfilePicked(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bytes = ImageUtil.processProfile(this@SettingsActivity, uri) ?: return@withContext false
                runCatching { container.conversations.setSelfProfile(bytes) }.isSuccess
            }
            Toast.makeText(this@SettingsActivity, getString(if (ok) R.string.toast_profile_updated else R.string.toast_failed, ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeProfile() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.conversations.setSelfProfile(null) } }
            Toast.makeText(this@SettingsActivity, getString(R.string.toast_profile_updated), Toast.LENGTH_SHORT).show()
        }
    }
}
