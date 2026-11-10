package app.niix.desktop.ui

import app.niix.core.storage.AppLockManager
import app.niix.core.storage.SettingsStore
import app.niix.desktop.DesktopContainer
import app.niix.desktop.UnlockFlow
import app.niix.desktop.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.Clamp
import org.gnome.adw.EntryRow
import org.gnome.adw.PasswordEntryRow
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.StatusPage
import org.gnome.adw.ToolbarView
import org.gnome.adw.HeaderBar
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Label
import org.gnome.gtk.PasswordEntry
import org.gnome.gtk.Picture
import org.gnome.gtk.ContentFit
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Spinner
import org.gnome.gtk.Widget

/** A spinner and one line, while something slow happens before any screen can show. */
class LoadingPage(message: String) {
    val widget: Widget

    init {
        val box = vbox(18)
        box.valign = Align.CENTER
        box.halign = Align.CENTER
        val spinner = Spinner()
        spinner.spinning = true
        spinner.setSizeRequest(48, 48)
        box.append(spinner)
        box.append(Label(message).apply { addCssClass("dim-label") })
        widget = box
    }
}

private fun brandHeader(subtitle: String): Box {
    val box = vbox(6)
    box.halign = Align.CENTER
    box.append(Label("NiiX").apply { addCssClass("niix-brand") })
    box.append(wrappingLabel(subtitle, "niix-subtitle").apply { justify = org.gnome.gtk.Justification.CENTER; xalign = 0.5f })
    return box
}

private fun scrollingClamp(content: Widget, width: Int = 480): Widget {
    val clamp = Clamp()
    clamp.maximumSize = width
    clamp.child = content
    clamp.margins(24, 18)
    val scroller = ScrolledWindow()
    scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
    scroller.child = clamp
    scroller.vexpand = true
    val view = ToolbarView()
    val header = HeaderBar()
    header.showTitle = false
    view.addTopBar(header)
    view.content = scroller
    return view
}

/**
 * First start: a new account here, or this computer joining the account on the phone. The passcode
 * and duress passcode work as on the phone, and are this device's own either way.
 */
class OnboardingPage(private val container: DesktopContainer, private val onDone: (linking: Boolean) -> Unit) {

    val widget: Widget
    private val username = EntryRow()
    private val passcode = PasswordEntryRow()
    private val confirm = PasswordEntryRow()
    private val duress = PasswordEntryRow()
    private val create = Button.withLabel(tr("onboarding_create"))
    private val link = Button.withLabel(tr("desktop_link_to_phone"))
    private val console = vbox(2)
    private var busy = false

    init {
        val content = vbox(18)
        content.append(brandHeader(tr("onboarding_subtitle")))

        username.title = tr("hint_username")
        passcode.title = tr("hint_passcode_digits")
        confirm.title = tr("hint_passcode_confirm")
        duress.title = tr("hint_duress_optional")
        val group = PreferencesGroup()
        listOf(username, passcode, confirm, duress).forEach(group::add)
        content.append(group)
        content.append(wrappingLabel(tr("onboarding_hint"), "dim-label", "caption"))

        create.addCssClass("suggested-action")
        create.addCssClass("pill")
        create.onClicked { submit(linking = false) }
        link.addCssClass("pill")
        link.onClicked { submit(linking = true) }
        val buttons = vbox(10)
        buttons.halign = Align.CENTER
        buttons.append(create)
        buttons.append(link)
        content.append(buttons)
        content.append(wrappingLabel(tr("desktop_link_explain"), "dim-label", "caption"))
        console.addCssClass("console")
        content.append(console)
        passcode.onEntryActivated { confirm.grabFocus() }
        confirm.onEntryActivated { submit(linking = false) }
        widget = scrollingClamp(content)
    }

    private fun toast(message: String) = Toasts.show(message)

    private fun submit(linking: Boolean) {
        if (busy) return
        val name = username.text.trim()
        val pass = passcode.text
        val again = confirm.text
        val duressCode = duress.text
        if (pass.isNotEmpty() && pass.length < AppLockManager.MIN_PASSCODE_LENGTH) {
            toast(tr("lock_too_short", AppLockManager.MIN_PASSCODE_LENGTH)); return
        }
        if (pass != again) {
            toast(tr("lock_mismatch")); return
        }
        if (duressCode.isNotEmpty() && pass.isEmpty()) {
            toast(tr("duress_needs_passcode")); return
        }
        if (duressCode.isNotEmpty() && (duressCode.length < AppLockManager.MIN_PASSCODE_LENGTH || duressCode == pass)) {
            toast(tr("duress_invalid")); return
        }
        setBusy(true)
        var child = console.firstChild
        while (child != null) {
            val next = child.nextSibling
            console.remove(child)
            child = next
        }
        uiScope.launch {
            val ok = createAccount(name, pass, duressCode, linking)
            listOf(passcode, confirm, duress).forEach { it.text = "" }
            if (ok) {
                onDone(linking)
            } else {
                toast(tr("onboarding_failed"))
                setBusy(false)
            }
        }
    }

    private suspend fun createAccount(name: String, pass: String, duressCode: String, linking: Boolean): Boolean {
        val appLock = container.storage.appLock
        val created = step(if (pass.isEmpty()) "step_setup_device" else "step_setup_passcode") {
            if (pass.isEmpty()) {
                appLock.setUpWithoutPasscode()
            } else {
                val chars = pass.toCharArray()
                try { appLock.setPasscode(chars) } finally { chars.fill('\u0000') }
            }
        }
        if (!created) return false
        if (linking) {
            container.storage.settings.setBool(SettingsStore.KEY_LINK_PENDING, true)
        } else {
            step("step_profile") {
                container.storage.settings.setString(SettingsStore.KEY_USERNAME, name.ifEmpty { tr("default_username") })
                true
            }
        }
        if (duressCode.isNotEmpty()) {
            val stored = step("step_duress") {
                val chars = duressCode.toCharArray()
                try { appLock.setDuressPasscode(chars) } finally { chars.fill('\u0000') }
            }
            if (!stored) return false
        }
        if (!linking) step("step_keys") { container.crypto.ensureKeysInitialized(); true }
        console.append(Label(tr("step_ready")).apply { xalign = 0f })
        return true
    }

    private suspend fun step(label: String, work: () -> Boolean): Boolean {
        val line = Label(tr(label) + " …")
        line.xalign = 0f
        console.append(line)
        val done = withContext(Dispatchers.IO) { runCatching(work).getOrDefault(false) }
        line.label = tr(label) + "  " + if (done) tr("console_ok") else tr("console_failed")
        return done
    }

    private fun setBusy(value: Boolean) {
        busy = value
        create.sensitive = !value
        link.sensitive = !value
        create.label = tr(if (value) "onboarding_creating" else "onboarding_create")
        listOf(username, passcode, confirm, duress).forEach { it.sensitive = !value }
    }
}

/** The lock screen. Failed attempts slow down as on the phone. */
class UnlockPage(private val container: DesktopContainer, private val onUnlocked: () -> Unit) {

    val widget: Widget
    private val entry = PasswordEntry()
    private val button = Button.withLabel(tr("desktop_unlock"))
    private val status = Label("")
    private var busy = false
    private var throttleJob: Job? = null
    private val ringBox = vbox(8)
    private var ringing = false

    init {
        val content = vbox(18)
        content.valign = Align.CENTER
        content.append(brandHeader(tr("passcode_prompt")))
        entry.showPeekIcon = true
        entry.setStringProperty("placeholder-text", tr("passcode_placeholder"))
        entry.onActivate { submit() }
        content.append(entry)
        button.addCssClass("suggested-action")
        button.addCssClass("pill")
        button.halign = Align.CENTER
        button.onClicked { submit() }
        content.append(button)
        status.addCssClass("dim-label")
        status.wrap = true
        content.append(status)
        ringBox.addCssClass("call-panel")
        ringBox.append(wrappingLabel(tr("desktop_call_locked")).apply { justify = org.gnome.gtk.Justification.CENTER; xalign = 0.5f })
        val decline = Button.withLabel(tr("call_decline"))
        decline.addCssClass("destructive-action")
        decline.halign = Align.CENTER
        decline.onClicked { container.conversations.declineLockedRing() }
        ringBox.append(decline)
        ringBox.visible = false
        content.append(ringBox)
        widget = scrollingClamp(content, 360)
        uiScope.launch {
            delay(100)
            entry.grabFocus()
        }
        startThrottleCountdown()
    }

    /** A call is ringing behind the lock: say so, and offer to decline. Unlocking answers it. */
    fun setRinging(ringing: Boolean) {
        this.ringing = ringing
        ringBox.visible = ringing
    }

    private fun submit() {
        if (busy) return
        val text = entry.text
        // Unlocking while it rings is answering: the call is picked up once it can be read.
        if (ringing) container.conversations.answerLockedRing()
        if (text.length < AppLockManager.MIN_PASSCODE_LENGTH) {
            status.label = tr("passcode_too_short", AppLockManager.MIN_PASSCODE_LENGTH)
            return
        }
        busy = true
        button.sensitive = false
        entry.sensitive = false
        status.label = tr("step_unlock")
        val chars = text.toCharArray()
        entry.text = ""
        uiScope.launch {
            when (UnlockFlow.attempt(container, chars)) {
                UnlockFlow.Outcome.UNLOCKED -> onUnlocked()
                UnlockFlow.Outcome.WRONG -> {
                    status.label = tr("console_incorrect")
                    reset()
                    startThrottleCountdown()
                }
                UnlockFlow.Outcome.THROTTLED -> {
                    reset()
                    startThrottleCountdown()
                }
            }
        }
    }

    private fun reset() {
        busy = false
        button.sensitive = true
        entry.sensitive = true
        entry.grabFocus()
    }

    private fun startThrottleCountdown() {
        throttleJob?.cancel()
        throttleJob = uiScope.launch {
            while (isActive) {
                val remaining = withContext(Dispatchers.IO) {
                    runCatching { container.storage.appLock.throttleRemainingMillis() }.getOrDefault(0L)
                }
                if (remaining <= 0) {
                    if (!busy) {
                        button.sensitive = true
                        entry.sensitive = true
                    }
                    return@launch
                }
                button.sensitive = false
                status.label = tr("passcode_throttled", ((remaining + 999) / 1000).toInt())
                delay(500)
            }
        }
    }
}

/**
 * Joining the phone's account: a QR code for the phone to scan in Settings › Linked devices. The
 * code needs this computer's onion address, so it appears once Tor is up.
 */
class LinkPage(private val container: DesktopContainer, private val onFinished: () -> Unit) {

    val widget: Widget
    private val picture = Picture()
    private val status = wrappingLabel(tr("link_waiting_network"), "dim-label")
    private var job: Job? = null
    private var linkedJob: Job? = null

    init {
        val content = vbox(18)
        content.append(brandHeader(tr("link_title")))
        content.append(wrappingLabel(tr("link_instructions")))
        picture.contentFit = ContentFit.CONTAIN
        picture.setSizeRequest(300, 300)
        picture.halign = Align.CENTER
        picture.visible = false
        picture.addCssClass("qr-frame")
        content.append(picture)
        content.append(status)
        val later = Button.withLabel(tr("desktop_link_later"))
        later.addCssClass("pill")
        later.halign = Align.CENTER
        later.onClicked { leave() }
        content.append(later)
        widget = scrollingClamp(content)
        showInvite()
        linkedJob = uiScope.launch {
            container.conversations.linked.collect {
                withContext(Dispatchers.IO) { runCatching { container.refreshAccountOnion() } }
                Toasts.show(tr("link_done"))
                dispose()
                onFinished()
            }
        }
    }

    private fun showInvite() {
        job = uiScope.launch {
            while (isActive) {
                val invite = withContext(Dispatchers.IO) { runCatching { container.conversations.startLinkInvite() }.getOrNull() }
                if (invite != null) {
                    QrCodes.texture(invite.encode())?.let {
                        picture.paintable = it
                        picture.visible = true
                    }
                    status.label = tr("link_waiting_scan")
                    return@launch
                }
                delay(1500)
            }
        }
    }

    /** Backing out means using this computer on its own for now; it can still join later. */
    private fun leave() {
        dispose()
        uiScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.storage.settings.remove(SettingsStore.KEY_LINK_PENDING) } }
            withContext(Dispatchers.IO) {
                if (container.storage.settings.getString(SettingsStore.KEY_USERNAME) == null) {
                    container.storage.settings.setString(SettingsStore.KEY_USERNAME, tr("default_username"))
                }
                runCatching { container.crypto.ensureKeysInitialized() }
            }
            onFinished()
        }
    }

    private fun dispose() {
        job?.cancel()
        linkedJob?.cancel()
        container.conversations.cancelLinkInvite()
    }
}

/** Shown with a status page when something can't be done, so a screen is never just empty. */
fun statusPage(icon: String, title: String, description: String): StatusPage {
    val page = StatusPage()
    page.iconName = icon
    page.title = title
    page.description = org.gnome.glib.GLib.markupEscapeText(description, -1)
    return page
}
