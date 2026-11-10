package app.niix.desktop.ui

import app.niix.core.messaging.CallPhase
import app.niix.core.model.DiagnosticLog
import app.niix.core.messaging.CallState
import app.niix.core.messaging.IncomingNotice
import app.niix.core.storage.SettingsStore
import app.niix.desktop.Background
import app.niix.desktop.Connectivity
import app.niix.desktop.DesktopContainer
import app.niix.desktop.Ringer
import app.niix.desktop.UnlockFlow
import app.niix.desktop.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.Application
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.ToastOverlay
import org.gnome.gdk.ToplevelState
import org.gnome.gio.ApplicationFlags
import org.gnome.gio.SimpleAction
import org.gnome.glib.GLib
import org.gnome.glib.VariantType
import org.gnome.gobject.Value
import org.gnome.gtk.Stack
import org.gnome.gtk.StackTransitionType
import org.gnome.gtk.Widget

/**
 * The application: one window, which shows setup, the lock screen, device linking or the chats,
 * and which hides rather than quits when closed, so messages keep arriving. Quitting is in the menu.
 */
class NiixApp(private val container: DesktopContainer, private val startHidden: Boolean) {

    val app = Application(APP_ID, ApplicationFlags.DEFAULT_FLAGS)
    private var window: ApplicationWindow? = null

    // Widgets only exist once GTK has started, in startup(), never before.
    private lateinit var stack: Stack
    private lateinit var toasts: ToastOverlay
    private lateinit var notifier: Notifier
    private lateinit var autoLock: AutoLock
    private var mainPage: MainPage? = null
    private var unlockPage: UnlockPage? = null
    private var lockedRinging = false
    private val ringer = Ringer()
    private var firstActivation = true
    private var held = false
    private var quitting = false
    private var tray: Tray? = null

    fun run(): Int {
        app.onStartup { startup() }
        app.onActivate { activate() }
        app.onShutdown { container.tempFiles.purge() }
        return app.run(arrayOf())
    }

    private fun startup() {
        Theme.apply(container.paths.filesDir, container.paths.cacheDir)
        stack = Stack()
        toasts = ToastOverlay()
        notifier = Notifier(app) {
            runCatching { container.storage.settings.getBool(SettingsStore.KEY_NOTIFICATION_PRIVACY, true) }.getOrDefault(true)
        }
        addAction("show") { activate(forceShow = true) }
        addAction("quit") { quit() }
        addAction("lock") { lockNow() }
        addAction("decline-call") {
            container.conversations.calls.decline()
            // A call ringing while locked isn't a call yet; it's declined at the doorbell.
            container.conversations.declineLockedRing()
        }
        addAction("answer-call") {
            activate(forceShow = true)
            if (container.storage.appLock.isUnlocked()) {
                container.conversations.calls.answer()
            } else {
                // Picked up as soon as the passcode is in; the lock screen says so.
                container.conversations.answerLockedRing()
            }
        }
        val open = SimpleAction("open-conversation", VariantType("s"))
        open.onActivate { parameter ->
            val id = parameter?.getString(null) ?: return@onActivate
            activate(forceShow = true)
            mainPage?.openConversation(id)
        }
        app.addAction(open)
        startTray()
        app.setAccelsForAction("app.quit", arrayOf("<Control>q"))
        app.setAccelsForAction("app.lock", arrayOf("<Control>l"))

        container.connectivity.listener = object : Connectivity.Listener {
            override fun onMessage(notice: IncomingNotice) {
                uiScope.launch {
                    val showing = window?.isActive == true && mainPage?.isShowing(notice.conversationId) == true
                    if (!showing) notifier.message(notice)
                }
            }

            override fun onCallState(state: CallState) {
                uiScope.launch { showCallState(state) }
            }

            override fun onMissedCall(account: String) {
                uiScope.launch { notifier.missedCall() }
            }
        }
        uiScope.launch {
            // A call ringing while NiiX is locked: it rings and says a call is coming in, and
            // nothing more until the passcode is entered.
            container.conversations.lockedRing.collect { ringing ->
                lockedRinging = ringing
                unlockPage?.setRinging(ringing)
                if (ringing) {
                    notifier.incomingCall()
                    ringer.start()
                } else if (container.conversations.calls.state.value.phase != CallPhase.INCOMING) {
                    notifier.cancelIncomingCall()
                    ringer.stop()
                }
            }
        }
        uiScope.launch {
            container.wiped.collect {
                notifier.withdrawAll()
                showOnboarding()
            }
        }
        if (!held) {
            app.hold()
            held = true
        }
        autoLock = AutoLock(container, ::currentWindow) { lockNow() }
        container.connectivity.start()
        // The inactivity wipe, checked while NiiX keeps running as well as when it starts: a
        // computer left with NiiX open but locked has to be erased on time too.
        GLib.timeoutAddSeconds(GLib.PRIORITY_DEFAULT, INACTIVITY_CHECK_SECONDS) {
            uiScope.launch {
                if (withContext(Dispatchers.IO) { runCatching { container.wipeIfInactive() }.getOrDefault(false) }) {
                    notifier.withdrawAll()
                    showOnboarding()
                }
            }
            GLib.SOURCE_CONTINUE
        }
    }

    private fun currentWindow(): ApplicationWindow? = window

    /**
     * The tray icon, where the desktop has a tray. Closing or minimising the window then leaves
     * NiiX there; without one, closing still only hides the window, and starting NiiX again from
     * the app menu brings it back.
     */
    private fun startTray() {
        val connection = app.dbusConnection ?: return
        val icons = if (Background.inFlatpak) null else java.io.File(container.paths.cacheDir, "icons").absolutePath
        tray = Tray(
            connection = connection,
            iconName = APP_ID,
            iconThemePath = icons,
            title = tr("app_name"),
            items = ::trayItems,
            onActivate = { toggleWindow() },
            onAvailability = { DiagnosticLog.record("tray", if (it) "tray icon shown" else "no tray") },
        ).also { it.start() }
    }

    private fun trayItems(): List<Tray.Item> {
        val items = ArrayList<Tray.Item>()
        items += Tray.Item(TRAY_OPEN, tr("desktop_open")) { activate(forceShow = true) }
        val lockable = runCatching { container.storage.appLock.isPasscodeEnabled() && container.storage.appLock.isUnlocked() }.getOrDefault(false)
        if (lockable) items += Tray.Item(TRAY_LOCK, tr("desktop_lock_now")) { lockNow() }
        items += Tray.Item(TRAY_SEPARATOR, "") {}
        items += Tray.Item(TRAY_QUIT, tr("desktop_quit")) { quit() }
        return items
    }

    /** A click on the tray icon: the window comes to the front, or goes away if it's already there. */
    private fun toggleWindow() {
        val current = window
        if (current != null && current.visible && current.isActive) {
            current.visible = false
        } else {
            activate(forceShow = true)
        }
    }

    /**
     * Minimised with a tray to go to: the window leaves the taskbar and stays in the tray. Only
     * where the desktop tells a window it was minimised, which X11 does and most Wayland desktops
     * don't; there, closing the window is the way to the tray.
     */
    private fun watchMinimise(target: ApplicationWindow) {
        target.onRealize {
            val surface = target.surface ?: return@onRealize
            surface.onNotify("state") {
                if (isMinimised(surface) && tray?.available == true && target.visible && !quitting) {
                    // Not from inside the notification: the surface is still changing state.
                    GLib.idleAddOnce { if (!quitting && window === target) target.visible = false }
                }
            }
        }
    }

    /** The window's state as GDK reports it, read as plain flags. */
    private fun isMinimised(surface: org.gnome.gdk.Surface): Boolean = runCatching {
        val value = Value()
        value.init(ToplevelState.getType())
        try {
            surface.getProperty("state", value)
            value.flags and ToplevelState.MINIMIZED.value != 0
        } finally {
            value.unset()
        }
    }.getOrDefault(false)

    private fun addAction(name: String, handler: () -> Unit) {
        val action = SimpleAction(name, null)
        action.onActivate { handler() }
        app.addAction(action)
    }

    private fun activate(forceShow: Boolean = false) {
        val existing = window
        if (existing == null) {
            val created = ApplicationWindow(app)
            created.title = tr("app_name")
            created.setDefaultSize(1100, 760)
            created.setSizeRequest(360, 480)
            stack.transitionType = StackTransitionType.CROSSFADE
            toasts.child = stack
            created.content = toasts
            Toasts.overlay = toasts
            created.onCloseRequest {
                created.visible = false
                Background.onWindowHidden(app, container)
                true
            }
            window = created
            watchMinimise(created)
            autoLock.attach(created)
            showStart()
        }
        val hideThisTime = startHidden && firstActivation && !forceShow
        firstActivation = false
        if (!hideThisTime) window?.present()
    }

    /** Picks the first screen: setup, the lock screen, or straight in without a passcode. */
    private fun showStart() {
        uiScope.launch {
            val appLock = container.storage.appLock
            if (withContext(Dispatchers.IO) { container.wipeIfInactive() }) {
                showOnboarding()
                return@launch
            }
            when {
                !appLock.isSetUp() -> showOnboarding()
                appLock.isUnlocked() -> afterUnlock()
                appLock.isPasscodeEnabled() -> showUnlock()
                else -> {
                    show(LoadingPage(tr("step_open")).widget, "loading")
                    if (UnlockFlow.openWithoutPasscode(container)) afterUnlock() else showUnlock()
                }
            }
        }
    }

    /** Stops the chats screen's work before another screen replaces it. */
    private fun dropMain() {
        mainPage?.dispose()
        mainPage = null
    }

    fun showOnboarding() {
        unlockPage = null
        dropMain()
        show(OnboardingPage(container, onDone = { linking -> if (linking) showLink() else afterUnlock() }).widget, "onboarding")
    }

    fun showUnlock() {
        dropMain()
        val page = UnlockPage(container, onUnlocked = { afterUnlock() })
        unlockPage = page
        page.setRinging(lockedRinging)
        show(page.widget, "unlock")
    }

    private fun showLink() {
        unlockPage = null
        dropMain()
        container.connectivity.start()
        show(LinkPage(container, onFinished = { afterUnlock() }).widget, "link")
    }

    /** Everything after the database is open: the network catches up, then the chats show. */
    fun afterUnlock() {
        uiScope.launch {
            val linkPending = withContext(Dispatchers.IO) {
                runCatching { container.storage.settings.getBool(SettingsStore.KEY_LINK_PENDING, false) }.getOrDefault(false)
            }
            if (linkPending) {
                showLink()
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { container.storage.appLock.recordUse() }
                runCatching { container.storage.lockedInbox.prepare() }
            }
            container.connectivity.start()
            unlockPage = null
            dropMain()
            val page = MainPage(container, this@NiixApp)
            mainPage = page
            show(page.widget, "main")
        }
    }

    fun lockNow() {
        if (!container.storage.appLock.isPasscodeEnabled() || !container.storage.appLock.isUnlocked()) return
        dropMain()
        notifier.withdrawAll()
        uiScope.launch {
            withContext(Dispatchers.IO) { container.lock() }
            showUnlock()
        }
    }

    fun quit() {
        if (quitting) return
        quitting = true
        notifier.withdrawAll()
        ringer.stop()
        tray?.dispose()
        window?.visible = false
        mainPage?.dispose()
        uiScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.shutdown() } }
            if (held) {
                app.release()
                held = false
            }
            app.quit()
        }
    }

    fun window(): ApplicationWindow? = window

    private fun show(widget: Widget, name: String) {
        stack.getChildByName(name)?.let(stack::remove)
        stack.addNamed(widget, name)
        stack.visibleChildName = name
        // Screens that aren't shown any more are dropped, so nothing they hold stays in memory.
        GLib.timeoutAddOnce(STACK_CLEANUP_MILLIS) {
            var child = stack.firstChild
            while (child != null) {
                val next = child.nextSibling
                if (child != stack.visibleChild) stack.remove(child)
                child = next
            }
        }
    }

    private fun showCallState(state: CallState) {
        if (state.phase == CallPhase.INCOMING) {
            notifier.incomingCall()
            ringer.start()
        } else if (!lockedRinging) {
            notifier.cancelIncomingCall()
            ringer.stop()
        }
        mainPage?.showCallState(state)
    }

    companion object {
        const val APP_ID = "app.niix.NiiX"
        private const val STACK_CLEANUP_MILLIS = 600
        private const val INACTIVITY_CHECK_SECONDS = 15 * 60
        private const val TRAY_OPEN = 1
        private const val TRAY_LOCK = 2
        private const val TRAY_SEPARATOR = 3
        private const val TRAY_QUIT = 4
    }
}
