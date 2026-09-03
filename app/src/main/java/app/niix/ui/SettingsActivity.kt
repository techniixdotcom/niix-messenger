package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.niix.BuildConfig
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

    private lateinit var requirePasscodeListener: android.widget.CompoundButton.OnCheckedChangeListener
    private lateinit var disguiseListener: android.widget.CompoundButton.OnCheckedChangeListener

    private val pickProfile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            cropLauncher.launch(
                Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_SOURCE_URI, it.toString()),
            )
        }
    }
    private val cropLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(CropActivity.EXTRA_RESULT_PATH)?.let { onProfileCropped(it) }
        }
    }
    private val exportPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { promptBackupPassphrase(exporting = true, uri = it) } }
    private val importPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { promptBackupPassphrase(exporting = false, uri = it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<android.widget.LinearLayout>(R.id.row_profile_photo).setOnClickListener {
            pickProfile.launch("image/*")
        }
        findViewById<android.widget.LinearLayout>(R.id.row_remove_photo).setOnClickListener {
            removeProfile()
        }

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

        val relayMode = findViewById<MaterialSwitch>(R.id.switch_relay_mode)
        relayMode.isChecked = container.conversations.isRelayModeEnabled()
        relayMode.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.conversations.setRelayModeEnabled(checked) }
        }

        val coverTraffic = findViewById<MaterialSwitch>(R.id.switch_cover_traffic)
        coverTraffic.isChecked = container.storage.settings.getBool(SettingsStore.KEY_COVER_TRAFFIC_ENABLED, false)
        coverTraffic.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_COVER_TRAFFIC_ENABLED, checked)

            if (checked) {
                container.coverTraffic.start(container.appScope)
            } else {
                container.coverTraffic.stop()
            }
        }

        val requirePasscode = findViewById<MaterialSwitch>(R.id.switch_require_passcode)
        val disguise = findViewById<MaterialSwitch>(R.id.switch_disguise)

        requirePasscodeListener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked) {
                enablePasscodeDialog(requirePasscode, disguise)
            } else if (disguise.isChecked) {
                confirmDisableBoth(requirePasscode, disguise)
            } else {
                confirmDisablePasscode(requirePasscode)
            }
        }
        disguiseListener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!container.storage.appLock.isPasscodeEnabled()) {
                    setSwitchChecked(disguise, false, disguiseListener)
                    AlertDialog.Builder(this)
                        .setMessage(R.string.warn_enable_disguise_needs_passcode)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                } else {
                    container.storage.appLock.setDisguiseEnabled(true)
                    app.niix.LauncherAlias.apply(this, true)
                    toast(getString(R.string.toast_disguise_on))
                }
            } else {
                confirmDisableDisguise(disguise)
            }
        }

        setSwitchChecked(requirePasscode, container.storage.appLock.isPasscodeEnabled(), requirePasscodeListener)
        setSwitchChecked(disguise, container.storage.appLock.isDisguiseEnabled(), disguiseListener)

        findViewById<LinearLayout>(R.id.row_duress).setOnClickListener { duressDialog() }
        findViewById<LinearLayout>(R.id.row_wipe_now).setOnClickListener { wipeNowDialog() }
        findViewById<LinearLayout>(R.id.row_export).setOnClickListener {
            exportPicker.launch(backupFileName())
        }
        findViewById<LinearLayout>(R.id.row_import).setOnClickListener {
            importPicker.launch(arrayOf("*/*"))
        }

        val updateCheckSwitch = findViewById<MaterialSwitch>(R.id.switch_update_check)
        updateCheckSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_CHECK_ENABLED, true)
        updateCheckSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_UPDATE_CHECK_ENABLED, checked)
        }
        val updateOverTorSwitch = findViewById<MaterialSwitch>(R.id.switch_update_over_tor)
        updateOverTorSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, false)
        updateOverTorSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_UPDATE_OVER_TOR, checked)
        }

        findViewById<LinearLayout>(R.id.row_check_update_now).setOnClickListener { checkForUpdateNow() }

        findViewById<LinearLayout>(R.id.row_diagnostics).setOnClickListener { showDiagnostics() }

        findViewById<TextView>(R.id.text_app_version).text =
            getString(
                R.string.setting_app_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                signingCertificateSha256(),
            )
    }

    /**
     * SHA-256 of the certificate this installed app was actually signed with.
     *
     * Android will refuse to install an update signed with a different key than the installed
     * app, no matter how correct that update otherwise is -- the failure surfaces as a generic
     * "app not installed" with nothing to indicate signing is the cause. Showing the fingerprint
     * here makes that diagnosable: compare it against the release keystore
     * (`keytool -list -v -keystore niix-release.jks`). If they differ, no update built with that
     * keystore can ever install over this install, and the only paths forward are uninstalling
     * first (export a backup!) or signing with the original key.
     */
    private fun signingCertificateSha256(): String = runCatching {
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val info = packageManager.getPackageInfo(packageName, flags)
        val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return@runCatching "unknown"
        val first = signatures.firstOrNull() ?: return@runCatching "unknown"
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

    private fun showDiagnostics() {
        val text = app.niix.core.model.DiagnosticLog.render()
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_diagnostics)
            .setMessage(text)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                // Deliberately a plain label, not the content: on Android 13+ the system shows a
                // preview of what was copied, and a descriptive label there would announce that
                // this device is running this app.
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", text))
                toast(getString(R.string.diagnostics_copied))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun setSwitchChecked(switch: MaterialSwitch, checked: Boolean, listener: android.widget.CompoundButton.OnCheckedChangeListener) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.setOnCheckedChangeListener(listener)
    }

    private fun confirmDisablePasscode(requirePasscode: MaterialSwitch) {
        AlertDialog.Builder(this)
            .setTitle(R.string.warn_disable_passcode_title)
            .setMessage(R.string.warn_disable_passcode_body)
            .setPositiveButton(R.string.warn_disable_passcode_confirm) { _, _ -> disablePasscodeNow(requirePasscode) }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> setSwitchChecked(requirePasscode, true, requirePasscodeListener) }
            .setOnCancelListener { setSwitchChecked(requirePasscode, true, requirePasscodeListener) }
            .show()
    }

    private fun confirmDisableDisguise(disguise: MaterialSwitch) {
        AlertDialog.Builder(this)
            .setTitle(R.string.warn_disable_disguise_title)
            .setMessage(R.string.warn_disable_disguise_body)
            .setPositiveButton(R.string.warn_disable_disguise_confirm) { _, _ ->
                container.storage.appLock.setDisguiseEnabled(false)
                app.niix.LauncherAlias.apply(this, false)
                toast(getString(R.string.toast_disguise_off))
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> setSwitchChecked(disguise, true, disguiseListener) }
            .setOnCancelListener { setSwitchChecked(disguise, true, disguiseListener) }
            .show()
    }

    private fun confirmDisableBoth(requirePasscode: MaterialSwitch, disguise: MaterialSwitch) {
        AlertDialog.Builder(this)
            .setTitle(R.string.warn_disable_both_title)
            .setMessage(R.string.warn_disable_both_body)
            .setPositiveButton(R.string.warn_disable_both_confirm) { _, _ ->
                disablePasscodeNow(requirePasscode, alsoDisguise = disguise)
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> setSwitchChecked(requirePasscode, true, requirePasscodeListener) }
            .setOnCancelListener { setSwitchChecked(requirePasscode, true, requirePasscodeListener) }
            .show()
    }

    private fun disablePasscodeNow(requirePasscode: MaterialSwitch, alsoDisguise: MaterialSwitch? = null) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { container.storage.appLock.disablePasscode() }
            if (ok) {
                CalculatorMemory.clear(this@SettingsActivity)
                if (alsoDisguise != null) {
                    container.storage.appLock.setDisguiseEnabled(false)
                    app.niix.LauncherAlias.apply(this@SettingsActivity, false)
                    setSwitchChecked(alsoDisguise, false, disguiseListener)
                }
                toast(getString(R.string.toast_passcode_disabled))
            } else {
                setSwitchChecked(requirePasscode, true, requirePasscodeListener)
                toast(getString(R.string.toast_change_failed))
            }
        }
    }

    private fun enablePasscodeDialog(requirePasscode: MaterialSwitch, disguise: MaterialSwitch) {
        val field = passwordField()
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_passcode_title)
            .setMessage(getString(R.string.hint_new_passcode))
            .setView(pad(field))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < 6) {
                    chars.fill('\u0000')
                    toast(getString(R.string.lock_too_short, 6))
                    setSwitchChecked(requirePasscode, false, requirePasscodeListener)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        try { container.storage.appLock.enablePasscode(chars) } finally { chars.fill('\u0000') }
                    }
                    if (ok) {
                        toast(getString(R.string.toast_passcode_enabled))
                    } else {
                        setSwitchChecked(requirePasscode, false, requirePasscodeListener)
                        toast(getString(R.string.toast_change_failed))
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> setSwitchChecked(requirePasscode, false, requirePasscodeListener) }
            .setOnCancelListener { setSwitchChecked(requirePasscode, false, requirePasscodeListener) }
            .show()
    }

    private fun wipeNowDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_now_title)
            .setMessage(R.string.wipe_now_body)
            .setPositiveButton(R.string.wipe_now_confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { container.wipeAllData() }
                        CalculatorMemory.clear(this@SettingsActivity)
                    }
                    app.niix.LauncherAlias.apply(this@SettingsActivity, container.storage.appLock.isDisguiseEnabled())
                    toast(getString(R.string.wipe_now_done))
                    startActivity(
                        Intent(
                            this@SettingsActivity,
                            if (container.storage.appLock.isDisguiseEnabled()) CalculatorActivity::class.java else PasscodeActivity::class.java,
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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

    private fun checkForUpdateNow() {
        val enabled = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_CHECK_ENABLED, true)
        if (!enabled) {
            toast(getString(R.string.update_disabled_first))
            return
        }
        // Updates go out over the normal network by default; only route through Tor if the user
        // has explicitly asked for it, in which case Tor genuinely has to be up first.
        val overTor = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, false)
        val socks = if (overTor) container.transport.socksAddress() else null
        if (overTor && socks == null) {
            toast(getString(R.string.update_not_connected))
            return
        }
        val checker = app.niix.update.UpdateChecker(
            applicationContext,
            useTor = overTor,
            socksHost = socks?.first,
            socksPort = socks?.second,
        )
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val currentVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
            val result = withContext(Dispatchers.IO) {
                runCatching { checker.checkForUpdate(currentVersion) }
                    .getOrElse { app.niix.update.UpdateCheckResult.Error(it.message ?: "Unknown error") }
            }
            progress.dismiss()
            when (result) {
                is app.niix.update.UpdateCheckResult.UpToDate -> toast(getString(R.string.update_up_to_date))
                is app.niix.update.UpdateCheckResult.Error -> toast(getString(R.string.toast_failed, result.message))
                is app.niix.update.UpdateCheckResult.Available -> showUpdateAvailableDialog(checker, result.info)
            }
        }
    }

    private fun showUpdateAvailableDialog(checker: app.niix.update.UpdateChecker, info: app.niix.update.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(info.changelog)
            .setPositiveButton(R.string.update_install) { _, _ -> downloadAndOfferInstall(checker, info) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun downloadAndOfferInstall(checker: app.niix.update.UpdateChecker, info: app.niix.update.UpdateInfo) {
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.update_downloading)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { checker.downloadAndVerify(info) }
                    .getOrElse { app.niix.update.UpdateInstallResult.Rejected(it.message ?: "Unknown error") }
            }
            progress.dismiss()
            when (result) {
                is app.niix.update.UpdateInstallResult.Rejected -> toast(getString(R.string.toast_failed, result.reason))
                is app.niix.update.UpdateInstallResult.Ready -> {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.update_available_title, info.versionName))
                        .setMessage(getString(R.string.update_verified_prompt, info.versionName))
                        .setPositiveButton(R.string.update_install) { _, _ ->
                            val startError = checker.promptInstall(result.apkFile)
                            if (startError != null) {
                                toast(startError)
                            } else {
                                // The install runs asynchronously and prompts for confirmation,
                                // so the outcome arrives later via UpdateInstallReceiver. Surface
                                // whatever reason Android gave rather than leaving the user with
                                // the system installer's generic message.
                                app.niix.update.UpdateInstallStatus.lastFailure = null
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(2500)
                                    app.niix.update.UpdateInstallStatus.lastFailure?.let { toast(it) }
                                }
                            }
                        }
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun backupFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return "niix-backup-$stamp.niix"
    }

    private fun displayNameOf(uri: android.net.Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: uri.toString()
                }
            }
        }
        return uri.toString()
    }

    private fun promptBackupPassphrase(exporting: Boolean, uri: android.net.Uri) {
        val field = passwordField()
        AlertDialog.Builder(this)
            .setTitle(if (exporting) R.string.setting_export else R.string.setting_import)
            .setMessage(getString(R.string.backup_path_hint, displayNameOf(uri)))
            .setView(pad(field))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < 6) { chars.fill('\u0000'); toast(getString(R.string.lock_too_short, 6)); return@setPositiveButton }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            if (exporting) exportToUri(chars, uri) else importFromUri(chars, uri)
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

    private fun exportToUri(passphrase: CharArray, uri: android.net.Uri) {
        val temp = File(cacheDir, "export-${System.nanoTime()}.niix")
        try {
            container.backup().export(passphrase, temp)
            val out = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Could not open the chosen location for writing")
            out.use { temp.inputStream().use { input -> input.copyTo(it) } }
        } finally {
            temp.delete()
        }
    }

    private fun importFromUri(passphrase: CharArray, uri: android.net.Uri) {
        val temp = File(cacheDir, "import-${System.nanoTime()}.niix")
        try {
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Could not open the chosen file for reading")
            input.use { temp.outputStream().use { out -> it.copyTo(out) } }
            container.restoreBackup(passphrase, temp)
        } finally {
            temp.delete()
        }
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

    private fun onProfileCropped(path: String) {
        val file = java.io.File(path)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                file.delete()
                if (bytes == null) return@withContext false
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
