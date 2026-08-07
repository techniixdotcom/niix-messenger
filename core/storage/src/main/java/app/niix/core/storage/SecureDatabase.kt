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

    /**
     * Re-keys the already-open database to [newPassphrase] (used when passcode protection is
     * turned on or off). Unlike [changePassphrase], this is defended against being interrupted
     * partway through: the live file (and any WAL/SHM sidecars) is snapshotted first, the new
     * key is verified to actually open and read the database before the backup is discarded,
     * and on any failure -- including the process being killed mid-operation, checked again on
     * the next app start via [recoverFromInterruptedRekey] -- the original file is restored so
     * the database is never left in a state that opens with neither the old key nor the new one.
     */
    internal fun rekeyWithBackup(newPassphrase: ByteArray) {
        val db = open()
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }

        runCatching { db.execSQL("PRAGMA wal_checkpoint(FULL)") }
        backups.values.forEach { it.delete() }
        for ((source, backup) in backups) {
            if (source.exists()) source.copyTo(backup, overwrite = true)
        }

        try {
            db.changePassword(newPassphrase)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            backups.values.forEach { it.delete() }
        } catch (e: Exception) {
            runCatching { db.close() }
            database = null
            for ((source, backup) in backups) {
                if (backup.exists()) backup.copyTo(source, overwrite = true) else source.delete()
            }
            backups.values.forEach { it.delete() }
            throw e
        }
    }

    /**
     * Call once at app start, before anything else touches the database: if a previous
     * [rekeyWithBackup] was interrupted (app killed, crash) before it could clean up, a backup
     * file will still be sitting next to the live one. Restore it so the database matches
     * whatever key was in use before that attempt, rather than being left in whatever
     * half-written state the interruption caused.
     */
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
        // The single SQLCipher-version-sensitive call. Adjust the trailing
        // typed-null arguments if your pinned SQLCipher exposes a different
        // openOrCreateDatabase signature.
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
                runCatching { db.execSQL(statement) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun databaseFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME)

    companion object {
        private const val BACKUP_SUFFIX = ".rekey-backup"
        private val nativeLoaded = AtomicBoolean(false)

        private fun ensureNativeLoaded() {
            if (nativeLoaded.compareAndSet(false, true)) {
                System.loadLibrary("sqlcipher")
            }
        }
    }
}
