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

    fun db(): SQLiteDatabase = database.open()

    fun lock() = appLock.lock()

    fun attachmentsDir(): File = File(appContext.filesDir, ATTACHMENTS_DIR)

    fun backup(): EncryptedBackup =
        EncryptedBackup(database, attachmentCipher, appContext.noBackupFilesDir)

    fun wipeAllData() {
        appLock.lock()
        secretProvider.clearAll()
        files.clear()
        deleteDatabaseFiles()
        attachmentsDir().deleteRecursively()
        // Embedded Tor working dir holds the onion service private key.
        File(appContext.filesDir, "niix-tor").deleteRecursively()
        File(appContext.filesDir, "niix-profiles").deleteRecursively()
        File(appContext.cacheDir, "niix-tor").deleteRecursively()
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
