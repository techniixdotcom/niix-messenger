package app.niix.core.storage

import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

class LockedException(message: String = "Database is locked") : IllegalStateException(message)

class SecureDatabase internal constructor(private val appContext: Context) {

    @Volatile
    private var database: SQLiteDatabase? = null

    fun isOpen(): Boolean = database != null

    fun open(): SQLiteDatabase = database ?: throw LockedException()

    internal fun openWith(passphrase: ByteArray): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            ensureNativeLoaded()
 // Before any open attempt: an interrupted rekey must be undone while the files are
 // still untouched.
            recoverInterruptedRekey()
            val opened = openInternal(databaseFile(), passphrase)
            opened.execSQL("PRAGMA foreign_keys = ON;")
            createSchema(opened)
            database = opened
            return opened
        }
    }

    internal fun changePassphrase(newPassphrase: ByteArray) {
        val db = open()
        db.changePassword(newPassphrase)
    }

    internal fun rekeyWithBackup(newPassphrase: ByteArray) {
        val db = open()
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }

        runCatching { db.execSQL("PRAGMA wal_checkpoint(FULL)") }
        backups.values.forEach { it.delete() }
        for ((source, backup) in backups) {
            if (source.exists()) source.copyTo(backup, overwrite = true)
        }

 // Written after the backups exist and removed only once the rekey is verified.
 //
 // The catch block below recovers from an exception, but not from the process dying --
 // a kill, a crash, a battery pull between the copy and the verification. That left
 // backups on disk with nothing to say whether the rekey had completed, and the next
 // rekey simply deleted them (line above), destroying the only copy of the old database.
 // The marker distinguishes the two cases: present means the rekey did not finish.
        runCatching { rekeyMarkerFile().writeBytes(byteArrayOf(1)) }

        try {
            db.changePassword(newPassphrase)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
 // Marker first: once it is gone the backups are no longer recovery data, so removing
 // it before them means a crash in between leaves stale copies rather than a state
 // that would restore over a database already rekeyed successfully.
            runCatching { rekeyMarkerFile().delete() }
            backups.values.forEach { it.delete() }
        } catch (e: Exception) {
            runCatching { db.close() }
            database = null
            for ((source, backup) in backups) {
                if (backup.exists()) backup.copyTo(source, overwrite = true) else source.delete()
            }
            runCatching { rekeyMarkerFile().delete() }
            backups.values.forEach { it.delete() }
            throw e
        }
    }

    internal fun recoverFromInterruptedRekey() {
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }
        if (backups.values.none { it.exists() }) return
        for ((source, backup) in backups) {
            if (backup.exists()) backup.copyTo(source, overwrite = true)
        }
        backups.values.forEach { it.delete() }
    }

    private fun fileSet(main: File): List<File> = listOf(
        main,
        File(main.parentFile, main.name + "-wal"),
        File(main.parentFile, main.name + "-shm"),
    )

    fun close() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    private fun openInternal(file: File, passphrase: ByteArray): SQLiteDatabase {

        return SQLiteDatabase.openOrCreateDatabase(
            file,
            passphrase,
            null as SQLiteDatabase.CursorFactory?,
            null as DatabaseErrorHandler?,
            null as SQLiteDatabaseHook?,
        )
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            Schema.DDL.forEach(db::execSQL)
            Schema.COLUMN_MIGRATIONS.forEach { (_, statement) ->
                try {
                    db.execSQL(statement)
                } catch (e: Exception) {
 // These are ADD COLUMN statements, run on every open. The one expected
 // failure is the column already existing, which simply means the migration
 // ran previously, that is ignored.
 //
 // Anything else is a real failure and is rethrown so the transaction rolls
 // back rather than being committed as successful. Swallowing every error
 // meant a genuinely failed migration produced a half-migrated database
 // marked valid, which later code would read as legitimate state, with
 // missing columns silently becoming missing authorization data.
                    if (!isDuplicateColumnError(e)) throw e
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** SQLite reports an already-applied ADD COLUMN as "duplicate column name". Matched on the
     * message because SQLCipher's binding does not expose a distinct error code for it. */
    private fun isDuplicateColumnError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("duplicate column")
    }

    private fun databaseFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME)

    private fun rekeyMarkerFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME + REKEY_MARKER_SUFFIX)

    /**
     * Rolls back a rekey that died partway.
     *
     * Marker present means we re-encrypted but never confirmed the new passphrase works, so
     * neither old nor new may open the db. Restoring the backups gets us back to something the
     * existing passcode opens. Redoing a passcode change is annoying; an unopenable db is not
     * recoverable. Runs before any open attempt, while the files are untouched.
     */
    private fun recoverInterruptedRekey() {
        val marker = rekeyMarkerFile()
        if (!marker.exists()) return
        runCatching {
            val live = databaseFile()
            for (source in fileSet(live)) {
                val backup = File(source.parentFile, source.name + BACKUP_SUFFIX)
                if (backup.exists()) {
                    backup.copyTo(source, overwrite = true)
                    backup.delete()
                } else {
 // No backup for this file means it did not exist when the rekey began, so
 // the recovered state should not have it either.
                    source.delete()
                }
            }
        }
        runCatching { marker.delete() }
    }

    companion object {
        private const val BACKUP_SUFFIX = ".rekey-backup"

        /** Presence means a rekey began and never verified. See recoverInterruptedRekey. */
        private const val REKEY_MARKER_SUFFIX = ".rekey-inprogress"
        private val nativeLoaded = AtomicBoolean(false)

        private fun ensureNativeLoaded() {
            if (nativeLoaded.compareAndSet(false, true)) {
                System.loadLibrary("sqlcipher")
            }
        }
    }
}
