package app.niix.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.BuildConfig
import app.niix.ConnectivityService
import app.niix.R
import app.niix.core.storage.AppLockManager
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

    private val exportPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { promptBackupPassphrase(exporting = true, uri = it) } }
    private val importPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { promptBackupPassphrase(exporting = false, uri = it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Back goes through the dispatcher like the other screens. With predictive back,
        // onBackPressed() isn't called at all, so the gesture would leave Settings from inside a
        // section.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        // Same as the system back button: from a section back to the list, from the list out of
        // settings.
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { navigateBack() }

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

        val phoneKeyboard = findViewById<MaterialSwitch>(R.id.switch_phone_keyboard)
        phoneKeyboard.isChecked = NiixKeyboardController.usesPhoneKeyboard(this)
        phoneKeyboard.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_PHONE_KEYBOARD, checked)
        }

        val screenshots = findViewById<MaterialSwitch>(R.id.switch_screenshots)
        screenshots.isChecked = container.storage.settings.getBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, true)
        screenshots.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, checked)
            applyScreenshotPolicy()
        }

        val nostrSwitch = findViewById<MaterialSwitch>(R.id.switch_nostr)
        val nostrRelaysRow = findViewById<LinearLayout>(R.id.row_nostr_relays)
        if (!container.conversations.isNostrAvailable()) {
            nostrSwitch.isEnabled = false
            nostrRelaysRow.isEnabled = false
        } else {
            lifecycleScope.launch {
                val enabled = container.conversations.isNostrEnabled()
                nostrSwitch.isChecked = enabled
                nostrRelaysRow.alpha = if (enabled) 1f else 0.5f
                nostrSwitch.setOnCheckedChangeListener { _, checked ->
                    nostrRelaysRow.alpha = if (checked) 1f else 0.5f
                    lifecycleScope.launch { container.conversations.setNostrEnabled(checked) }
                }
            }
            nostrRelaysRow.setOnClickListener { nostrRelaysDialog() }
        }

        val announceOnline = findViewById<MaterialSwitch>(R.id.switch_announce_online)
        announceOnline.isChecked = container.storage.settings.getBool(SettingsStore.KEY_ANNOUNCE_ONLINE, false)
        announceOnline.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_ANNOUNCE_ONLINE, checked)
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
        requirePasscodeListener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked) enablePasscodeDialog(requirePasscode) else confirmDisablePasscode(requirePasscode)
        }
        setSwitchChecked(requirePasscode, container.storage.appLock.isPasscodeEnabled(), requirePasscodeListener)

        val disconnectLockedSwitch = findViewById<MaterialSwitch>(R.id.switch_disconnect_locked)
        disconnectLockedSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)
        disconnectLockedSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, checked)
            // Nothing that lets it receive while locked may stay behind. Turned off, the copy is
            // written again the next time the connection starts.
            if (checked) container.storage.receiveBeacon.destroy()
        }

        findViewById<LinearLayout>(R.id.row_duress).setOnClickListener { duressDialog() }
        refreshInactivityWipe()
        findViewById<LinearLayout>(R.id.row_inactivity_wipe).setOnClickListener { inactivityWipeDialog() }
        findViewById<LinearLayout>(R.id.row_remote_wipe).setOnClickListener { remoteWipeDialog() }
        findViewById<LinearLayout>(R.id.row_devices).setOnClickListener {
            startActivity(android.content.Intent(this, DevicesActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.row_blocked).setOnClickListener {
            startActivity(android.content.Intent(this, BlockedActivity::class.java))
        }
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

        val updateOverTorSwitch = findViewById<MaterialSwitch>(R.id.switch_update_over_tor)
        updateOverTorSwitch.isChecked = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, true)
        updateOverTorSwitch.setOnCheckedChangeListener { _, checked ->
            container.storage.settings.setBool(SettingsStore.KEY_UPDATE_OVER_TOR, checked)
        }

        findViewById<LinearLayout>(R.id.row_check_update_now).setOnClickListener { checkForUpdateNow() }

        findViewById<LinearLayout>(R.id.row_diagnostics).setOnClickListener { showDiagnostics() }

        // Placeholders. They're visible so the layout is settled, and they say nothing is set up
        // yet instead of silently doing nothing.
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

        findViewById<LinearLayout>(R.id.row_source).setOnClickListener { openInBrowser(R.string.setting_source_full_url) }
        findViewById<LinearLayout>(R.id.row_feedback).setOnClickListener { openInBrowser(R.string.feedback_url) }
        findViewById<LinearLayout>(R.id.row_donate).setOnClickListener {
            val address = getString(R.string.donate_monero_address)
            niixDialog(this)
                .setTitle(R.string.setting_donate)
                .setMessage(getString(R.string.donate_monero_body, address))
                .setPositiveButton(R.string.donate_copy) { _, _ ->
                    // Copy the address instead of opening a monero: link, which would hand it to
                    // whatever app claims the scheme. Pasting it into their own wallet keeps that
                    // choice with the user.
                    val clip = android.content.ClipData.newPlainText("Monero address", address)
                    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                    toast(getString(R.string.donate_copied))
                }
                .setNegativeButton(R.string.dialog_ok, null)
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

    private fun signingCertificateSha256(): String = runCatching {
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = packageManager.getPackageInfo(packageName, flags)
        val signatures = info.signingInfo?.apkContentsSigners ?: return@runCatching "unknown"
        val first = signatures.firstOrNull() ?: return@runCatching "unknown"
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

    /**
     * Category id to the section it shows. One list, so a category can't exist without a section or
     * point at the wrong one.
     */
    private val categories = listOf(
        R.id.cat_privacy to R.id.section_privacy,
        R.id.cat_security to R.id.section_security,
        R.id.cat_backup to R.id.section_backup,
        R.id.cat_updates to R.id.section_updates,
        R.id.cat_about to R.id.section_about,
    )

    private fun wireCategoryMenu() {
        // Profile has its own screen, the same one the avatar on the home screen opens.
        findViewById<LinearLayout>(R.id.cat_profile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        categories.forEach { (catId, sectionId) ->
            findViewById<LinearLayout>(catId).setOnClickListener { showSection(sectionId) }
        }
        // The Play build has no updater (Play doesn't allow apps to update themselves), so
        // Updates isn't offered at all rather than showing controls that could do nothing.
        if (!BuildConfig.SELF_UPDATE) {
            findViewById<android.view.View>(R.id.cat_updates).visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.cat_updates_divider).visibility = android.view.View.GONE
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
            // Show which section is open.
            if (id == sectionId) {
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(categoryTitleFor(catId))
            }
        }
    }

    private fun categoryTitleFor(catId: Int): Int = when (catId) {
        R.id.cat_privacy -> R.string.settings_cat_privacy
        R.id.cat_security -> R.string.settings_cat_security
        R.id.cat_backup -> R.string.settings_cat_backup
        R.id.cat_updates -> R.string.settings_cat_updates
        else -> R.string.settings_cat_about
    }

    /**
     * Both back controls come through here so they can't behave differently: section to list, list
     * to out of settings.
     */
    private fun navigateBack() {
        if (findViewById<LinearLayout>(R.id.settings_menu).visibility != android.view.View.VISIBLE) {
            showMenu()
        } else {
            finish()
        }
    }

    private fun showDiagnostics() {
        val text = app.niix.core.model.DiagnosticLog.render()
        niixDialog(this)
            .setTitle(R.string.setting_diagnostics)
            .setMessage(text)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                // A plain label on purpose: Android 13+ previews copied content, and a descriptive
                // label would announce which app this is.
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
        niixDialog(this)
            .setTitle(R.string.warn_disable_passcode_title)
            .setMessage(R.string.warn_disable_passcode_body)
            .setPositiveButton(R.string.warn_disable_passcode_confirm) { _, _ -> disablePasscodeNow(requirePasscode) }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> setSwitchChecked(requirePasscode, true, requirePasscodeListener) }
            .setOnCancelListener { setSwitchChecked(requirePasscode, true, requirePasscodeListener) }
            .show()
    }

    private fun disablePasscodeNow(requirePasscode: MaterialSwitch) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { container.storage.appLock.disablePasscode() }
            if (ok) {
                toast(getString(R.string.toast_passcode_disabled))
            } else {
                setSwitchChecked(requirePasscode, true, requirePasscodeListener)
                toast(getString(R.string.toast_change_failed))
            }
        }
    }

    private fun enablePasscodeDialog(requirePasscode: MaterialSwitch) {
        fun revert() = setSwitchChecked(requirePasscode, false, requirePasscodeListener)
        val field = passwordField()
        niixDialog(this)
            .setTitle(R.string.enable_passcode_title)
            .setMessage(R.string.hint_new_passcode)
            .setView(pad(field))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < AppLockManager.MIN_PASSCODE_LENGTH) {
                    chars.fill('\u0000')
                    toast(getString(R.string.lock_too_short, AppLockManager.MIN_PASSCODE_LENGTH))
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
                    toast(getString(R.string.toast_passcode_enabled))
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
        // "Wipe another device" doesn't depend on whether this device accepts remote wipes. You
        // shouldn't have to expose your own phone just to be able to wipe another one.
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
        // No setMessage: AlertDialog shows either a message or a list, and setting both silently
        // drops the list. The explanation goes in as the last item instead.
        niixDialog(this)
            .setTitle(R.string.remote_wipe_title)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.remote_wipe_enable) -> {
                        wipe.enable()
                        refreshRemoteWipeStatus()
                    }
                    getString(R.string.remote_wipe_nominate) -> nominateDialog()
                    getString(R.string.remote_wipe_manage) -> manageNominationsDialog()
                    getString(R.string.remote_wipe_send) -> sendWipeChooseContact()
                    getString(R.string.remote_wipe_about) -> niixDialog(this)
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

    private fun sendWipeChooseContact() {
        lifecycleScope.launch {
            val allowed = withContext(Dispatchers.IO) { container.conversations.contactsThisDeviceCanWipe() }
            if (allowed.isEmpty()) {
                niixDialog(this@SettingsActivity)
                    .setTitle(R.string.remote_wipe_send)
                    .setMessage(R.string.remote_wipe_nobody_nominated_you)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show()
                return@launch
            }
            val contacts = withContext(Dispatchers.IO) { container.conversations.listContacts() }
            val labels = allowed.map { onion ->
                contacts.firstOrNull { it.onionAddress.value == onion }?.displayName
                    ?: (onion.take(12) + "\u2026")
            }
            niixDialog(this@SettingsActivity)
                .setTitle(R.string.remote_wipe_send)
                .setItems(labels.toTypedArray()) { _, which ->
                    confirmSendWipe(labels[which], allowed[which])
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun confirmSendWipe(displayName: String, onion: String) {
        niixDialog(this)
            .setTitle(R.string.remote_wipe_send)
            .setMessage(getString(R.string.remote_wipe_send_body, displayName))
            .setPositiveButton(R.string.remote_wipe_send_confirm) { _, _ ->
                lifecycleScope.launch {
                    container.conversations.sendRemoteWipe(onion)
                    // "Sent", not "wiped": nothing comes back, so anything stronger would be a
                    // guess.
                    toast(getString(R.string.remote_wipe_sent))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    private fun nominateDialog() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { container.conversations.listContacts() }
            if (contacts.isEmpty()) {
                toast(getString(R.string.remote_wipe_none_nominated))
                return@launch
            }
            val labels = contacts.map { "${it.displayName} (${it.onionAddress.value.take(8)}…)" }
            niixDialog(this@SettingsActivity)
                .setTitle(R.string.remote_wipe_nominate)
                .setItems(labels.toTypedArray()) { _, which ->
                    val onion = contacts[which].onionAddress.value
                    val idKey = container.conversations.identityKeyFor(onion)
                    if (idKey == null) {
                        // Without a session there's no identity to tie the permission to, and an
                        // untied permission is exactly what we're avoiding.
                        toast(getString(R.string.remote_wipe_no_identity))
                        return@setItems
                    }
                    container.storage.remoteWipe.nominate(onion, idKey)
                    refreshRemoteWipeStatus()
                    // Tell them right away; until they know, they can't act on it.
                    lifecycleScope.launch { container.conversations.sendWipeNomination(onion, true) }
                    toast(getString(R.string.remote_wipe_nominated_toast))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    /**
     * Revoking is what you need most here (a nominated contact's phone might be compromised), so
     * it's one tap away on the same screen.
     */
    private fun manageNominationsDialog() {
        val nominated = container.storage.remoteWipe.nominated()
        if (nominated.isEmpty()) {
            toast(getString(R.string.remote_wipe_none_nominated))
            return
        }
        val labels = nominated.map { getString(R.string.remote_wipe_revoke, it.take(12) + "…") }
        niixDialog(this)
            .setTitle(R.string.remote_wipe_manage)
            .setItems(labels.toTypedArray()) { _, which ->
                val onion = nominated[which]
                container.storage.remoteWipe.revoke(onion)
                refreshRemoteWipeStatus()
                // They need to hear about the revocation too, or their app keeps offering a wipe
                // this device won't accept.
                lifecycleScope.launch { container.conversations.sendWipeNomination(onion, false) }
                toast(getString(R.string.remote_wipe_revoked))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun wipeNowDialog() {
        niixDialog(this)
            .setTitle(R.string.wipe_now_title)
            .setMessage(R.string.wipe_now_body)
            .setPositiveButton(R.string.wipe_now_confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { container.wipeAllData() } }
                    toast(getString(R.string.wipe_now_done))
                    startActivity(
                        Intent(this@SettingsActivity, OnboardingActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    private fun nostrRelaysDialog() {
        lifecycleScope.launch {
            val current = container.conversations.nostrRelays()
            val field = EditText(this@SettingsActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3
                hint = getString(R.string.nostr_relays_hint)
                setText(current.joinToString("\n"))
            }
            niixDialog(this@SettingsActivity)
                .setTitle(R.string.setting_nostr_relays)
                .setMessage(R.string.nostr_relays_explanation)
                .setView(pad(field))
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val lines = field.text.toString().split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                    lifecycleScope.launch {
                        val saved = container.conversations.setNostrRelays(lines)
                        toast(
                            if (saved.isEmpty()) getString(R.string.toast_nostr_relays_none)
                            else getString(R.string.toast_nostr_relays_saved, saved.size),
                        )
                    }
                }
                .setNeutralButton(R.string.nostr_relays_reset) { _, _ ->
                    lifecycleScope.launch {
                        val saved = container.conversations.setNostrRelays(container.conversations.defaultNostrRelays())
                        toast(getString(R.string.toast_nostr_relays_saved, saved.size))
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun duressDialog() {
        val field = passwordField()
        niixDialog(this)
            .setTitle(R.string.setting_set_duress)
            .setMessage(R.string.duress_explanation)
            .setView(pad(field))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chars = CharArray(field.text.length) { field.text[it] }
                if (chars.size < AppLockManager.MIN_PASSCODE_LENGTH) {
                    chars.fill('\u0000')
                    toast(getString(R.string.lock_too_short, AppLockManager.MIN_PASSCODE_LENGTH))
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        try { container.storage.appLock.setDuressPasscode(chars) } finally { chars.fill('\u0000') }
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
        // Updates go over Tor unless that's been turned off, so Tor has to be up first.
        val overTor = container.storage.settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, true)
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
        val progress = niixDialog(this)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val currentVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
            val result = withContext(Dispatchers.IO) {
                runCatching { checker.checkForUpdate(currentVersion) }
                    .getOrElse { app.niix.update.UpdateCheckResult.Error(it.message ?: getString(R.string.error_unknown)) }
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
        niixDialog(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(info.changelog)
            .setPositiveButton(R.string.update_install) { _, _ -> downloadAndOfferInstall(checker, info) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun downloadAndOfferInstall(checker: app.niix.update.UpdateChecker, info: app.niix.update.UpdateInfo) {
        UpdateDownloadDialog.start(this, checker, info) { apkFile ->
            niixDialog(this@SettingsActivity)
                .setTitle(getString(R.string.update_available_title, info.versionName))
                .setMessage(getString(R.string.update_verified_prompt, info.versionName))
                .setPositiveButton(R.string.update_install) { _, _ ->
                    val startError = checker.promptInstall(apkFile)
                    if (startError != null) {
                        toast(startError)
                    } else {
                        // The install is asynchronous and asks for confirmation, so the result
                        // comes later through UpdateInstallReceiver. Show Android's reason instead
                        // of only the installer's generic message.
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

    /**
     * A backup holds the identity keys and every message, and once it leaves the phone nothing but
     * this passphrase protects it; there's no hardware secret to lean on. So it's free text with a
     * real minimum, not a PIN, and it's typed twice on export, where a typo would make the backup
     * useless.
     */
    private fun promptBackupPassphrase(exporting: Boolean, uri: android.net.Uri) {
        fun passphraseField(hint: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(hint)
        }
        val field = passphraseField(R.string.backup_passphrase_hint)
        val confirm = passphraseField(R.string.backup_passphrase_confirm)
        val fields = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(field)
            if (exporting) addView(confirm)
        }
        niixDialog(this)
            .setTitle(if (exporting) R.string.setting_export else R.string.setting_import)
            .setMessage(getString(R.string.backup_path_hint, displayNameOf(uri)))
            .setView(fields)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                if (exporting && field.text.length < MIN_BACKUP_PASSPHRASE) {
                    toast(getString(R.string.lock_too_short, MIN_BACKUP_PASSPHRASE))
                    return@setPositiveButton
                }
                if (exporting && field.text.toString() != confirm.text.toString()) {
                    toast(getString(R.string.backup_passphrase_mismatch))
                    return@setPositiveButton
                }
                val chars = CharArray(field.text.length) { field.text[it] }
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

    private fun refreshInactivityWipe() {
        val days = runCatching { container.storage.appLock.inactivityWipeDays() }.getOrDefault(0)
        findViewById<TextView>(R.id.text_inactivity_wipe).text =
            if (days == 0) getString(R.string.inactivity_wipe_off) else resources.getQuantityString(R.plurals.inactivity_wipe_after, days, days)
    }

    /** Off or a number of days. The note goes in setView, since setMessage would replace the list. */
    private fun inactivityWipeDialog() {
        val options = intArrayOf(0, 3, 7, 14, 30)
        val labels = options.map { if (it == 0) getString(R.string.timer_off) else resources.getQuantityString(R.plurals.inactivity_wipe_days, it, it) }
            .toTypedArray()
        val current = runCatching { container.storage.appLock.inactivityWipeDays() }.getOrDefault(0)
        val note = TextView(this).apply {
            setText(R.string.inactivity_wipe_note)
            textSize = 13f
            setTextColor(getColor(R.color.niix_on_surface_muted))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        niixDialog(this)
            .setTitle(R.string.setting_inactivity_wipe)
            .setView(note)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                runCatching { container.storage.appLock.setInactivityWipeDays(options[which]) }
                refreshInactivityWipe()
                dialog.dismiss()
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

    /**
     * Opens a link in the browser. An embedded webview would be an odd thing for a messenger that
     * avoids servers.
     */
    private fun openInBrowser(url: Int) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(url)))) }
            .onFailure { toast(getString(R.string.setting_source_no_browser)) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MIN_BACKUP_PASSPHRASE = 12
    }
}
