package app.niix.desktop

import app.niix.core.crypto.CryptoEngine
import app.niix.core.messaging.ConversationManager
import app.niix.core.messaging.CoverTrafficScheduler
import app.niix.core.messaging.ExpirySweeper
import app.niix.core.messaging.MessageReceiver
import app.niix.core.model.DiagnosticLog
import app.niix.core.nostr.NostrMailbox
import app.niix.core.nostr.TorDialer
import app.niix.core.storage.EncryptedBackup
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import app.niix.core.storage.StoragePaths
import app.niix.core.transport.TorTransport
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the desktop app is made of, wired the way AppContainer wires the phone app: the same
 * storage, crypto, Tor transport, Nostr mailbox and messaging, with the desktop's own folders,
 * keyring and network monitor underneath.
 */
class DesktopContainer(val paths: StoragePaths) {

    /** This device's own address, once Tor has published it. */
    @Volatile
    var selfOnion: String? = null

    /** The account's address, which contacts know it by. A linked device shares the account's. */
    @Volatile
    var accountOnion: String? = null

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tempFiles = TempFiles(paths.cacheDir)

    val network: DesktopNetworkMonitor by lazy { DesktopNetworkMonitor(appScope) }

    val storage: SecureStorage by lazy { SecureStorage.getInstance(paths) }

    val transport: TorTransport by lazy {
        DesktopTorTransport(paths.filesDir, appScope, network) {
            storage.onionKeySeed() ?: error("Locked, with no copy of the onion key to receive with")
        }
    }

    val crypto: CryptoEngine by lazy { CryptoEngine.create(storage, LOCAL_NAME) }

    /**
     * Offline delivery through Nostr relays, always over Tor. No extra hostname verifier: the JVM's
     * HTTPS endpoint identification already checks the relay's certificate against its name, and
     * the JVM's default verifier would refuse every relay.
     */
    val nostr: NostrMailbox by lazy { NostrMailbox(dialer = TorDialer(transport), hostnameVerifier = null) }

    private val _wiped = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after this device was erased by a remote wipe, so the UI can go back to setup. */
    val wiped: SharedFlow<Unit> = _wiped.asSharedFlow()

    val conversations: ConversationManager by lazy {
        ConversationManager(
            storage = storage,
            crypto = crypto,
            transport = transport,
            attachmentsDir = storage.attachmentsDir(),
            servicePort = SERVICE_PORT,
            selfOnionProvider = { selfOnion },
            sendScope = appScope,
            nostr = nostr,
            callAudio = DesktopCallAudio(),
        ).also { manager ->
            manager.onRemoteWipe = {
                appScope.launch {
                    runCatching { wipeAllData() }
                    _wiped.tryEmit(Unit)
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

    val connectivity: Connectivity by lazy { Connectivity(this) }

    /** Works out [accountOnion] again. Needs the database open. */
    fun refreshAccountOnion() {
        accountOnion = storage.settings.getString(SettingsStore.KEY_ACCOUNT) ?: selfOnion
        crypto.localOnionForFingerprint = accountOnion
    }

    /**
     * What a contact needs to add this account: "<account address>|<identity key>", the same code
     * the phone app shows. Null until Tor has published this device's address.
     */
    fun shareCode(): String? {
        crypto.ensureKeysInitialized()
        val onion = accountOnion ?: return null
        return "$onion|" + Base64.getEncoder().encodeToString(crypto.localIdentityKey())
    }

    fun backup(): EncryptedBackup = storage.backup()

    /** Replaces this device's data with a backup, then reconnects as the restored account. */
    fun restoreBackup(passphrase: CharArray, source: File) {
        storage.backup().import(passphrase, source)
        crypto.forgetCachedIdentity()
        nostr.clear()
        storage.receiveBeacon.destroy()
        runBlocking { runCatching { connectivity.stopAndWait() } }
        selfOnion = null
        accountOnion = null
        conversations.forgetRuntimeState()
        conversations.refreshWipeBeacon()
        connectivity.start()
    }

    /** Locks: closes the database, drops keys held in memory and deletes plaintext copies. */
    fun lock() {
        val disconnect = runCatching {
            storage.settings.getBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)
        }.getOrDefault(false)
        storage.appLock.lock()
        tempFiles.purge()
        crypto.forgetCachedIdentity()
        if (disconnect) {
            nostr.clear()
            appScope.launch { runCatching { connectivity.stopAndWait() } }
        }
    }

    fun wipeWithoutConfirmation(): Boolean = runCatching {
        storage.settings.getBool(SettingsStore.KEY_WIPE_WITHOUT_CONFIRM, false)
    }.getOrDefault(false)

    /**
     * Erases this device and tells the account's other devices to do the same: first for up to
     * [waitForOtherDevicesMillis] while Tor still runs, then in the background for whatever wasn't
     * reached. The duress passcode waits for nothing. Blocks, so never on the UI thread.
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

    /** Erases this device if the inactivity wipe's deadline has passed. Blocks while Tor stops. */
    fun wipeIfInactive(): Boolean {
        if (!runCatching { storage.appLock.inactivityWipeDue() }.getOrDefault(false)) return false
        runCatching { wipeThisDevice() }
        return true
    }

    private fun wipeThisDevice() {
        runBlocking { runCatching { connectivity.stopAndWait() } }
        runCatching { nostr.clear() }
        storage.wipeAllData()
        tempFiles.purge()
        crypto.forgetCachedIdentity()
        selfOnion = null
        accountOnion = null
        crypto.localOnionForFingerprint = null
        conversations.forgetRuntimeState()
        DiagnosticLog.clear()
    }

    /** Stops everything for the app to quit. */
    fun shutdown() {
        runBlocking { withTimeoutOrNull(SHUTDOWN_WAIT_MILLIS) { runCatching { connectivity.stopAndWait() } } }
        tempFiles.purge()
        runCatching { storage.appLock.lock() }
    }

    companion object {
        const val SERVICE_PORT = 7600
        private const val OTHER_DEVICES_WAIT_MILLIS = 15_000L
        private const val OTHER_DEVICES_RETRY_MILLIS = 30L * 60 * 1000
        private const val LOCAL_NAME = "self"
        private const val COVER_TRAFFIC_MEAN_INTERVAL_MILLIS = 10L * 60 * 1000
        private const val SHUTDOWN_WAIT_MILLIS = 10_000L
    }
}
