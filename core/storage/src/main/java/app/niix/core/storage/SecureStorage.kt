package app.niix.core.storage

import android.content.Context
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SecureStorage private constructor(
    private val appContext: Context,
    private val secretProvider: DatabaseSecretProvider,
    val database: SecureDatabase,
    val files: EncryptedFileStore,
    val appLock: AppLockManager,
    val attachmentCipher: AttachmentCipher,
) {

    val conversations: ConversationDao by lazy { ConversationDao(database) }
    val messages: MessageDao by lazy { MessageDao(database) }
    val members: MemberDao by lazy { MemberDao(database) }
    val attachments: AttachmentDao by lazy { AttachmentDao(database) }
    val contacts: ContactDao by lazy { ContactDao(database) }
    val blocklist: BlocklistDao by lazy { BlocklistDao(database) }
    val settings: SettingsStore by lazy { SettingsStore(database) }
    val pendingGroupInvites: PendingGroupInviteDao by lazy { PendingGroupInviteDao(database) }
    val groupSenderKeyState: GroupSenderKeyStateDao by lazy { GroupSenderKeyStateDao(database) }
    val groupRemoteSenderKeys: GroupRemoteSenderKeyDao by lazy { GroupRemoteSenderKeyDao(database) }
    val onionIdentity: OnionIdentityDao by lazy { OnionIdentityDao(database) }

    /** Remote wipe configuration. See RemoteWipeSettings for why the token is stored hashed. */
    val remoteWipe: RemoteWipeSettings by lazy { RemoteWipeSettings(settings) }

    /**
     * Messages received while locked, held until the database can be opened.
     *
     * Deliberately independent of [database] -- it has to work precisely when the database is
     * closed, so it uses its own hardware-backed keystore key rather than anything derived from
     * the passcode.
     */
    private val LOCKED_INBOX_KEY_ALIAS = "niix_locked_inbox"

    val lockedInbox: LockedInboxQueue by lazy {
        // Its own keystore alias rather than sharing the database secret's. Two independent
        // purposes should not share one key, and a distinct alias means the wipe below can
        // destroy it explicitly rather than relying on it happening to be the same entry.
        LockedInboxQueue(appContext, KeystoreKeyManager(keyAlias = LOCKED_INBOX_KEY_ALIAS))
    }
    val relayGrants: RelayGrantDao by lazy { RelayGrantDao(database) }

    fun db(): SQLiteDatabase = database.open()

    fun lock() = appLock.lock()

    fun attachmentsDir(): File = File(appContext.filesDir, ATTACHMENTS_DIR)

    fun attachmentFile(attachmentId: String): File? = AttachmentFiles.resolve(attachmentsDir(), attachmentId)

    fun deleteAttachmentFile(attachmentId: String): Boolean = AttachmentFiles.delete(attachmentsDir(), attachmentId)

    fun backup(): EncryptedBackup =
        EncryptedBackup(database, attachmentCipher, appContext.noBackupFilesDir)

    fun wipeAllData() {
        appLock.lock()
        secretProvider.clearAll()
        files.clear()
        deleteDatabaseFiles()
        attachmentsDir().deleteRecursively()

        // Every directory Tor or the updater may have written to. Tor's cache holds the network
        // consensus and descriptors (it lives under filesDir so bootstraps stay warm -- see
        // KmpTorTransport), and the updates directory can hold a downloaded APK. Neither is key
        // material, but a wipe should leave nothing behind that describes what this device was
        // doing or which build it was running. The legacy cacheDir path is still cleared so an
        // upgrade from a build that used it doesn't leave an orphaned directory behind.
        File(appContext.filesDir, "niix-tor").deleteRecursively()
        File(appContext.filesDir, "niix-tor-cache").deleteRecursively()
        File(appContext.filesDir, "niix-profiles").deleteRecursively()
        File(appContext.filesDir, "updates").deleteRecursively()
        // Queued inbound messages are ciphertext, but they still record who was talking to this
        // device and when. A wipe has to take them too.
        File(appContext.filesDir, "locked-inbox").deleteRecursively()
        // Destroy the key as well as the files. Leaving the alias behind would mean the wrapping
        // key outlives the data it protected, which matters most in exactly the case this wipe
        // exists for.
        runCatching { KeystoreKeyManager(keyAlias = LOCKED_INBOX_KEY_ALIAS).clear() }
        File(appContext.cacheDir, "niix-tor").deleteRecursively()
        File(appContext.cacheDir, "shared").deleteRecursively()

        // Everything the app has left loose in the cache root. Naming individual prefixes here
        // would repeat the mistake that let att_crop_* survive: a wipe must not depend on
        // somebody remembering to add each new temporary-file prefix to a list. Anything the
        // app wrote to its own cache is either regenerable or a plaintext copy of something the
        // database is encrypting, and neither is worth keeping through a duress wipe.
        appContext.cacheDir?.listFiles()?.forEach { runCatching { it.deleteRecursively() } }

        // Rekey backups are full copies of the database, written before a passcode change so an
        // interrupted rekey can be rolled back. A stale one is a complete copy of everything the
        // wipe just destroyed, sitting beside it.
        appContext.noBackupFilesDir?.listFiles()
            ?.filter { it.name.endsWith(".rekey-backup") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun deleteDatabaseFiles() {
        val base = File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(base.parentFile, base.name + suffix).delete()
        }
    }

    companion object {
        private const val ATTACHMENTS_DIR = "attachments"

        @Volatile
        private var instance: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(appContext: Context): SecureStorage {
            val secretProvider = DatabaseSecretProvider(appContext)
            val database = SecureDatabase(appContext)
            database.recoverFromInterruptedRekey()
            val appLock = AppLockManager(database, secretProvider)
            val files = EncryptedFileStore(appContext)
            val attachmentCipher = AttachmentCipher()
            return SecureStorage(appContext, secretProvider, database, files, appLock, attachmentCipher)
        }
    }
}
