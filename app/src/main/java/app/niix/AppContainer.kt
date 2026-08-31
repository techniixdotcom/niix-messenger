package app.niix

import android.content.Context
import app.niix.core.crypto.CryptoEngine
import app.niix.core.messaging.ConversationManager
import app.niix.core.messaging.CoverTrafficScheduler
import app.niix.core.messaging.ExpirySweeper
import app.niix.core.messaging.MessageReceiver
import app.niix.core.relay.RelayManager
import app.niix.core.storage.EncryptedBackup
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
        storage.wipeAllData()
        crypto.forgetCachedIdentity()
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
