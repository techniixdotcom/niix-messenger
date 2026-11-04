package app.niix

import android.content.Context
import android.content.Intent
import app.niix.core.crypto.CryptoEngine
import app.niix.core.messaging.ConversationManager
import app.niix.core.messaging.CoverTrafficScheduler
import app.niix.core.messaging.ExpirySweeper
import app.niix.core.messaging.MessageReceiver
import app.niix.core.nostr.NostrMailbox
import app.niix.core.nostr.TorDialer
import app.niix.core.storage.EncryptedBackup
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    /** This device's own address, once Tor has published it. */
    @Volatile
    var selfOnion: String? = null

    /**
     * The account's address, which contacts know it by. The same as [selfOnion] on the device the
     * account was created on; a linked device has its own address but shares this one.
     */
    @Volatile
    var accountOnion: String? = null

    /** Works out [accountOnion] again. Needs the database open. */
    fun refreshAccountOnion() {
        accountOnion = storage.settings.getString(SettingsStore.KEY_ACCOUNT) ?: selfOnion
        // Safety numbers are pairwise, so they need our own address as well as the peer's.
        crypto.localOnionForFingerprint = accountOnion
    }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Only with a passcode. Without one, locking protects jack shit and reopening costs time.
    val lock: LockController = LockController(
        lockApp = {
            // Decrypted copies of what was opened or shared go a minute after leaving the app,
            // with a passcode or without.
            TempFileGuard.purge(appContext)
            if (storage.appLock.isUnlocked() && storage.appLock.isPasscodeEnabled()) lock()
        },
        holdLock = { storage.appLock.isPasscodeEnabled() && conversations.hasFreshUnsent() },
    )

    val network: NetworkMonitor by lazy { NetworkMonitor(appContext, appScope) }

    val storage: SecureStorage by lazy { SecureStorage.getInstance(appContext) }

    val transport: TorTransport by lazy {
        KmpTorTransport(appContext, appScope, network) {
            storage.onionKeySeed() ?: error("Locked, with no copy of the onion key to receive with")
        }
    }

    val crypto: CryptoEngine by lazy { CryptoEngine.create(storage, LOCAL_NAME) }

    /**
     * Offline delivery through Nostr relays, always over Tor. Android's HTTPS hostname verifier is
     * passed in as a second check on relay certificates.
     */
    val nostr: NostrMailbox by lazy {
        NostrMailbox(
            dialer = TorDialer(transport),
            hostnameVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier(),
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
            nostr = nostr,
        ).also { manager ->
            // The messaging layer spots a valid wipe request but can't erase the database it's
            // reading from, so the wipe happens here.
            manager.onRemoteWipe = {
                appScope.launch {
                    runCatching { wipeAllData() }
                    // Go to setting up a new account. Wiping the data doesn't touch what's already on
                    // screen, and an open conversation still showing would make the wipe pointless.
                    // pwned by your own UI, lol.
                    // CLEAR_TASK drops the back stack too. Android blocks background activity
                    // starts, so this only works while the app is visible, which is the only time
                    // it matters.
                    runCatching {
                        appContext.startActivity(
                            Intent(appContext, app.niix.ui.OnboardingActivity::class.java)
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

    /**
     * Replaces this device's data with a backup, then reconnects as the restored account. The
     * backup brings its own onion key, Nostr inbox and wipe key, and everything still running on
     * the old ones has to go: Tor would otherwise keep answering on the old address until the app
     * next restarted, which on a new phone, the usual reason to restore, is the wrong account
     * entirely. Called off the main thread, since stopping Tor can take seconds.
     */
    fun restoreBackup(passphrase: CharArray, source: java.io.File) {
        storage.backup().import(passphrase, source)
        crypto.forgetCachedIdentity()
        nostr.clear()
        storage.receiveBeacon.destroy()
        runBlocking { runCatching { transport.stop() } }
        selfOnion = null
        accountOnion = null
        conversations.forgetRuntimeState()
        conversations.refreshWipeBeacon()
        ConnectivityService.start(appContext)
    }

    fun lock() {
        // Read the setting before locking: lock() closes the database it lives in.
        val disconnect = runCatching {
            storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)
        }.getOrDefault(false)

        storage.appLock.lock()
        TempFileGuard.purge(appContext)
        // The identity private key is cached in memory for speed. Nothing needs it while locked.
        crypto.forgetCachedIdentity()

        // In the background. lock() runs from onResume on the main thread and stopping Tor can take
        // seconds, long enough for an ANR. Nothing needs to wait for it: the database is already
        // closed, and Tor handles stop and start in order, so an immediate unlock can't be
        // overtaken. (The wipe does block, because it mustn't report success while the old address
        // is still up.)
        if (disconnect) {
            // These connections go over Tor and would just fail until unlock. Clearing also drops
            // the mailbox keys kept in memory for receiving while locked.
            nostr.clear()
            appScope.launch { runCatching { transport.stop() } }
        }
    }

    private val serviceAliveFile: java.io.File get() = java.io.File(appContext.noBackupFilesDir, SERVICE_ALIVE_FILENAME)
    @Volatile private var stopEvidenceChecked = false
    @Volatile private var stoppedGapAtStart: Long? = null

    /**
     * Called by the background service every few minutes while it runs. The first call in a new
     * process looks at the previous mark before overwriting it, since that's the evidence of
     * whether the last process was killed.
     */
    fun recordServiceAlive() {
        if (!stopEvidenceChecked) {
            stopEvidenceChecked = true
            stoppedGapAtStart = backgroundStopGapMillis()
        }
        runCatching { serviceAliveFile.writeText(System.currentTimeMillis().toString()) }
    }

    /** How long Android kept NiiX stopped before this process started, once; null if it didn't. */
    fun takeBackgroundStopGap(): Long? {
        val gap = stoppedGapAtStart ?: if (!stopEvidenceChecked) backgroundStopGapMillis() else null
        stoppedGapAtStart = null
        stopEvidenceChecked = true
        return gap
    }

    /**
     * How long NiiX wasn't running while the phone stayed on, or null.
     *
     * A gap in the marks alone proves nothing: a sleeping phone pauses the service without killing
     * it, and the marks pause with it. What shows the service was killed is that this process
     * started after the last mark, so the process that wrote it is gone, and that the phone hasn't
     * restarted since, which would explain it innocently.
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

    /** Whether the wipe button skips the confirmation. See KEY_WIPE_WITHOUT_CONFIRM. */
    fun wipeWithoutConfirmation(): Boolean = runCatching {
        storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_WIPE_WITHOUT_CONFIRM, false)
    }.getOrDefault(false)

    /**
     * Erases this device and tells the account's other devices to do the same. They're told first,
     * for up to [waitForOtherDevicesMillis] while Tor is still running; whatever isn't reached by
     * then keeps being tried in the background. The duress passcode waits for nothing, since a
     * pause before the decoy appears would be noticed; its wipe goes out once the decoy's Tor is up.
     */
    fun wipeAllData(waitForOtherDevicesMillis: Long = OTHER_DEVICES_WAIT_MILLIS) {
        var otherDevices = runCatching { conversations.prepareWipeOfOtherDevices() }.getOrNull()
        val deadline = System.nanoTime() / 1_000_000 + OTHER_DEVICES_RETRY_MILLIS
        if (otherDevices != null && waitForOtherDevicesMillis > 0) {
            otherDevices = runBlocking {
                withTimeoutOrNull(waitForOtherDevicesMillis) {
                    conversations.deliverWipe(otherDevices!!, System.nanoTime() / 1_000_000 + waitForOtherDevicesMillis)
                } ?: otherDevices
            }
        }
        wipeThisDevice()
        otherDevices?.let { remaining -> appScope.launch { runCatching { conversations.deliverWipe(remaining, deadline) } } }
    }

    /**
     * Erases this device if the inactivity wipe's deadline has passed, and says whether it did.
     * Only this device: a linked phone left in a drawer shouldn't erase the one in use. Blocks
     * while Tor stops, so callers must be off the main thread.
     */
    fun wipeIfInactive(): Boolean {
        if (!runCatching { storage.appLock.inactivityWipeDue() }.getOrDefault(false)) return false
        runCatching { wipeThisDevice() }
        return true
    }

    private fun wipeThisDevice() {
        // A message copied out of a chat stays on the clipboard after the app's data is gone, and
        // on older Android any app can read it. This clears whatever else was copied too, which is
        // an acceptable cost for a wipe. Android may refuse it from the background.
        runCatching {
            appContext.getSystemService(android.content.ClipboardManager::class.java)?.clearPrimaryClip()
        }
        // Stop Tor first. Deleting the onion key from the database doesn't take down a hidden
        // service that's already published; the running daemon would keep answering on the old
        // address. Blocking on purpose, so the wipe isn't reported done while that address is still
        // reachable.
        runBlocking { runCatching { transport.stop() } }
        // Before the database goes: drop relay connections and the mailbox keys held in memory.
        runCatching { nostr.clear() }

        storage.wipeAllData()
        crypto.forgetCachedIdentity()
        // Everything in memory that names the old account. The old onion address in particular
        // would otherwise show up on the next account's "My code" until Tor republished.
        selfOnion = null
        accountOnion = null
        crypto.localOnionForFingerprint = null
        conversations.forgetRuntimeState()
        // The diagnostic log describes what the device has been doing, so it goes too.
        app.niix.core.model.DiagnosticLog.clear()
        appContext.deleteSharedPreferences(app.niix.ui.SCREENSHOT_MIRROR_PREFS)
        serviceAliveFile.delete()
    }

    companion object {
        const val SERVICE_PORT = 7600
        private const val OTHER_DEVICES_WAIT_MILLIS = 15_000L
        private const val OTHER_DEVICES_RETRY_MILLIS = 30L * 60 * 1000
        private const val LOCAL_NAME = "self"
        private const val ATTACHMENTS_DIR = "attachments"
        private const val COVER_TRAFFIC_MEAN_INTERVAL_MILLIS = 10L * 60 * 1000
        /** An opaque name, like the other files that say something about how the app is used. */
        private const val SERVICE_ALIVE_FILENAME = "a1.bin"
        private const val STOPPED_GAP_THRESHOLD_MILLIS = 30L * 60 * 1000
    }
}
