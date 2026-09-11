package app.niix

import android.content.Context
import app.niix.core.crypto.CryptoEngine
import app.niix.core.messaging.ConversationManager
import app.niix.core.messaging.CoverTrafficScheduler
import app.niix.core.messaging.ExpirySweeper
import app.niix.core.messaging.MessageReceiver
import app.niix.core.relay.RelayManager
import app.niix.core.storage.EncryptedBackup
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
        )
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

    /**
     * Locks the app, and disconnects from the network too if the user has asked for that.
     *
     * Closing the database is not the same as going quiet. With Tor still running the onion
     * service stays published, so the device remains reachable and visibly network-active while
     * the screen shows a locked app -- or, with the disguise enabled, a calculator. A calculator
     * holding open network connections is exactly the observation the disguise exists to avoid.
     *
     * It is a setting rather than unconditional behaviour because the tradeoff is real and cuts
     * both ways: disconnecting means messages do not arrive until you unlock, and reconnecting
     * costs a Tor bootstrap. Defaulting to staying connected keeps a messenger behaving like a
     * messenger; anyone who needs the stronger property can choose it.
     */
    fun lock() {
        // The setting is read BEFORE locking. Settings live in the SQLCipher database, and
        // appLock.lock() closes it -- so reading afterwards always failed and fell back to the
        // default of false, meaning the disconnect-on-lock option silently never did anything
        // however the user had it set. Ordering is the entire fix here.
        val disconnect = runCatching {
            storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)
        }.getOrDefault(false)

        storage.appLock.lock()
        // A locked device must not keep a record of which messages had been read -- everything
        // re-blurs on unlock, which is the behaviour the feature promises.
        app.niix.ui.RevealedMessages.clear()

        if (disconnect) {
            runBlocking { runCatching { transport.stop() } }
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
        val light = runCatching {
            storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_LIGHT_ICON, false)
        }.getOrDefault(false)
        LauncherAlias.apply(
            appContext,
            disguised,
            if (light) LauncherAlias.Icon.LIGHT else LauncherAlias.Icon.DARK,
        )
    }

    fun wipeAllData() {
        // Stop the Tor daemon before anything else. Deleting the onion key from the database
        // does not retract a hidden service that has already been published: the running daemon
        // still holds it and keeps answering on the old address, so until Tor is actually
        // stopped the device remains reachable under the identity that was just "wiped" -- and
        // anyone who had the old address could still connect to it. For a duress wipe that is
        // the difference between an identity being destroyed and merely being unreachable from
        // the app's own UI. Blocking here is deliberate: the wipe must not be reported as
        // complete while the old service is still live.
        runBlocking { runCatching { transport.stop() } }

        storage.wipeAllData()
        crypto.forgetCachedIdentity()
        // The diagnostic buffer describes what the device has been doing. A wipe -- duress or
        // deliberate -- must leave nothing behind that could reconstruct that.
        app.niix.core.model.DiagnosticLog.clear()
        app.niix.ui.RevealedMessages.clear()

        // The calculator's stored state survives a wipe by design -- a calculator that forgets
        // its memory the moment someone unlocks it is a tell. But it must not carry anything
        // from before: it is reset to an empty calculator rather than left holding whatever was
        // last entered, which could itself be revealing.
        runCatching { CalculatorMemory.reset(appContext) }
    }

    fun applyLockTimeoutFromSettings() {
        lock.timeoutMillis = storage.settings.getLong(
            SettingsStore.KEY_LOCK_TIMEOUT_MILLIS,
            lock.timeoutMillis,
        )
    }

    companion object {
        const val SERVICE_PORT = 7600
        private const val LOCAL_NAME = "self"
        private const val ATTACHMENTS_DIR = "attachments"
        private const val COVER_TRAFFIC_MEAN_INTERVAL_MILLIS = 10L * 60 * 1000
    }
}
