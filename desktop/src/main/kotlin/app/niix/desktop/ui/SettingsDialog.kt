package app.niix.desktop.ui

import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.AppLockManager
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import app.niix.desktop.Background
import app.niix.desktop.DesktopContainer
import app.niix.desktop.Strings
import app.niix.desktop.Version
import app.niix.desktop.tr
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.ActionRow
import org.gnome.adw.ComboRow
import org.gnome.adw.HeaderBar
import org.gnome.adw.NavigationPage
import org.gnome.adw.PreferencesDialog
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.PreferencesPage
import org.gnome.adw.SwitchRow
import org.gnome.adw.ToolbarView
import org.gnome.gtk.Align
import org.gnome.gtk.Button
import org.gnome.gtk.FileDialog
import org.gnome.gtk.Image
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.StringList
import org.gnome.gtk.CheckButton
import org.gnome.gtk.TextIter
import org.gnome.gtk.TextView
import org.gnome.gtk.Widget
import org.gnome.gtk.Window
import org.gnome.gtk.WrapMode

/** Settings, grouped as on the phone, with the desktop's own options where they belong. */
class SettingsDialog(private val container: DesktopContainer, private val app: NiixApp) {

    private val dialog = PreferencesDialog()
    private val settings get() = container.storage.settings
    private var refreshing = false

    init {
        dialog.title = tr("action_settings")
        dialog.searchEnabled = true
        dialog.add(profilePage())
        dialog.add(themePage())
        dialog.add(privacyPage())
        dialog.add(securityPage())
        dialog.add(backupPage())
        dialog.add(aboutPage())
    }

    fun present(parent: Widget) = dialog.present(parent)

    private fun window(): Window? = app.window()

    private fun page(title: String, icon: String): PreferencesPage {
        val page = PreferencesPage()
        page.title = title
        page.iconName = icon
        return page
    }

    private fun group(title: String? = null, description: String? = null): PreferencesGroup {
        val group = PreferencesGroup()
        title?.let { group.title = it }
        description?.let { group.description = it }
        return group
    }

    private fun actionRow(title: String, subtitle: String? = null, icon: String = "go-next-symbolic", onClick: () -> Unit): ActionRow {
        val row = ActionRow()
        row.useMarkup = false
        row.title = title
        subtitle?.let { row.subtitle = it }
        row.activatable = true
        row.addSuffix(Image.fromIconName(icon))
        row.onActivated { onClick() }
        return row
    }

    /** A switch stored under [key], read now and written whenever it's flipped. */
    private fun settingSwitch(title: String, subtitle: String, key: String, default: Boolean, onChange: suspend (Boolean) -> Unit = {}): SwitchRow {
        val row = SwitchRow()
        row.useMarkup = false
        row.title = title
        row.subtitle = subtitle
        row.active = runCatching { settings.getBool(key, default) }.getOrDefault(default)
        row.onNotify("active") {
            val value = row.active
            uiScope.launch {
                withContext(Dispatchers.IO) { runCatching { settings.setBool(key, value) } }
                onChange(value)
            }
        }
        return row
    }

    private fun profilePage(): PreferencesPage {
        val page = page(tr("settings_cat_profile"), "avatar-default-symbolic")
        val g = group(description = tr("profile_name_desc"))
        val name = actionRow(tr("profile_change_name"), "") { changeName() }
        uiScope.launch {
            name.subtitle = withContext(Dispatchers.IO) { runCatching { container.conversations.username() }.getOrDefault("") }
        }
        nameRow = name
        g.add(name)
        g.add(actionRow(tr("profile_change_photo"), tr("setting_profile_photo_desc")) { choosePhoto() })
        g.add(actionRow(tr("profile_remove_photo"), null, "user-trash-symbolic") { removePhoto() })
        g.add(actionRow(tr("profile_my_code"), tr("profile_code_desc")) { MyCodeDialog(container).present(dialog) })
        page.add(g)
        return page
    }

    private var nameRow: ActionRow? = null

    /** One row per theme, as on the phone: a swatch of its background ringed in its colour. */
    private fun themePage(): PreferencesPage {
        val page = page(tr("settings_cat_theme"), "applications-graphics-symbolic")
        val g = group()
        var first: CheckButton? = null
        NiixTheme.entries.forEach { theme ->
            val row = ActionRow()
            row.useMarkup = false
            row.title = tr(theme.labelKey)
            val swatch = org.gnome.gtk.Box(org.gnome.gtk.Orientation.HORIZONTAL, 0)
            swatch.addCssClass("theme-swatch")
            swatch.addCssClass(theme.swatchClass)
            swatch.valign = Align.CENTER
            row.addPrefix(swatch)
            val check = CheckButton()
            check.valign = Align.CENTER
            first?.let { check.setGroup(it) } ?: run { first = check }
            check.active = theme == Theme.current
            check.onToggled { if (check.active) Theme.select(theme) }
            row.addSuffix(check)
            row.activatableWidget = check
            g.add(row)
        }
        page.add(g)
        return page
    }

    private fun changeName() {
        uiScope.launch {
            val current = withContext(Dispatchers.IO) { runCatching { container.conversations.username() }.getOrDefault("") }
            val name = askText(dialog, tr("profile_change_name"), tr("profile_change_name_body"), current, maxLength = 64) ?: return@launch
            val ok = withContext(Dispatchers.IO) { runCatching { container.conversations.setUsername(name) }.getOrDefault(false) }
            Toasts.show(tr(if (ok) "profile_name_changed" else "profile_name_invalid"))
            if (ok) nameRow?.subtitle = name
        }
    }

    private fun choosePhoto() {
        val chooser = FileDialog()
        chooser.title = tr("profile_change_photo")
        chooser.open(window(), null) { result ->
            val path = runCatching { chooser.openFinish(result) }.getOrNull()?.path?.toString() ?: return@open
            uiScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val bytes = squareJpeg(File(path)) ?: return@withContext false
                    runCatching { container.conversations.setSelfProfile(bytes) }.isSuccess
                }
                Toasts.show(if (ok) tr("toast_profile_updated") else tr("toast_failed", ""))
            }
        }
    }

    private fun removePhoto() {
        uiScope.launch {
            if (!confirm(dialog, tr("profile_remove_photo"), tr("profile_remove_photo_confirm"), tr("action_delete"), destructive = true)) return@launch
            withContext(Dispatchers.IO) { runCatching { container.conversations.setSelfProfile(null) } }
            Toasts.show(tr("toast_profile_updated"))
        }
    }

    private fun privacyPage(): PreferencesPage {
        val page = page(tr("settings_cat_privacy"), "security-medium-symbolic")
        val messages = group(tr("settings_section_privacy"))
        messages.add(settingSwitch(tr("setting_notification_privacy"), tr("setting_notif_desc"), SettingsStore.KEY_NOTIFICATION_PRIVACY, true))
        messages.add(settingSwitch(tr("setting_blur_messages"), tr("setting_blur_messages_desc"), SettingsStore.KEY_BLUR_MESSAGES, true))
        messages.add(settingSwitch(tr("setting_allowlist_only"), tr("setting_allowlist_desc"), SettingsStore.KEY_ALLOWLIST_ONLY, false))
        page.add(messages)

        val network = group(tr("desktop_network"))
        val nostr = SwitchRow()
        nostr.useMarkup = false
        nostr.title = tr("setting_nostr")
        nostr.subtitle = tr("setting_nostr_desc")
        uiScope.launch {
            refreshing = true
            nostr.active = withContext(Dispatchers.IO) { runCatching { container.conversations.isNostrEnabled() }.getOrDefault(true) }
            refreshing = false
        }
        nostr.onNotify("active") {
            if (refreshing) return@onNotify
            val value = nostr.active
            uiScope.launch { withContext(Dispatchers.IO) { runCatching { container.conversations.setNostrEnabled(value) } } }
        }
        network.add(nostr)
        network.add(actionRow(tr("setting_nostr_relays"), tr("setting_nostr_relays_desc")) { editRelays() })
        network.add(settingSwitch(tr("setting_announce_online"), tr("setting_announce_online_desc"), SettingsStore.KEY_ANNOUNCE_ONLINE, false))
        network.add(settingSwitch(tr("setting_cover_traffic"), tr("setting_cover_traffic_desc"), SettingsStore.KEY_COVER_TRAFFIC_ENABLED, false) { on ->
            if (on) container.coverTraffic.start(container.appScope) else container.coverTraffic.stop()
        })
        page.add(network)
        return page
    }

    private fun editRelays() {
        uiScope.launch {
            val current = withContext(Dispatchers.IO) { runCatching { container.conversations.nostrRelays() }.getOrDefault(emptyList()) }
            val text = TextView()
            text.wrapMode = WrapMode.CHAR
            text.monospace = true
            text.buffer.setText(current.joinToString("\n"), -1)
            val scroller = ScrolledWindow()
            scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            scroller.child = text
            scroller.vexpand = true
            scroller.margins(12)
            val view = ToolbarView()
            val header = HeaderBar()
            val save = Button.withLabel(tr("dialog_save"))
            save.addCssClass("suggested-action")
            val reset = Button.withLabel(tr("nostr_relays_reset"))
            header.packEnd(save)
            header.packStart(reset)
            view.addTopBar(header)
            val box = vbox(8)
            box.append(wrappingLabel(tr("nostr_relays_explanation"), "dim-label").also { it.margins(12) })
            box.append(scroller)
            view.content = box
            val subpage = NavigationPage(view, tr("setting_nostr_relays"))
            save.onClicked {
                val buffer = text.buffer
                val start = TextIter()
                val end = TextIter()
                buffer.getBounds(start, end)
                val lines = buffer.getText(start, end, false).lines().map { it.trim() }.filter { it.isNotEmpty() }
                uiScope.launch {
                    val saved = withContext(Dispatchers.IO) { runCatching { container.conversations.setNostrRelays(lines) }.getOrDefault(emptyList()) }
                    Toasts.show(if (saved.isEmpty()) tr("toast_nostr_relays_none") else tr("toast_nostr_relays_saved", saved.size))
                    dialog.popSubpage()
                }
            }
            reset.onClicked {
                uiScope.launch {
                    withContext(Dispatchers.IO) { runCatching { container.conversations.setNostrRelays(container.conversations.defaultNostrRelays()) } }
                    dialog.popSubpage()
                }
            }
            dialog.pushSubpage(subpage)
        }
    }

    private fun securityPage(): PreferencesPage {
        val page = page(tr("settings_cat_security"), "system-lock-screen-symbolic")
        val lock = group(tr("settings_section_security"))
        val passcode = SwitchRow()
        passcode.useMarkup = false
        passcode.title = tr("setting_require_passcode")
        passcode.subtitle = tr("setting_require_passcode_desc")
        passcode.active = container.storage.appLock.isPasscodeEnabled()
        passcode.onNotify("active") {
            if (refreshing) return@onNotify
            if (passcode.active) enablePasscode(passcode) else disablePasscode(passcode)
        }
        lock.add(passcode)
        lock.add(actionRow(tr("setting_set_duress"), tr("setting_duress_desc")) { setDuress() })
        val wipeHours = intArrayOf(0, 3, 6, 12, 24, 48, 72, 96, 120, 144, 168)
        val inactivity = ComboRow()
        inactivity.useMarkup = false
        inactivity.title = tr("setting_inactivity_wipe")
        inactivity.subtitle = tr("inactivity_wipe_note")
        val current = runCatching { container.storage.appLock.inactivityWipeHours() }.getOrDefault(0)
        val options = (wipeHours.toList() + listOf(current).filter { it !in wipeHours }).sorted()
        inactivity.model = StringList(options.map { if (it == 0) tr("timer_off") else wipeDuration(it) }.toTypedArray())
        inactivity.selected = options.indexOf(current).coerceAtLeast(0)
        inactivity.onNotify("selected") {
            val hours = options.getOrElse(inactivity.selected) { 0 }
            uiScope.launch { withContext(Dispatchers.IO) { runCatching { container.storage.appLock.setInactivityWipeHours(hours) } } }
        }
        lock.add(inactivity)
        lock.add(settingSwitch(tr("setting_disconnect_locked"), tr("setting_disconnect_locked_desc"), SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false) { on ->
            // Nothing that lets it receive, or ring, while locked may stay behind.
            withContext(Dispatchers.IO) {
                if (on) runCatching { container.storage.receiveBeacon.destroy() }
                container.conversations.refreshRingBeacon()
            }
        })
        lock.add(settingSwitch(tr("setting_ring_locked"), tr("setting_ring_locked_desc"), SettingsStore.KEY_RING_WHEN_LOCKED, true) {
            withContext(Dispatchers.IO) { container.conversations.refreshRingBeacon() }
        })
        val keyStore = ActionRow()
        keyStore.useMarkup = false
        keyStore.title = tr("desktop_key_storage")
        keyStore.subtitle = when (container.storage.keyStoreKind()) {
            SecureStorage.KEY_STORE_KEYRING -> tr("desktop_key_storage_keyring")
            SecureStorage.KEY_STORE_FILE -> tr("desktop_key_storage_file")
            else -> ""
        }
        lock.add(keyStore)
        page.add(lock)

        val devices = group(tr("desktop_devices_and_contacts"))
        devices.add(actionRow(tr("setting_devices"), tr("setting_devices_desc")) { DevicesPage(container, dialog).push() })
        devices.add(actionRow(tr("setting_blocked"), tr("setting_blocked_desc")) { BlockedPage(container, dialog).push() })
        devices.add(actionRow(tr("setting_remote_wipe"), tr("setting_remote_wipe_desc")) { RemoteWipePage(container, dialog).push() })
        page.add(devices)

        val desktop = group(tr("desktop_section"))
        val autostart = SwitchRow()
        autostart.useMarkup = false
        autostart.title = tr("desktop_autostart")
        autostart.subtitle = tr("desktop_autostart_desc")
        autostart.active = Background.autostartEnabled(container)
        autostart.onNotify("active") {
            val value = autostart.active
            uiScope.launch {
                val ok = withContext(Dispatchers.IO) { Background.setAutostart(container, value) }
                if (!ok) Toasts.show(tr("toast_change_failed"))
            }
        }
        desktop.add(autostart)
        page.add(desktop)

        val danger = group(tr("setting_wipe_now"))
        danger.add(settingSwitch(tr("setting_wipe_no_confirm"), tr("setting_wipe_no_confirm_desc"), SettingsStore.KEY_WIPE_WITHOUT_CONFIRM, false))
        val wipe = actionRow(tr("setting_wipe_now"), tr("setting_wipe_now_desc"), "user-trash-symbolic") { wipeNow() }
        wipe.addCssClass("danger")
        danger.add(wipe)
        page.add(danger)
        return page
    }

    private fun wipeDuration(hours: Int): String =
        if (hours < 24) Strings.plural("duration_hours", hours) else Strings.plural("duration_days", hours / 24)

    private fun revert(row: SwitchRow, value: Boolean) {
        refreshing = true
        row.active = value
        refreshing = false
    }

    private fun enablePasscode(row: SwitchRow) {
        uiScope.launch {
            val chars = askSecret(dialog, tr("enable_passcode_title"), tr("lock_too_short", AppLockManager.MIN_PASSCODE_LENGTH), confirmTwice = true, confirmLabel = tr("dialog_ok"))
            if (chars == null || chars.size < AppLockManager.MIN_PASSCODE_LENGTH) {
                chars?.let {
                    it.fill('\u0000')
                    Toasts.show(tr("lock_too_short", AppLockManager.MIN_PASSCODE_LENGTH))
                }
                revert(row, false)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                try { runCatching { container.storage.appLock.enablePasscode(chars) }.getOrDefault(false) } finally { chars.fill('\u0000') }
            }
            Toasts.show(tr(if (ok) "toast_passcode_enabled" else "toast_change_failed"))
            if (!ok) revert(row, false)
        }
    }

    private fun disablePasscode(row: SwitchRow) {
        uiScope.launch {
            val sure = confirm(dialog, tr("warn_disable_passcode_title"), tr("warn_disable_passcode_body"), tr("warn_disable_passcode_confirm"), destructive = true)
            if (!sure) {
                revert(row, true)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { runCatching { container.storage.appLock.disablePasscode() }.getOrDefault(false) }
            Toasts.show(tr(if (ok) "toast_passcode_disabled" else "toast_change_failed"))
            if (!ok) revert(row, true)
        }
    }

    private fun setDuress() {
        uiScope.launch {
            if (!container.storage.appLock.isPasscodeEnabled()) {
                Toasts.show(tr("duress_needs_passcode"))
                return@launch
            }
            val chars = askSecret(dialog, tr("setting_set_duress"), tr("duress_explanation"), confirmTwice = true, confirmLabel = tr("dialog_save")) ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                try { runCatching { container.storage.appLock.setDuressPasscode(chars) }.getOrDefault(false) } finally { chars.fill('\u0000') }
            }
            Toasts.show(tr(if (ok) "toast_duress_set" else "duress_invalid"))
        }
    }

    private fun wipeNow() {
        uiScope.launch {
            if (!container.wipeWithoutConfirmation() &&
                !confirm(dialog, tr("wipe_now_title"), tr("wipe_now_body"), tr("wipe_now_confirm"), destructive = true)
            ) return@launch
            dialog.forceClose()
            withContext(Dispatchers.IO) { runCatching { container.wipeAllData() } }
            Toasts.show(tr("wipe_now_done"))
            app.showOnboarding()
        }
    }

    private fun backupPage(): PreferencesPage {
        val page = page(tr("settings_cat_backup"), "document-save-symbolic")
        val g = group(tr("settings_section_backup"), tr("settings_cat_backup_desc"))
        g.add(actionRow(tr("setting_export"), tr("setting_export_desc")) { export() })
        g.add(actionRow(tr("setting_import"), tr("setting_import_desc")) { import() })
        page.add(g)
        return page
    }

    private fun export() {
        val chooser = FileDialog()
        chooser.title = tr("setting_export")
        chooser.initialName = "niix-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".niix"
        chooser.save(window(), null) { result ->
            val path = runCatching { chooser.saveFinish(result) }.getOrNull()?.path?.toString() ?: return@save
            uiScope.launch {
                val chars = askSecret(dialog, tr("setting_export"), tr("backup_path_hint", path), confirmTwice = true, confirmLabel = tr("dialog_ok")) ?: return@launch
                if (chars.size < MIN_BACKUP_PASSPHRASE) {
                    chars.fill('\u0000')
                    Toasts.show(tr("lock_too_short", MIN_BACKUP_PASSPHRASE))
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    val temp = container.tempFiles.create("export_", "niix")
                    try {
                        runCatching {
                            container.backup().export(chars, temp)
                            temp.copyTo(File(path), overwrite = true)
                        }
                    } finally {
                        chars.fill('\u0000')
                        temp.delete()
                    }
                }
                Toasts.show(if (result.isSuccess) tr("toast_exported") else tr("toast_failed", result.exceptionOrNull()?.message ?: ""))
            }
        }
    }

    private fun import() {
        val chooser = FileDialog()
        chooser.title = tr("setting_import")
        chooser.open(window(), null) { result ->
            val path = runCatching { chooser.openFinish(result) }.getOrNull()?.path?.toString() ?: return@open
            uiScope.launch {
                val chars = askSecret(dialog, tr("setting_import"), tr("backup_path_hint", path), confirmTwice = false, confirmLabel = tr("dialog_ok")) ?: return@launch
                Toasts.show(tr("desktop_restoring"))
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        runCatching { container.restoreBackup(chars, File(path)) }
                    } finally {
                        chars.fill('\u0000')
                    }
                }
                if (outcome.isSuccess) {
                    Toasts.show(tr("toast_imported"))
                    dialog.forceClose()
                    app.afterUnlock()
                } else {
                    Toasts.show(tr("toast_failed", outcome.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    private fun aboutPage(): PreferencesPage {
        val page = page(tr("settings_cat_about"), "help-about-symbolic")
        val g = group(tr("settings_section_about"))
        val version = ActionRow()
        version.useMarkup = false
        version.title = tr("setting_app_version")
        version.subtitle = Version.name
        g.add(version)
        g.add(actionRow(tr("setting_source"), "https://" + tr("setting_source_url"), "adw-external-link-symbolic") {
            launchUri("https://" + tr("setting_source_url"))
        })
        g.add(actionRow(tr("setting_feedback"), tr("setting_feedback_desc"), "adw-external-link-symbolic") { launchUri(tr("feedback_url")) })
        g.add(actionRow(tr("setting_diagnostics"), tr("setting_diagnostics_desc"), "edit-copy-symbolic") {
            dialog.clipboard.setText(diagnostics())
            Toasts.show(tr("diagnostics_copied"))
        })
        page.add(g)
        return page
    }

    private fun diagnostics(): String = buildString {
        appendLine("NiiX desktop ${Version.name}")
        appendLine("Java ${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        appendLine("Flatpak: ${Background.inFlatpak}")
        appendLine("Key store: ${container.storage.keyStoreKind() ?: "none"}")
        appendLine()
        append(DiagnosticLog.render())
    }

    private fun launchUri(uri: String) {
        val launcher = org.gnome.gtk.UriLauncher(uri)
        launcher.launch(window(), null) { result -> runCatching { launcher.launchFinish(result) } }
    }

    companion object {
        private const val MIN_BACKUP_PASSPHRASE = 12

        /**
         * A profile picture as the phone makes them: the middle square, 480 pixels across, as a
         * JPEG. Re-encoding also drops whatever metadata the original carried, location included.
         */
        fun squareJpeg(file: File): ByteArray? = runCatching {
            ImageIO.createImageInputStream(file).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = input
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w.toLong() * h > 40_000_000L) return null
                    val source = reader.read(0)
                    val side = minOf(source.width, source.height)
                    val x = (source.width - side) / 2
                    val y = (source.height - side) / 2
                    val out = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
                    val g = out.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null)
                    g.dispose()
                    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                    val bytes = ByteArrayOutputStream()
                    ImageIO.createImageOutputStream(bytes).use { stream ->
                        writer.output = stream
                        val params = writer.defaultWriteParam
                        params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        params.compressionQuality = 0.85f
                        writer.write(null, IIOImage(out, null, null), params)
                        writer.dispose()
                    }
                    bytes.toByteArray()
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

        private const val SIZE = 480
    }
}

/** A settings subpage with a header bar and a preferences page in it. */
abstract class SettingsSubpage(private val dialog: PreferencesDialog, title: String) {
    protected val page = PreferencesPage()
    private val navigation: NavigationPage

    init {
        val view = ToolbarView()
        view.addTopBar(HeaderBar())
        view.content = page
        navigation = NavigationPage(view, title)
    }

    fun push() {
        dialog.pushSubpage(navigation)
    }

    protected val parentWidget: Widget get() = dialog
}

/** The account's devices: which there are, linking another and removing one. */
class DevicesPage(private val container: DesktopContainer, dialog: PreferencesDialog) : SettingsSubpage(dialog, tr("setting_devices")) {

    private var list = PreferencesGroup()
    private val actions = PreferencesGroup()

    init {
        page.add(list)
        val link = ActionRow()
        link.useMarkup = false
        link.title = tr("devices_link_new")
        link.subtitle = tr("desktop_devices_link_desc")
        link.activatable = true
        link.addSuffix(Image.fromIconName("list-add-symbolic"))
        link.onActivated { enterLinkCode() }
        actions.add(link)
        val fromPicture = ActionRow()
        fromPicture.useMarkup = false
        fromPicture.title = tr("desktop_read_qr_image")
        fromPicture.activatable = true
        fromPicture.addSuffix(Image.fromIconName("image-x-generic-symbolic"))
        fromPicture.onActivated { readLinkQr() }
        actions.add(fromPicture)
        page.add(actions)
        reload()
    }

    private fun readLinkQr() {
        val chooser = FileDialog()
        chooser.title = tr("desktop_read_qr_image")
        chooser.open(parentWidget.root as? Window, null) { result ->
            val path = runCatching { chooser.openFinish(result) }.getOrNull()?.path?.toString() ?: return@open
            uiScope.launch {
                val code = withContext(Dispatchers.IO) { QrCodes.decode(File(path)) }
                if (code == null) Toasts.show(tr("desktop_no_qr_found")) else linkDevice(parentWidget, container, code) { reload() }
            }
        }
    }

    private fun enterLinkCode() {
        uiScope.launch {
            val code = askText(parentWidget, tr("devices_link_new"), tr("desktop_devices_link_desc"), placeholder = "niix-link:1:…", confirmLabel = tr("devices_link_confirm")) ?: return@launch
            if (code.isNotEmpty()) linkDevice(parentWidget, container, code) { reload() }
        }
    }

    private fun reload() {
        uiScope.launch {
            val (devices, me) = withContext(Dispatchers.IO) {
                runCatching { container.conversations.ownDevices() to container.conversations.thisDeviceId() }.getOrDefault(emptyList<app.niix.core.model.Device>() to -1)
            }
            page.remove(list)
            page.remove(actions)
            list = PreferencesGroup()
            list.description = tr("devices_explain")
            devices.forEach { device ->
                val row = ActionRow()
                row.useMarkup = false
                row.title = if (device.id == me) tr("devices_this") else tr("devices_other", device.id)
                row.subtitle = device.onion.value.take(16) + "…"
                if (device.id != me) {
                    val remove = Button.withLabel(tr("devices_remove"))
                    remove.addCssClass("destructive-action")
                    remove.valign = Align.CENTER
                    remove.onClicked {
                        uiScope.launch {
                            if (!confirm(parentWidget, tr("devices_remove_title"), tr("devices_remove_body"), tr("devices_remove"), destructive = true)) return@launch
                            val removed = withContext(Dispatchers.IO) { runCatching { container.conversations.removeDevice(device.id) }.getOrDefault(false) }
                            if (removed) Toasts.show(tr("devices_removed"))
                            reload()
                        }
                    }
                    row.addSuffix(remove)
                }
                list.add(row)
            }
            page.add(list)
            page.add(actions)
        }
    }
}

/** Blocked people, and unblocking them. */
class BlockedPage(private val container: DesktopContainer, dialog: PreferencesDialog) : SettingsSubpage(dialog, tr("setting_blocked")) {

    private var list = PreferencesGroup()

    init {
        page.add(list)
        reload()
    }

    private fun reload() {
        uiScope.launch {
            val blocked = withContext(Dispatchers.IO) { runCatching { container.conversations.blocked() }.getOrDefault(emptyList()) }
            page.remove(list)
            list = PreferencesGroup()
            list.description = tr("blocked_explain")
            if (blocked.isEmpty()) list.add(wrappingLabel(tr("blocked_empty"), "dim-label"))
            blocked.forEach { peer ->
                val row = ActionRow()
                row.useMarkup = false
                row.title = peer.name ?: (peer.onion.take(12) + "…")
                row.subtitle = peer.onion.take(16) + "…"
                val unblock = Button.withLabel(tr("menu_unblock"))
                unblock.valign = Align.CENTER
                unblock.onClicked {
                    uiScope.launch {
                        if (!confirm(parentWidget, tr("blocked_unblock_title"), tr("blocked_unblock_body"), tr("menu_unblock"))) return@launch
                        withContext(Dispatchers.IO) { runCatching { container.conversations.unblock(peer.onion) } }
                        Toasts.show(tr("toast_unblocked"))
                        reload()
                    }
                }
                row.addSuffix(unblock)
                list.add(row)
            }
            page.add(list)
        }
    }
}

/**
 * Remote wipe, as on the phone: allowing contacts to erase this device, choosing who, revoking,
 * and erasing the device of someone who chose this one.
 */
class RemoteWipePage(private val container: DesktopContainer, dialog: PreferencesDialog) : SettingsSubpage(dialog, tr("setting_remote_wipe")) {

    private var content = PreferencesGroup()

    init {
        page.add(content)
        reload()
    }

    private fun reload() {
        uiScope.launch {
            val (enabled, nominated) = withContext(Dispatchers.IO) {
                runCatching { container.storage.remoteWipe.isEnabled() to container.storage.remoteWipe.nominated() }.getOrDefault(false to emptyList())
            }
            page.remove(content)
            content = PreferencesGroup()
            content.description = tr("remote_wipe_explain")
            val toggle = SwitchRow()
            toggle.useMarkup = false
            toggle.title = tr("remote_wipe_title")
            toggle.active = enabled
            toggle.onNotify("active") {
                val on = toggle.active
                uiScope.launch {
                    withContext(Dispatchers.IO) { runCatching { if (on) container.storage.remoteWipe.enable() else container.storage.remoteWipe.disable() } }
                    reload()
                }
            }
            content.add(toggle)
            if (enabled) {
                content.add(row(tr("remote_wipe_nominate"), "list-add-symbolic") { nominate() })
                nominated.forEach { onion ->
                    content.add(row(tr("remote_wipe_revoke", onion.take(12) + "…"), "edit-delete-symbolic") { revoke(onion) })
                }
            }
            content.add(row(tr("remote_wipe_send"), "user-trash-symbolic") { sendWipe() })
            page.add(content)
        }
    }

    private fun row(title: String, icon: String, onClick: () -> Unit): ActionRow {
        val r = ActionRow()
        r.useMarkup = false
        r.title = title
        r.activatable = true
        r.addSuffix(Image.fromIconName(icon))
        r.onActivated { onClick() }
        return r
    }

    private fun nominate() {
        uiScope.launch {
            val contacts = withContext(Dispatchers.IO) { runCatching { container.conversations.listContacts() }.getOrDefault(emptyList()) }
            if (contacts.isEmpty()) {
                Toasts.show(tr("remote_wipe_none_nominated"))
                return@launch
            }
            val labels = contacts.map { "${it.displayName} (${it.onionAddress.value.take(8)}…)" }
            val index = choose(parentWidget, tr("remote_wipe_nominate"), "", labels) ?: return@launch
            val onion = contacts[index].onionAddress.value
            val ok = withContext(Dispatchers.IO) {
                val idKey = container.conversations.identityKeyFor(onion) ?: return@withContext false
                container.storage.remoteWipe.nominate(onion, idKey)
                runCatching { container.conversations.sendWipeNomination(onion, true) }
                true
            }
            Toasts.show(tr(if (ok) "remote_wipe_nominated_toast" else "remote_wipe_no_identity"))
            reload()
        }
    }

    private fun revoke(onion: String) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                container.storage.remoteWipe.revoke(onion)
                runCatching { container.conversations.sendWipeNomination(onion, false) }
            }
            Toasts.show(tr("remote_wipe_revoked"))
            reload()
        }
    }

    private fun sendWipe() {
        uiScope.launch {
            val allowed = withContext(Dispatchers.IO) { runCatching { container.conversations.contactsThisDeviceCanWipe() }.getOrDefault(emptyList()) }
            if (allowed.isEmpty()) {
                inform(parentWidget, tr("remote_wipe_send"), tr("remote_wipe_nobody_nominated_you"))
                return@launch
            }
            val contacts = withContext(Dispatchers.IO) { runCatching { container.conversations.listContacts() }.getOrDefault(emptyList()) }
            val labels = allowed.map { onion -> contacts.firstOrNull { it.onionAddress.value == onion }?.displayName ?: (onion.take(12) + "…") }
            val index = choose(parentWidget, tr("remote_wipe_send"), "", labels) ?: return@launch
            if (!confirm(parentWidget, tr("remote_wipe_send"), tr("remote_wipe_send_body", labels[index]), tr("remote_wipe_send_confirm"), destructive = true)) return@launch
            withContext(Dispatchers.IO) { runCatching { container.conversations.sendRemoteWipe(allowed[index]) } }
            Toasts.show(tr("remote_wipe_sent"))
        }
    }
}
