package app.niix.core.storage

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The desktop version of SecureStorage: the same members as on Android, over the folders in
 * [StoragePaths] and the desktop keyring instead of an Android Context and keystore.
 */
class SecureStorage private constructor(
    private val paths: StoragePaths,
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

    /** Who voice calls are allowed with, in both directions. */
    val callPermissions: CallPermissions by lazy { CallPermissions(settings) }

    /** A clock that can't be wound back, for anything that decides how long data lives. */
    val clock: MonotonicClock by lazy { MonotonicClock(settings) }

    val lockedInbox: LockedInboxQueue by lazy { LockedInboxQueue(paths.filesDir, File(paths.noBackupDir, LOCKED_INBOX_KEYS_DIR)) }

    val wipeBeacon: WipeBeacon by lazy { WipeBeacon(paths.noBackupDir) }

    val receiveBeacon: ReceiveBeacon by lazy { ReceiveBeacon(paths.noBackupDir) }

    /** Ring keys for calls that should ring while the app is locked. See RingBeacon. */
    val ringBeacon: RingBeacon by lazy { RingBeacon(paths.noBackupDir) }

    /** Which store holds the keys protecting the data, or null before setup. See KeyVault. */
    fun keyStoreKind(): String? = when (KeyVault.backend()) {
        KeyVault.Backend.KEYRING -> KEY_STORE_KEYRING
        KeyVault.Backend.FILE -> KEY_STORE_FILE
        null -> null
    }

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

    fun attachmentsDir(): File = File(paths.filesDir, ATTACHMENTS_DIR)

    fun attachmentFile(attachmentId: String): File? = AttachmentFiles.resolve(attachmentsDir(), attachmentId)

    fun deleteAttachmentFile(attachmentId: String): Boolean = AttachmentFiles.delete(attachmentsDir(), attachmentId)

    fun backup(): EncryptedBackup =
        EncryptedBackup(database, attachmentCipher, paths.noBackupDir, attachmentsDir(), profilesDir())

    fun filesDir(): File = paths.filesDir

    fun cacheDir(): File = paths.cacheDir

    private fun profilesDir(): File = File(paths.filesDir, PROFILES_DIR)

    fun wipeAllData() {
        appLock.lock()
        secretProvider.clearAll()
        deleteDatabaseFiles()
        attachmentsDir().deleteRecursively()
        File(paths.filesDir, "niix-tor").deleteRecursively()
        File(paths.filesDir, "niix-tor-cache").deleteRecursively()
        profilesDir().deleteRecursively()
        lockedInbox.destroy()
        wipeBeacon.destroy()
        receiveBeacon.destroy()
        ringBeacon.destroy()
        File(paths.noBackupDir, LOCKED_INBOX_KEYS_DIR).deleteRecursively()
        File(paths.noBackupDir, BOOT_COUNT_FILE).delete()
        paths.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        paths.noBackupDir.listFiles()
            ?.filter { it.name.endsWith(".rekey-backup") || it.name.endsWith(".rekey-inprogress") }
            ?.forEach { runCatching { it.delete() } }
        KeyVault.destroy(listOf(KeystoreKeyManager.DEFAULT_ALIAS))
        StoragePaths.ensurePrivateDirectory(paths.filesDir)
        StoragePaths.ensurePrivateDirectory(paths.noBackupDir)
        StoragePaths.ensurePrivateDirectory(paths.cacheDir)
    }

    private fun deleteDatabaseFiles() {
        val base = File(paths.noBackupDir, Schema.DATABASE_FILENAME)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(base.parentFile, base.name + suffix).delete()
        }
    }

    companion object {
        const val KEY_STORE_KEYRING = "keyring"
        const val KEY_STORE_FILE = "file"
        private const val ATTACHMENTS_DIR = "attachments"
        private const val PROFILES_DIR = "niix-profiles"
        private const val LOCKED_INBOX_KEYS_DIR = "inbox-keys"

        @Volatile
        private var instance: SecureStorage? = null

        fun getInstance(paths: StoragePaths): SecureStorage {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: create(paths).also { instance = it }
            }
        }

        private fun create(paths: StoragePaths): SecureStorage {
            KeyVault.configure(File(paths.noBackupDir, VAULT_DIR))
            val secretProvider = DatabaseSecretProvider(paths.noBackupDir)
            val database = SecureDatabase(paths.noBackupDir)
            val clock = InactivityClock(bootCount = ::bootNumber, sinceBoot = android.os.SystemClock::elapsedRealtime)
            val appLock = AppLockManager(database, secretProvider, clock)
            return SecureStorage(paths, secretProvider, database, appLock, AttachmentCipher())
        }

        private const val VAULT_DIR = "vault"
        private const val BOOT_ID = "/proc/sys/kernel/random/boot_id"
        private const val BOOT_COUNT_FILE = "boots"

        /**
         * Android counts boots; Linux gives each boot a random id instead. A boot number is kept by
         * counting new boot ids, which is all InactivityClock needs: the same number within one
         * boot, a larger one after a restart.
         */
        private fun bootNumber(): Int = runCatching {
            val bootId = Files.readString(Path.of(BOOT_ID), StandardCharsets.US_ASCII).trim()
            require(bootId.isNotEmpty())
            synchronized(this) {
                val dir = instance?.paths?.noBackupDir ?: return@runCatching -1
                val file = File(dir, BOOT_COUNT_FILE)
                val (count, lastId) = if (file.isFile) {
                    file.readText().trim().split(' ').let { (it[0].toInt()) to it.getOrElse(1) { "" } }
                } else {
                    0 to ""
                }
                if (lastId == bootId) {
                    count
                } else {
                    val next = count + 1
                    val tmp = File(dir, "$BOOT_COUNT_FILE.tmp")
                    tmp.writeText("$next $bootId")
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                    next
                }
            }
        }.getOrDefault(-1)
    }
}
