package app.niix.core.storage

import android.content.Context
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SecureStorage private constructor(
    private val appContext: Context,
    private val secretProvider: DatabaseSecretProvider,
    val database: SecureDatabase,
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
    val devices: DeviceDao by lazy { DeviceDao(database) }

    /** Who may erase this device, and whose devices this one may erase. */
    val remoteWipe: RemoteWipeSettings by lazy { RemoteWipeSettings(settings) }

    /** A clock that can't be wound back, for anything that decides how long data lives. */
    val clock: MonotonicClock by lazy { MonotonicClock(settings) }

    val lockedInbox: LockedInboxQueue by lazy { LockedInboxQueue(appContext) }

    val wipeBeacon: WipeBeacon by lazy { WipeBeacon(appContext.noBackupFilesDir) }

    val receiveBeacon: ReceiveBeacon by lazy { ReceiveBeacon(appContext.noBackupFilesDir) }

    /**
     * The onion key: from the database when it's open, which also refreshes the copy kept for
     * receiving while locked (or removes it, with "Disconnect when locked" on); from that copy
     * when the database is closed. Null if it's closed and there's no copy.
     */
    fun onionKeySeed(): ByteArray? {
        if (!appLock.isUnlocked()) return receiveBeacon.onionKey()
        val seed = onionIdentity.getOrCreate()
        if (settings.getBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)) {
            receiveBeacon.destroy()
        } else {
            runCatching { receiveBeacon.updateOnionKey(seed.copyOf()) }
        }
        return seed
    }

    fun db(): SQLiteDatabase = database.open()

    fun lock() = appLock.lock()

    fun attachmentsDir(): File = File(appContext.filesDir, ATTACHMENTS_DIR)

    fun attachmentFile(attachmentId: String): File? = AttachmentFiles.resolve(attachmentsDir(), attachmentId)

    fun deleteAttachmentFile(attachmentId: String): Boolean = AttachmentFiles.delete(attachmentsDir(), attachmentId)

    fun backup(): EncryptedBackup =
        EncryptedBackup(database, attachmentCipher, appContext.noBackupFilesDir, attachmentsDir(), profilesDir())

    private fun profilesDir(): File = File(appContext.filesDir, PROFILES_DIR)

    fun wipeAllData() {
        appLock.lock()
        secretProvider.clearAll()
        deleteDatabaseFiles()
        attachmentsDir().deleteRecursively()

        // Everywhere Tor or the updater may have written. Tor's cache (kept under filesDir so
        // bootstraps stay fast) and a downloaded APK aren't key material, but they still say what
        // this device was doing and which build it ran. The old cacheDir path is cleared too, in
        // case an earlier build used it.
        File(appContext.filesDir, "niix-tor").deleteRecursively()
        File(appContext.filesDir, "niix-tor-cache").deleteRecursively()
        profilesDir().deleteRecursively()
        File(appContext.filesDir, "updates").deleteRecursively()
        // Queued messages are ciphertext, but they still show who was talking to this device and
        // when. Their keys go too, so they don't outlive the data they protected.
        lockedInbox.destroy()
        wipeBeacon.destroy()
        receiveBeacon.destroy()
        File(appContext.cacheDir, "niix-tor").deleteRecursively()
        File(appContext.cacheDir, "shared").deleteRecursively()

        // Everything left in the cache root. Listing prefixes would rely on someone remembering
        // every new temp file; anything in our cache is either regenerable or a plaintext copy of
        // something we keep encrypted.
        appContext.cacheDir?.listFiles()?.forEach { runCatching { it.deleteRecursively() } }

        // Rekey backups are full copies of the database, taken before a passcode change. A stale
        // one would be a complete copy of everything this wipe just destroyed.
        appContext.noBackupFilesDir?.listFiles()
            // And the in-progress marker, or the next open would try to recover a rekey from
            // backups that are gone.
            ?.filter { it.name.endsWith(".rekey-backup") || it.name.endsWith(".rekey-inprogress") }
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
        private const val PROFILES_DIR = "niix-profiles"

        /**
         * Older builds created an encrypted key-value store at every start and never put anything
         * in it. Its keyset, keystore key and folder are removed once; the preferences file
         * disappearing is what marks it done.
         */
        private fun removeRetiredFileStore(appContext: Context) {
            if (!appContext.deleteSharedPreferences("niix_file_keyset_prefs")) return
            File(appContext.filesDir, "niix-files").deleteRecursively()
            KeystoreKeyManager(keyAlias = "niix_file_master").clear()
        }

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
            val clock = InactivityClock(
                bootCount = {
                    android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
                },
                sinceBoot = android.os.SystemClock::elapsedRealtime,
            )
            val appLock = AppLockManager(database, secretProvider, clock)
            removeRetiredFileStore(appContext)
            return SecureStorage(appContext, secretProvider, database, appLock, AttachmentCipher())
        }
    }
}
