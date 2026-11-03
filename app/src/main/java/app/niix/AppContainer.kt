package app.niix

import android.content.Context
import android.content.Intent
import app.niix.core.crypto.CryptoEngine
import app.niix.core.messaging.ConversationManager
import app.niix.core.messaging.CoverTrafficScheduler
import app.niix.core.messaging.ExpirySweeper
import app.niix.core.messaging.MessageReceiver
import app.niix.core.relay.RelayManager
import app.niix.core.storage.EncryptedBackup
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import app.niix.core.transport.TorTransport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    val context: Context get() = appContext

    @Volatile
    var selfOnion: String? = null

    val lock: LockController = LockController()

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val storage: SecureStorage by lazy { SecureStorage.getInstance(appContext) }

    val transport: TorTransport by lazy {
        KmpTorTransport(appContext, appScope, SERVICE_PORT) { storage.onionIdentity.getOrCreate() }
    }

    val crypto: CryptoEngine by lazy { CryptoEngine.create(storage, LOCAL_NAME) }

    val relay: RelayManager by lazy {
        RelayManager(
            storage = storage,
            crypto = crypto,
            transport = transport,
            servicePort = SERVICE_PORT,
            selfOnionProvider = { selfOnion },
        )
    }

    val conversations: ConversationManager by lazy {
        ConversationManager(
            storage = storage,
            crypto = crypto,
            transport = transport,
            attachmentsDir = File(appContext.filesDir, ATTACHMENTS_DIR),
            servicePort = SERVICE_PORT,
            selfOnionProvider = { selfOnion },
            sendScope = appScope,
            relay = relay,
        ).also { manager ->
 // The messaging layer detects a valid wipe request but cannot carry it out, it
 // would be erasing the database it is reading from. The actual wipe belongs here,
 // where wipeAllData already stops Tor, clears the keystore and destroys the data.
            manager.onRemoteWipe = {
                appScope.launch {
                    runCatching { wipeAllData() }
                    runCatching { CalculatorMemory.reset(appContext) }
                    applyLauncherIcon()

 // Send the UI back to the lock screen.
 //
 // Wiping the data does not touch what is already drawn, so an open
 // conversation stayed on screen reading normally until the user next tried to
 // do something. For this feature that is the whole failure: the point of a
 // remote wipe is that someone holding the phone sees nothing, and a visible
 // conversation defeats it no matter how thoroughly the database was erased.
 //
 // CLEAR_TASK discards the back stack, so nothing behind the lock screen is
 // still holding data that no longer exists.
 //
 // Android blocks background activity starts, so this only succeeds when the
 // app is actually on screen, which is exactly when it is needed. If the app
 // is backgrounded there is nothing visible to hide, and the lock screen is
 // what the user meets on their next launch regardless.
                    runCatching {
                        val target = if (storage.appLock.isDisguiseEnabled()) {
                            app.niix.ui.CalculatorActivity::class.java
                        } else {
                            app.niix.ui.PasscodeActivity::class.java
                        }
                        appContext.startActivity(
                            Intent(appContext, target)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                                ),
                        )
                    }
                }
            }
        }
    }

    val receiver: MessageReceiver by lazy { MessageReceiver(transport, conversations, appScope) }

    val expirySweeper: ExpirySweeper by lazy { ExpirySweeper(storage) }

    val coverTraffic: CoverTrafficScheduler by lazy {
        CoverTrafficScheduler(
            meanIntervalMillis = COVER_TRAFFIC_MEAN_INTERVAL_MILLIS,
            sendDummy = { conversations.sendCoverTraffic() },
        )
    }

    fun backup(): EncryptedBackup = storage.backup()

    fun restoreBackup(passphrase: CharArray, source: java.io.File) {
        storage.backup().import(passphrase, source)
        crypto.forgetCachedIdentity()
    }

    fun lock() {
 // The setting is read BEFORE locking. Settings live in the SQLCipher database, and
 // appLock.lock() closes it, so reading afterwards always failed and fell back to the
 // default of false, meaning the disconnect-on-lock option silently never did anything
 // however the user had it set. Ordering is the entire fix here.
        val disconnect = runCatching {
            storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)
        }.getOrDefault(false)

        storage.appLock.lock()

        // In the background, not blocking. lock() is called from onResume on the main thread, and
        // stopping Tor can take seconds, long enough for Android to report the app as not
        // responding. Nothing here needs to wait: the database is already closed above, and
        // Tor's runtime carries out stop and start requests in the order they are made, so an
        // unlock straight afterwards cannot be overtaken by this stop. (The wipe is different and
        // does block, because it must not report success while the old address is still live.)
        if (disconnect) {
            appScope.launch { runCatching { transport.stop() } }
        }
    }

    /**
     * Applies the correct launcher entry for the current settings.
     *
     * Every caller previously passed only the disguise flag, which meant the icon choice was
     * silently reset to the default whenever the disguise was toggled. Resolving both here means
     * there is one place that knows what the launcher should look like.
     */
    fun applyLauncherIcon() {
        val disguised = runCatching { storage.appLock.isDisguiseEnabled() }.getOrDefault(false)
        LauncherAlias.apply(appContext, disguised)
    }

    /** Whether the wipe button should act without asking. See KEY_WIPE_WITHOUT_CONFIRM. */
    fun wipeWithoutConfirmation(): Boolean = runCatching {
        storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_WIPE_WITHOUT_CONFIRM, false)
    }.getOrDefault(false)

    fun wipeAllData() {
        // Clear the system clipboard first. A message copied out of a chat stays there after the
        // app's own data is gone, and on older Android any app can read it. This also clears
        // whatever else was copied, which is an acceptable cost for a wipe.
        runCatching {
            appContext.getSystemService(android.content.ClipboardManager::class.java)?.clearPrimaryClip()
        }
 // Stop the Tor daemon before anything else. Deleting the onion key from the database
 // does not retract a hidden service that has already been published: the running daemon
 // still holds it and keeps answering on the old address, so until Tor is actually
 // stopped the device remains reachable under the identity that was just "wiped", and
 // anyone who had the old address could still connect to it. For a duress wipe that is
 // the difference between an identity being destroyed and merely being unreachable from
 // the app's own UI. Blocking here is deliberate: the wipe must not be reported as
 // complete while the old service is still live.
        runBlocking { runCatching { transport.stop() } }

        storage.wipeAllData()
        crypto.forgetCachedIdentity()
 // The diagnostic buffer describes what the device has been doing. A wipe, duress or
 // deliberate, must leave nothing behind that could reconstruct that.
        app.niix.core.model.DiagnosticLog.clear()

 // The calculator's stored state survives a wipe by design, a calculator that forgets
 // its memory the moment someone unlocks it is a tell. But it must not carry anything
 // from before: it is reset to an empty calculator rather than left holding whatever was
 // last entered, which could itself be revealing.
        runCatching { CalculatorMemory.reset(appContext) }
    }

    /**
     * Applies the configured lock timeout, keeping the current value if it cannot be read.
     *
     * Called from onResume, so an exception here stops the screen being drawn at all. The
     * database is not always open at that point, and failing to update a timeout is a far
     * smaller problem than an app that shows nothing.
     */
    private val serviceAliveFile: java.io.File get() = java.io.File(appContext.noBackupFilesDir, "a1.bin")

    @Volatile private var stopEvidenceChecked = false
    @Volatile private var stoppedGapAtStart: Long? = null

    /**
     * Called by the background service every few minutes while it runs. The first call in a new
     * process looks at the previous mark before overwriting it, since that is the evidence of
     * whether the last process was killed.
     */
    fun recordServiceAlive() {
        if (!stopEvidenceChecked) {
            stopEvidenceChecked = true
            stoppedGapAtStart = backgroundStopGapMillis()
        }
        runCatching { serviceAliveFile.writeText(System.currentTimeMillis().toString()) }
    }

    /** The stopped gap found for this process, once; null if none or already reported. */
    fun takeBackgroundStopGap(): Long? {
        val gap = stoppedGapAtStart ?: if (!stopEvidenceChecked) backgroundStopGapMillis() else null
        stoppedGapAtStart = null
        stopEvidenceChecked = true
        return gap
    }

    /**
     * How long NiiX was not running while the phone stayed on, or null.
     *
     * A gap in the marks alone proves nothing: a sleeping phone pauses the service without
     * killing it, and the marks pause with it. What shows the service was killed is that this
     * process started after the last mark, meaning the process that wrote it is gone, and that
     * the phone has not restarted since, which would explain it innocently. Nothing can restart
     * the connection by itself after that, because the Tor address key is in the encrypted
     * database, so the useful response is to notice afterwards and explain.
     */
    private fun backgroundStopGapMillis(): Long? = runCatching {
        val last = serviceAliveFile.readText().trim().toLong()
        val nowWall = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val bootedAt = nowWall - elapsed
        val processStartedAt = nowWall - (elapsed - android.os.Process.getStartElapsedRealtime())
        val gap = processStartedAt - last
        if (processStartedAt > last && bootedAt < last && gap > STOPPED_GAP_THRESHOLD_MILLIS) gap else null
    }.getOrNull()

    /**
     * Wipes everything if the inactivity deadline has passed, and says whether it did.
     *
     * Blocks while Tor stops, like any wipe, so callers must be off the main thread. Uses the same
     * steps as a remote wipe, so the app comes back exactly as a fresh install would.
     */
    fun wipeIfInactive(): Boolean {
        val due = runCatching { storage.appLock.inactivityWipeDue() }.getOrDefault(false)
        if (!due) return false
        runCatching { wipeAllData() }
        runCatching { CalculatorMemory.reset(appContext) }
        applyLauncherIcon()
        return true
    }

    /**
     * Whether update checks go through Tor.
     *
     * The user's setting, except that the calculator disguise forces Tor. A plain request to GitHub
     * for NiiX releases tells anyone watching the network that this phone runs NiiX, which is the
     * one thing the disguise exists to hide. Plain internet stays the default otherwise, because it
     * is faster and more reliable for getting security fixes out.
     */
    fun updateChecksOverTor(): Boolean = runCatching {
        storage.settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, false) || storage.appLock.isDisguiseEnabled()
    }.getOrDefault(true)

    fun applyLockTimeoutFromSettings() {
        runCatching {
            lock.timeoutMillis = storage.settings.getLong(
                SettingsStore.KEY_LOCK_TIMEOUT_MILLIS,
                lock.timeoutMillis,
            )
        }
    }

    companion object {
        /** Longer than the heartbeat interval plus slack, so a normal pause never counts. */
        private const val STOPPED_GAP_THRESHOLD_MILLIS = 30L * 60 * 1000
        const val SERVICE_PORT = 7600
        private const val LOCAL_NAME = "self"
        private const val ATTACHMENTS_DIR = "attachments"
        private const val COVER_TRAFFIC_MEAN_INTERVAL_MILLIS = 10L * 60 * 1000
    }
}
