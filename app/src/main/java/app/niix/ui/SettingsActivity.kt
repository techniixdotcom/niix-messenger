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

    companion object {
        /** Lets the home screen's avatar menu ask for a profile action without duplicating the
         * image picker, which needs a registered ActivityResultLauncher to work at all. */
        const val EXTRA_ACTION = "niix.settings.action"
        const val ACTION_PICK_PHOTO = "pick_photo"
        const val ACTION_REMOVE_PHOTO = "remove_photo"
    }


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
        // Same behaviour as the system back button: from a section, return to the category list;
        // from the list, leave settings. Calling finish() directly skipped the section logic, so
        // the toolbar arrow jumped straight out while the phone's back button behaved correctly
        // -- two controls that look identical doing different things.
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { navigateBack() }

        findViewById<android.widget.LinearLayout>(R.id.row_profile_photo).setOnClickListener {
            pickProfile.launch("image/*")
        }

        // Handles a profile action requested from the home screen's avatar menu. Consumed once:
        // the extra is cleared so a configuration change (rotation, theme switch) does not
        // re-trigger the picker or silently remove the photo a second time.
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_PICK_PHOTO -> {
                intent.removeExtra(EXTRA_ACTION)
                pickProfile.launch("image/*")
            }
            ACTION_REMOVE_PHOTO -> {
                intent.removeExtra(EXTRA_ACTION)
                removeProfile()
            }
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
        screenshots.isChecked = container.storage.settings.getBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, true)
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
                    // The disguise is meaningless without a passcode -- anyone opening the
                    // "calculator" would land straight in the app. Rather than refusing and
                    // making the user go and find the other setting, ask for a passcode here
                    // and turn both on together, which is what they were trying to achieve.
                    enablePasscodeDialog(requirePasscode, disguise, alsoEnableDisguise = true)
                } else {
                    container.storage.appLock.setDisguiseEnabled(true)
                    container.applyLauncherIcon()
                    toast(getString(R.string.toast_disguise_on))
                }
            } else {
                confirmDisableDisguise(disguise)
            }
        }

        setSwitchChecked(requirePasscode, container.storage.appLock.isPasscodeEnabled(), requirePasscodeListener)
        setSwitchChecked(disguise, container.storage.appLock.isDisguiseEnabled(), disguiseListener)

        findViewById<LinearLayout>(R.id.row_duress).setOnClickListener { duressDialog() }
        findViewById<LinearLayout>(R.id.row_remote_wipe).setOnClickListener { remoteWipeDialog() }
        refreshRemoteWipeStatus()
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
        val disconnectLockedSwitch = findViewById<MaterialSwitch>(R.id.switch_disconnect_locked)
        disconnectLockedSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)
        disconnectLockedSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, checked)
        }

        val updateOverTorSwitch = findViewById<MaterialSwitch>(R.id.switch_update_over_tor)
        updateOverTorSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, false)
        updateOverTorSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_UPDATE_OVER_TOR, checked)
        }

        findViewById<LinearLayout>(R.id.row_check_update_now).setOnClickListener { checkForUpdateNow() }

        findViewById<LinearLayout>(R.id.row_diagnostics).setOnClickListener { showDiagnostics() }

        // Placeholders. They are wired up and visible rather than absent so the layout is
        // settled, but they say plainly that nothing is configured yet -- a row that silently
        // does nothing when tapped is worse than one that admits it.
        wireCategoryMenu()

        val wipeNoConfirmSwitch = findViewById<MaterialSwitch>(R.id.switch_wipe_no_confirm)
        wipeNoConfirmSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_WIPE_WITHOUT_CONFIRM, false)
        wipeNoConfirmSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_WIPE_WITHOUT_CONFIRM, checked)
        }

        val blurSwitch = findViewById<MaterialSwitch>(R.id.switch_blur_messages)
        blurSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_BLUR_MESSAGES, true)
        blurSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_BLUR_MESSAGES, checked)
        }

        val lightIconSwitch = findViewById<MaterialSwitch>(R.id.switch_light_icon)
        lightIconSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_LIGHT_ICON, false)
        lightIconSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_LIGHT_ICON, checked)
            // Applied immediately so the home screen reflects the choice without a restart.
            container.applyLauncherIcon()
        }

        findViewById<LinearLayout>(R.id.row_feedback).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_feedback)
                .setMessage(R.string.placeholder_not_configured)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }
        findViewById<LinearLayout>(R.id.row_donate).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_donate)
                .setMessage(R.string.placeholder_not_configured)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }

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
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = packageManager.getPackageInfo(packageName, flags)
        val signatures = info.signingInfo?.apkContentsSigners ?: return@runCatching "unknown"
        val first = signatures.firstOrNull() ?: return@runCatching "unknown"
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

    /** Category id to the section it reveals. Kept as one list so a category cannot be added
     * without a section, or point at the wrong one. */
    private val categories = listOf(
        R.id.cat_profile to R.id.section_profile,
        R.id.cat_privacy to R.id.section_privacy,
        R.id.cat_security to R.id.section_security,
        R.id.cat_backup to R.id.section_backup,
        R.id.cat_updates to R.id.section_updates,
        R.id.cat_about to R.id.section_about,
    )

    private fun wireCategoryMenu() {
        categories.forEach { (catId, sectionId) ->
            findViewById<LinearLayout>(catId).setOnClickListener { showSection(sectionId) }
        }
        showMenu()
    }

    private fun showMenu() {
        findViewById<LinearLayout>(R.id.settings_menu).visibility = android.view.View.VISIBLE
        categories.forEach { (_, sectionId) ->
            findViewById<LinearLayout>(sectionId).visibility = android.view.View.GONE
        }
        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.action_settings)
    }

    private fun showSection(sectionId: Int) {
        findViewById<LinearLayout>(R.id.settings_menu).visibility = android.view.View.GONE
        categories.forEach { (catId, id) ->
            findViewById<LinearLayout>(id).visibility =
                if (id == sectionId) android.view.View.VISIBLE else android.view.View.GONE
            // Title follows the section so it is clear which one is open, and the back press
            // below has something to return from.
            if (id == sectionId) {
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(categoryTitleFor(catId))
            }
        }
    }

    private fun categoryTitleFor(catId: Int): Int = when (catId) {
        R.id.cat_profile -> R.string.settings_cat_profile
        R.id.cat_privacy -> R.string.settings_cat_privacy
        R.id.cat_security -> R.string.settings_cat_security
        R.id.cat_backup -> R.string.settings_cat_backup
        R.id.cat_updates -> R.string.settings_cat_updates
        else -> R.string.settings_cat_about
    }

    /** Back returns to the category list rather than leaving settings, so the two levels behave
     * the way any nested screen does. Only when the list is already showing does it exit. */
    /** One place both back controls go through, so they cannot diverge. */
    private fun navigateBack() {
        if (findViewById<LinearLayout>(R.id.settings_menu).visibility != android.view.View.VISIBLE) {
            showMenu()
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        if (findViewById<LinearLayout>(R.id.settings_menu).visibility != android.view.View.VISIBLE) {
            showMenu()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

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
                container.applyLauncherIcon()
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
                    container.applyLauncherIcon()
                    setSwitchChecked(alsoDisguise, false, disguiseListener)
                }
                toast(getString(R.string.toast_passcode_disabled))
            } else {
                setSwitchChecked(requirePasscode, true, requirePasscodeListener)
                toast(getString(R.string.toast_change_failed))
            }
        }
    }

    /**
     * Prompts for a new passcode and enables it.
     *
     * When [alsoEnableDisguise] is set, this was reached by turning on the calculator disguise
     * rather than the passcode switch itself, so both are enabled together on success and both
     * are reverted on cancel or failure -- leaving one on without the other would be a setting
     * that silently does nothing.
     */
    private fun enablePasscodeDialog(
        requirePasscode: MaterialSwitch,
        disguise: MaterialSwitch,
        alsoEnableDisguise: Boolean = false,
    ) {
        fun revert() {
            setSwitchChecked(requirePasscode, false, requirePasscodeListener)
            if (alsoEnableDisguise) setSwitchChecked(disguise, false, disguiseListener)
        }
        val field = passwordField()
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_passcode_title)
            .setMessage(
                if (alsoEnableDisguise) {
                    getString(R.string.hint_new_passcode_for_disguise)
                } else {
                    getString(R.string.hint_new_passcode)
                },
            )
            .setView(pad(field))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < 6) {
                    chars.fill('\u0000')
                    toast(getString(R.string.lock_too_short, 6))
                    revert()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        try { container.storage.appLock.enablePasscode(chars) } finally { chars.fill('\u0000') }
                    }
                    if (!ok) {
                        revert()
                        toast(getString(R.string.toast_change_failed))
                        return@launch
                    }
                    setSwitchChecked(requirePasscode, true, requirePasscodeListener)
                    if (alsoEnableDisguise) {
                        container.storage.appLock.setDisguiseEnabled(true)
                        container.applyLauncherIcon()
                        setSwitchChecked(disguise, true, disguiseListener)
                        toast(getString(R.string.toast_disguise_on))
                    } else {
                        toast(getString(R.string.toast_passcode_enabled))
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> revert() }
            .setOnCancelListener { revert() }
            .show()
    }

    private fun refreshRemoteWipeStatus() {
        val status = findViewById<TextView>(R.id.remote_wipe_status)
        val wipe = container.storage.remoteWipe
        status.text = if (wipe.isEnabled()) {
            getString(R.string.setting_remote_wipe_on, wipe.nominated().size)
        } else {
            getString(R.string.setting_remote_wipe_desc)
        }
    }

    private fun remoteWipeDialog() {
        val wipe = container.storage.remoteWipe
        // "Wipe another device" is available whether or not this device accepts remote wipes.
        // They are independent: nominating people to erase *this* phone has nothing to do with
        // being able to erase one of your own, and tying them together would mean exposing this
        // device to remote wipe just to be able to trigger one elsewhere.
        val options = if (wipe.isEnabled()) {
            arrayOf(
                getString(R.string.remote_wipe_nominate),
                getString(R.string.remote_wipe_manage),
                getString(R.string.remote_wipe_send),
                getString(R.string.remote_wipe_disable),
                getString(R.string.remote_wipe_about),
            )
        } else {
            arrayOf(
                getString(R.string.remote_wipe_enable),
                getString(R.string.remote_wipe_send),
                getString(R.string.remote_wipe_about),
            )
        }
        // No setMessage here. AlertDialog renders either a message or a list, not both -- setting
        // both silently drops the list, which left this dialog showing an explanation and a
        // Cancel button with no way to do anything. The explanation is the last item instead.
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_wipe_title)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.remote_wipe_enable) -> showGeneratedToken(wipe.enableAndGenerateToken())
                    getString(R.string.remote_wipe_nominate) -> nominateDialog()
                    getString(R.string.remote_wipe_manage) -> manageNominationsDialog()
                    getString(R.string.remote_wipe_send) -> sendWipeChooseContact()
                    getString(R.string.remote_wipe_about) -> AlertDialog.Builder(this)
                        .setTitle(R.string.remote_wipe_title)
                        .setMessage(R.string.remote_wipe_explain)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                    getString(R.string.remote_wipe_disable) -> {
                        wipe.disable()
                        refreshRemoteWipeStatus()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Shows the freshly generated code, once.
     *
     * Only the hash is stored, so this is genuinely the only opportunity to record it -- which
     * is the point: a copy of the database must not yield a working wipe command for the devices
     * it came from.
     */
    private fun showGeneratedToken(token: String) {
        refreshRemoteWipeStatus()
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_wipe_token_title)
            .setMessage(getString(R.string.remote_wipe_token_body, token))
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("code", token))
                toast(getString(R.string.remote_wipe_copied))
            }
            .setNegativeButton(R.string.dialog_ok, null)
            .show()
    }

    /**
     * Erases another device you have nominated yourself on.
     *
     * Two steps on purpose. Choosing a contact and entering a code are separate deliberate acts,
     * and the final prompt names the contact -- this destroys someone's data irreversibly, with
     * no undo and no confirmation coming back, so it should be hard to do by accident.
     */
    private fun sendWipeChooseContact() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { container.conversations.listContacts() }
            if (contacts.isEmpty()) {
                toast(getString(R.string.remote_wipe_no_contacts))
                return@launch
            }
            val labels = contacts.map { "${it.displayName} (${it.onionAddress.value.take(8)}…)" }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.remote_wipe_send)
                .setItems(labels.toTypedArray()) { _, which ->
                    sendWipeEnterCode(contacts[which].displayName, contacts[which].onionAddress.value)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun sendWipeEnterCode(displayName: String, onion: String) {
        val field = passwordField()
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_wipe_send)
            .setMessage(getString(R.string.remote_wipe_send_body, displayName))
            .setView(pad(field))
            .setPositiveButton(R.string.remote_wipe_send_confirm) { _, _ ->
                val code = field.text.toString().trim()
                if (code.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    container.conversations.sendRemoteWipe(onion, code)
                    // Deliberately says "sent", not "wiped". The recipient never acknowledges,
                    // so claiming success would be a guess presented as fact -- in exactly the
                    // situation where someone most needs an accurate picture of what happened.
                    toast(getString(R.string.remote_wipe_sent))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun nominateDialog() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { container.conversations.listContacts() }
            if (contacts.isEmpty()) {
                toast(getString(R.string.remote_wipe_none_nominated))
                return@launch
            }
            val labels = contacts.map { "${it.displayName} (${it.onionAddress.value.take(8)}…)" }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.remote_wipe_nominate)
                .setItems(labels.toTypedArray()) { _, which ->
                    container.storage.remoteWipe.nominate(contacts[which].onionAddress.value)
                    refreshRemoteWipeStatus()
                    toast(getString(R.string.remote_wipe_nominated_toast))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    /** Revocation is the operation that matters most here -- it is what someone reaches for when
     * a nominated contact's device may be compromised -- so it is one tap from the same screen. */
    private fun manageNominationsDialog() {
        val nominated = container.storage.remoteWipe.nominated()
        if (nominated.isEmpty()) {
            toast(getString(R.string.remote_wipe_none_nominated))
            return
        }
        val labels = nominated.map { getString(R.string.remote_wipe_revoke, it.take(12) + "…") }
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_wipe_manage)
            .setItems(labels.toTypedArray()) { _, which ->
                container.storage.remoteWipe.revoke(nominated[which])
                refreshRemoteWipeStatus()
                toast(getString(R.string.remote_wipe_revoked))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
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
                    container.applyLauncherIcon()
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
