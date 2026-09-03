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
